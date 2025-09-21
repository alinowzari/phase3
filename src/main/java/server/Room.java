package server;

import com.fasterxml.jackson.databind.JsonNode;
import common.AbilityType;
import common.MatchInfoDTO;
import common.NetSnapshotDTO;
import common.PointDTO;
import common.RoomState;
import common.cmd.*;
import common.cmd.marker.ActivePhaseCmd;
import common.cmd.marker.AnyPhaseCmd;
import common.cmd.marker.BuildPhaseCmd;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One room, two independent levels (A,B). */
final class Room {
    private static final int SIM_HZ = 30;
    private static final int SNAPSHOT_HZ = 10;
    private static final int SNAPSHOT_EVERY = SIM_HZ / SNAPSHOT_HZ;
    // identities / sockets
    final String  id;
    final Session a, b;

    // level choice (per side)
    final String        levelNameA, levelNameB;
    final LevelSession  levelA, levelB;  // ← separate authoritative models

    // lifecycle
    volatile boolean     activeLogged = false;
    volatile boolean     started      = true;
    volatile long        tick         = 0;
    volatile RoomState   state        = RoomState.BUILD;
    volatile long        buildDeadlineMs;
    volatile boolean     readyA, readyB;

    // per-side command de-dup
    private final Set<Long> seenSeqA = ConcurrentHashMap.newKeySet();
    private final Set<Long> seenSeqB = ConcurrentHashMap.newKeySet();
    volatile boolean launchedA = false, launchedB = false;


    Room(String id,
         Session a, Session b,
         String levelNameA, String levelNameB,
         LevelSession lvlA, LevelSession lvlB) {
        this.id = id;
        this.a = a; this.b = b;
        this.levelNameA = levelNameA; this.levelNameB = levelNameB;
        this.levelA = lvlA; this.levelB = lvlB;
    }

    void beginBuildPhase(long durationMs) {
        state = RoomState.BUILD;
        buildDeadlineMs = System.currentTimeMillis() + durationMs;
        readyA = false; readyB = false;
        launchedA = false; launchedB = false;  // ✨ NEW
        tick = 0;
    }

    void tickOnce() {
        tick++;

        // 1) drain incoming COMMANDs, routing per side
        drainCommands(a, levelA, seenSeqA);
        drainCommands(b, levelB, seenSeqB);

        // 2) advance each authoritative simulation
        levelA.step(33);
        levelB.step(33);
        System.out.println("[LEN] A=" + levelA.sm.getWireUsedPx() + " B=" + levelB.sm.getWireUsedPx());

        // 3) send snapshots (coalesced by NetIO writer)
        if ((tick % SNAPSHOT_EVERY) == 0) {
            var snapA = composeSnapshot(levelA, levelB, "A");
            var snapB = composeSnapshot(levelB, levelA, "B");
            System.out.println("[SNAP OUT] A ready="+snapA.ui().get("readyA")+" coinsA="+snapA.ui().get("coinsA"));
            System.out.println("[SNAP OUT] B ready="+snapB.ui().get("readyB")+" coinsB="+snapB.ui().get("coinsB"));
            NetIO.send(a, net.Wire.of("SNAPSHOT", a.sid, snapA));
            NetIO.send(b, net.Wire.of("SNAPSHOT", b.sid, snapB));
        }

        // 4) phase transitions / lifecycle
        if (state == RoomState.BUILD) {
            boolean timeUp = System.currentTimeMillis() >= buildDeadlineMs;
            if (launchedA && launchedB) {
                state = RoomState.ACTIVE;
                System.out.println("[ROOM " + id + "] → ACTIVE (someone launched)");
            }
        }
        if (state == RoomState.ACTIVE && !activeLogged) {
            activeLogged = true;
            GameServer.onRoomActive(this);
        }
    }

    // Room.java
    private Map<String,Object> buildUi(String sideTag) {
        Map<String,Object> ui = new java.util.HashMap<>();
        ui.put("side", sideTag); // "A" or "B"

        // readiness & coins (authoritative)
        ui.put("readyA", levelA.isReady());
        ui.put("readyB", levelB.isReady());
        ui.put("coinsA", levelA.score());
        ui.put("coinsB", levelB.score());
        ui.put("levelPassedA", levelA.isLevelPassed());
        ui.put("levelPassedB", levelB.isLevelPassed());

        // totals (if you want “Total:” in HUD)
        ui.put("totalA", levelA.sm.getTotalCoins());
        ui.put("totalB", levelB.sm.getTotalCoins());

        // wire HUD (per side)
        ui.put("wireUsedA",    levelA.sm.getWireUsedPx());
        ui.put("wireBudgetA", (int) levelA.sm.getWireBudgetPx());
        ui.put("wireUsedB",    levelB.sm.getWireUsedPx());
        ui.put("wireBudgetB", (int) levelB.sm.getWireBudgetPx());

        // (optional) buttons
        boolean buildPhase = (state == RoomState.BUILD);
        ui.put("canBuildA",  buildPhase && !levelA.isLaunched());
        ui.put("canBuildB",  buildPhase && !levelB.isLaunched());
        ui.put("canLaunchA", buildPhase && levelA.canLaunch());
        ui.put("canLaunchB", buildPhase && levelB.canLaunch());
        return ui;
    }

