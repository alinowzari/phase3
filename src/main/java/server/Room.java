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

        // 3) snapshots (per side)
        var snapA = composeSnapshot(levelA, levelB, "A");
        var snapB = composeSnapshot(levelB, levelA, "B");
        NetIO.send(a, net.Wire.of("SNAPSHOT", a.sid, snapA));
        NetIO.send(b, net.Wire.of("SNAPSHOT", b.sid, snapB));

        if (state == RoomState.BUILD) {
            boolean timeUp = System.currentTimeMillis() >= buildDeadlineMs;
            // 🔒 TEMP: Require explicit launches from *both* sides; ignore timeUp while debugging
            if (launchedA || launchedB) {
                state = RoomState.ACTIVE;
                System.out.println("[ROOM " + id + "] → ACTIVE (someone launched)");
            }
        }
        if (state == RoomState.ACTIVE && !activeLogged) {
            activeLogged = true;
            GameServer.onRoomActive(this);
        }
    }
    /** Compose a snapshot for one side (“me”), including both HUD polylines and budgets. */
    private NetSnapshotDTO composeSnapshot(LevelSession me, LevelSession opp, String sideTag) {
        var info = new MatchInfoDTO(
                id,
                me.levelId(),
                state,
                tick,
                me.timeLeftMs(),
                me.score(),           // my score (ok to vary per-recipient)
                opp.score(),          // opponent score
                sideTag
        );

        // State tree must still be "me"
        var stateDto = mapper.Mapper.toState(me.sm);

        Map<String, Object> ui = new HashMap<>();
        ui.put("side", sideTag);

        // 🔴 Always label by actual side, not by 'me/opp'
        ui.put("wireUsedA",    levelA.sm.getWireUsedPx());
        ui.put("wireBudgetA", (int) levelA.sm.getWireBudgetPx());
        ui.put("wireUsedB",    levelB.sm.getWireUsedPx());
        ui.put("wireBudgetB", (int) levelB.sm.getWireBudgetPx());

        // Same for HUD lines
        ui.put("hudLinesA", levelA.hudLinesForUi());
        ui.put("hudLinesB", levelB.hudLinesForUi());

        return new NetSnapshotDTO(info, stateDto, ui);
    }

    // Room.java  (inside drainCommands)
    private void drainCommands(Session s, LevelSession target, Set<Long> seenSet) {
        if (s == null) return;

        net.Wire.Envelope env;
        while ((env = s.inputs.poll()) != null) {
            try {
                if (!"COMMAND".equals(env.t)) continue;
                JsonNode d = env.data; if (d == null) continue;

                long seq = d.path("seq").asLong(-1);
                if (seq >= 0 && !seenSet.add(seq)) {
                    NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, Map.of("seq", seq, "dup", true)));
                    continue;
                }

                JsonNode cmdNode = d.get("cmd"); if (cmdNode == null) continue;
                ClientCommand cmd = decodeCmd(cmdNode);

                // 🔎 TRACE what we received and current room state
                System.out.println("[ROOM " + id + "] cmd from " + (s == a ? "A" : "B")
                        + " type=" + cmd.getClass().getSimpleName()
                        + " state=" + state);

                if (!isAllowedInPhase(cmd, state)) {
                    System.out.println("[ROOM " + id + "] DROP " + cmd.getClass().getSimpleName()
                            + " in phase " + state + " (seq=" + seq + ")");
                    NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, Map.of("seq", seq, "dropped", true, "why", "phase")));
                    continue;
                }

                if (cmd instanceof LaunchCmd) {
                    if (s == a){
                        launchedA = true;
                    }
                    else if (s == b){
                        launchedB = true;
                    }
                    System.out.println("[ROOM " + id + "] Launch by " + (s==a?"A":"B")
                            + " launchedA=" + launchedA + " launchedB=" + launchedB);
                } else if (cmd instanceof ReadyCmd) {
                    if (s == a) readyA = true; else if (s == b) readyB = true;
                } else {
                    target.enqueue(cmd);
                }

                NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, Map.of("seq", seq)));
            } catch (Exception ex) {
                System.err.println("[Room " + id + "] bad command: " + ex);
            }
        }
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
