package server;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.AbilityType;
import common.PointDTO;
import common.cmd.*;
import common.RoomState;
import common.cmd.marker.ActivePhaseCmd;
import common.cmd.marker.AnyPhaseCmd;
import common.cmd.marker.BuildPhaseCmd;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class Room {
    final String id;
    final Session a, b;
    final String levelName;
    final LevelSession level;
    volatile boolean activeLogged = false;
    volatile boolean started = true;
    volatile long tick = 0;
    volatile RoomState state = RoomState.BUILD;
    volatile long buildDeadlineMs;
    volatile boolean readyA, readyB;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Set<Long> seenSeqA = ConcurrentHashMap.newKeySet();
    private final Set<Long> seenSeqB = ConcurrentHashMap.newKeySet();

    Room(String id, Session a, Session b, String levelName, LevelSession level) {
        this.id = id; this.a = a; this.b = b; this.levelName = levelName; this.level = level;
    }

    void beginBuildPhase(long durationMs) {
        state = RoomState.BUILD;
        buildDeadlineMs = System.currentTimeMillis() + durationMs;
        readyA = false; readyB = false;
        tick = 0;
    }

    /** Legacy path (kept in case you still call it elsewhere). Now side-aware. */
    private void drainInputs(Session s) {
        net.Wire.Envelope e;
        while ((e = s.inputs.poll()) != null) {
            if (!"COMMAND".equals(e.t)) continue;
            try {
                var payload = e.data;
                var cmdNode = (payload != null && payload.has("cmd")) ? payload.get("cmd") : payload;

                ClientCommand cmd = net.Wire.read(cmdNode, ClientCommand.class);
                final String side = (s == a) ? "A" : "B";

                System.out.println("[ROOM " + id + "] cmd=" + cmd.getClass().getSimpleName()
                        + " from " + side + " phase=" + state);

                if (isLaunch(cmd) || cmd instanceof ReadyCmd) {
                    if (s == a) readyA = true; else if (s == b) readyB = true;
                    System.out.println("[ROOM " + id + "] ready flags A=" + readyA + " B=" + readyB);
                }

                if (!isAllowedInPhase(cmd, state)) {
                    System.out.println("[ROOM " + id + "] DROP " + cmd.getClass().getSimpleName()
                            + " in phase " + state);
                    continue;
                }

                // route to the correct layer
                if (!(cmd instanceof ReadyCmd)) {
                    level.enqueue(cmd, side);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    void tickOnce() {
        tick++;

        // 1) drain queued COMMAND envelopes -> typed commands -> LevelSession
        drainCommands(a);
        drainCommands(b);

        // 2) advance the authoritative simulation
        level.step(33);

        // 3) broadcast snapshots
        var snapA = level.toSnapshot(id, state, "A");
        var snapB = level.toSnapshot(id, state, "B");
        NetIO.send(a, net.Wire.of("SNAPSHOT", a.sid, snapA));
        NetIO.send(b, net.Wire.of("SNAPSHOT", b.sid, snapB));

        // 4) room state machine (unchanged)
        if (state == RoomState.BUILD) {
            boolean timeUp = System.currentTimeMillis() >= buildDeadlineMs;
            if (timeUp || (readyA && readyB)) {
                state = RoomState.ACTIVE;
                System.out.println("[ROOM " + id + "] → ACTIVE (timeUp=" + timeUp
                        + " readyA=" + readyA + " readyB=" + readyB + ")");
            }
        }
        if (state == RoomState.ACTIVE && !activeLogged) {
            activeLogged = true;
            GameServer.onRoomActive(this);
        }
    }

    /** Main drain used in tickOnce; now computes side and passes it to LevelSession.enqueue. */
    private void drainCommands(Session s) {
        if (s == null) return;

        net.Wire.Envelope env;
        while ((env = s.inputs.poll()) != null) {
            try {
                if (!"COMMAND".equals(env.t)) continue;
                var d = env.data; if (d == null) continue;

                long seq = d.path("seq").asLong(-1);
                var cmdNode = d.get("cmd"); if (cmdNode == null) continue;

                ClientCommand cmd = decodeCmd(cmdNode);
                final String side = (s == a) ? "A" : "B";

                if (cmd instanceof ReadyCmd) {
                    if (s == a) readyA = true; else if (s == b) readyB = true;
                } else {
                    level.enqueue(cmd, side); // ← side-aware enqueue
                }

                NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, java.util.Map.of("seq", seq)));
            } catch (Exception ex) {
                System.err.println("[Room " + id + "] bad command: " + ex);
            }
        }
    }

    private ClientCommand decodeCmd(com.fasterxml.jackson.databind.JsonNode cmdNode) {
        if (cmdNode == null || !cmdNode.isObject())
            throw new IllegalArgumentException("bad command json");

        var on = (com.fasterxml.jackson.databind.node.ObjectNode) cmdNode;
        String t = on.path("type").asText("").trim();
        on.remove("type"); // never let Jackson see it again

        long  seq = on.path("seq").asLong(-1);
        int   fs  = on.path("fromSystemId").asInt(-1);
        int   fo  = on.path("fromOutputIndex").asInt(-1);
        int   ts  = on.path("toSystemId").asInt(-1);
        int   ti  = on.path("toInputIndex").asInt(-1);

        java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, PointDTO> P =
                j -> new PointDTO(j.path("x").asInt(), j.path("y").asInt());

        switch (t) {
            case "AddLineCmd", "addLine" -> {
                return new AddLineCmd(seq, fs, fo, ts, ti);
            }
            case "RemoveLineCmd", "removeLine" -> {
                return new RemoveLineCmd(seq, fs, fo, ts, ti);
            }
            case "MoveSystemCmd", "moveSystem" -> {
                int sysId = on.path("systemId").asInt();
                int x     = on.path("x").asInt();
                int y     = on.path("y").asInt();
                return new MoveSystemCmd(seq, sysId, x, y);
            }
            case "AddBendCmd", "addBend" -> {
                var footA  = P.apply(on.path("footA"));
                var middle = P.apply(on.path("middle"));
                var footB  = P.apply(on.path("footB"));
                return new AddBendCmd(seq, fs, fo, ts, ti, footA, middle, footB);
            }
            case "MoveBendCmd", "moveBend" -> {
                int bendIndex = on.path("bendIndex").asInt();
                var newMid    = P.apply(on.path("newMiddle"));
                return new MoveBendCmd(seq, fs, fo, ts, ti, bendIndex, newMid);
            }
            case "UseAbilityCmd", "useAbility" -> {
                String aStr = on.path("ability").asText();
                var ability = AbilityType.valueOf(aStr);
                var at      = P.apply(on.path("at"));
                return new UseAbilityCmd(seq, ability, fs, fo, ts, ti, at);
            }
            case "ReadyCmd", "ready" -> {
                return new ReadyCmd(seq);
            }
            case "LaunchCmd", "launch" -> {
                return new LaunchCmd(seq);
            }
            default -> throw new IllegalArgumentException("unknown cmd type: " + t);
        }
    }

    private static boolean isLaunch(ClientCommand cmd) {
        return cmd.getClass().getSimpleName().equals("LaunchCmd");
    }
    private static boolean isAllowedInPhase(ClientCommand cmd, RoomState st) {
        if (cmd instanceof AnyPhaseCmd) return true;
        if (st == RoomState.BUILD)  return (cmd instanceof BuildPhaseCmd);
        if (st == RoomState.ACTIVE) return (cmd instanceof ActivePhaseCmd);
        return false;
    }
}