    private NetSnapshotDTO composeSnapshot(LevelSession me, LevelSession opp, String sideTag) {
        var info = new MatchInfoDTO(id, me.levelId(), state, tick, me.timeLeftMs(), me.score(), opp.score(), sideTag);
        var stateDto = mapper.Mapper.toState(me.sm);
        return new NetSnapshotDTO(info, stateDto, buildUi(sideTag));
    }
    private void drainCommands(Session s, LevelSession target, Set<Long> seenSet) {
        if (s == null) return;

        // NEW: keep only the latest move per entity/bend this tick
        java.util.Map<Integer, MoveSystemCmd> latestSystemMove = new java.util.HashMap<>();
        record BendKey(int fs, int fo, int ts, int ti, int bendIndex) {}
        java.util.Map<BendKey, MoveBendCmd> latestBendMove = new java.util.HashMap<>();

        net.Wire.Envelope env;
        while ((env = s.inputs.poll()) != null) {
            try {
                if (!"COMMAND".equals(env.t)) continue;
                com.fasterxml.jackson.databind.JsonNode d = env.data; if (d == null) continue;

                long seq = d.path("seq").asLong(-1);
                if (seq >= 0 && !seenSet.add(seq)) {
                    NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, java.util.Map.of("seq", seq, "dup", true)));
                    continue;
                }

                com.fasterxml.jackson.databind.JsonNode cmdNode = d.get("cmd");
                if (cmdNode == null) continue;
                ClientCommand cmd = decodeCmd(cmdNode);

                if (!isAllowedInPhase(cmd, state)) {
                    NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, java.util.Map.of("seq", seq, "dropped", true, "why", "phase")));
                    continue;
                }

                if (cmd instanceof LaunchCmd) {
                    if (s == a) launchedA = true; else if (s == b) launchedB = true;
                    target.enqueue(cmd); // keep explicit launch
                } else if (cmd instanceof ReadyCmd) {
                    if (s == a) readyA = true; else if (s == b) readyB = true;
                } else if (cmd instanceof MoveSystemCmd m) {
                    latestSystemMove.put(m.systemId(), m); // coalesce
                } else if (cmd instanceof MoveBendCmd mb) {
                    BendKey k = new BendKey(mb.fromSystemId(), mb.fromOutputIndex(), mb.toSystemId(), mb.toInputIndex(), mb.bendIndex());
                    latestBendMove.put(k, mb); // coalesce
                } else {
                    target.enqueue(cmd); // other commands as-is
                }

                NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, java.util.Map.of("seq", seq)));
            } catch (Exception ex) {
                System.err.println("[Room " + id + "] bad command: " + ex);
            }
        }

        // Apply at most one of each move now
        for (var m : latestSystemMove.values()) target.enqueue(m);
        for (var m : latestBendMove.values())   target.enqueue(m);
    }

    /** Parse a compact command node (we strip “type” before mapping to records). */
    private ClientCommand decodeCmd(JsonNode cmdNode) {
        if (cmdNode == null || !cmdNode.isObject())
            throw new IllegalArgumentException("bad command json");

        var on = (com.fasterxml.jackson.databind.node.ObjectNode) cmdNode;
        String t = on.path("type").asText("").trim();
        on.remove("type"); // keep Jackson from double-decoding

        long  seq = on.path("seq").asLong(-1);
        int   fs  = on.path("fromSystemId").asInt(-1);
        int   fo  = on.path("fromOutputIndex").asInt(-1);
        int   ts  = on.path("toSystemId").asInt(-1);
        int   ti  = on.path("toInputIndex").asInt(-1);

        java.util.function.Function<JsonNode, PointDTO> P =
                j -> new PointDTO(j.path("x").asInt(), j.path("y").asInt());

        return switch (t) {
            case "AddLineCmd",   "addLine"    -> new AddLineCmd(seq, fs, fo, ts, ti);
            case "RemoveLineCmd","removeLine" -> new RemoveLineCmd(seq, fs, fo, ts, ti);
            case "MoveSystemCmd","moveSystem" -> {
                int sysId = on.path("systemId").asInt();
                int x     = on.path("x").asInt();
                int y     = on.path("y").asInt();
                yield new MoveSystemCmd(seq, sysId, x, y);
            }
            case "AddBendCmd",   "addBend"    -> {
                var footA  = P.apply(on.path("footA"));
                var middle = P.apply(on.path("middle"));
                var footB  = P.apply(on.path("footB"));
                yield new AddBendCmd(seq, fs, fo, ts, ti, footA, middle, footB);
            }
            case "MoveBendCmd",  "moveBend"   -> {
                int bendIndex = on.path("bendIndex").asInt();
                var newMid    = P.apply(on.path("newMiddle"));
                yield new MoveBendCmd(seq, fs, fo, ts, ti, bendIndex, newMid);
            }
            case "UseAbilityCmd","useAbility" -> {
                String aStr = on.path("ability").asText();
                var ability = AbilityType.valueOf(aStr);
                var at      = P.apply(on.path("at"));
                yield new UseAbilityCmd(seq, ability, fs, fo, ts, ti, at);
            }
            case "ReadyCmd",     "ready"      -> new ReadyCmd(seq);
            case "LaunchCmd",    "launch"     -> new LaunchCmd(seq);
            default -> throw new IllegalArgumentException("unknown cmd type: " + t);
        };
    }

    private static boolean isAllowedInPhase(ClientCommand cmd, RoomState st) {
        if (cmd instanceof AnyPhaseCmd) return true;
        if (st == RoomState.BUILD)  return (cmd instanceof BuildPhaseCmd);
        if (st == RoomState.ACTIVE) return (cmd instanceof ActivePhaseCmd);
        return false;
    }
    public NetSnapshotDTO composeSnapshotFor(Session who) {
        if (who == a) return composeSnapshot(levelA, levelB, "A");
        if (who == b) return composeSnapshot(levelB, levelA, "B");
        // Not one of our players – minimal neutral snapshot (optional)
        return composeSnapshot(levelA, levelB, "A"); // safe default or throw
    }
}
