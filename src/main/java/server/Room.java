package server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.dto.RoomState;
import common.dto.cmd.ClientCommand;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.AnyPhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
import common.dto.util.Canon;
import net.Wire;

import java.util.Map;
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
    private void drainInputs(Session s) {
        net.Wire.Envelope e;
        while ((e = s.inputs.poll()) != null) {
            if (!"COMMAND".equals(e.t)) continue;
            try {
                var payload = e.data;
                var cmdNode = (payload != null && payload.has("cmd")) ? payload.get("cmd") : payload;

                ClientCommand cmd = net.Wire.read(cmdNode, ClientCommand.class);
                System.out.println("[ROOM " + id + "] cmd=" + cmd.getClass().getSimpleName()
                        + " from " + (s == a ? "A" : "B") + " phase=" + state);

                if (isLaunch(cmd) || cmd.getClass().getSimpleName().equals("ReadyCmd")) {
                    if (s == a) readyA = true; else if (s == b) readyB = true;
                    System.out.println("[ROOM " + id + "] ready flags A=" + readyA + " B=" + readyB);
                }

                if (!isAllowedInPhase(cmd, state)) {
                    System.out.println("[ROOM " + id + "] DROP " + cmd.getClass().getSimpleName()
                            + " in phase " + state);
                    continue;
                }

                level.enqueue(cmd);
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
    // Room.java
    private void drainCommands(Session s) {
        if (s == null) return;

        net.Wire.Envelope env;
        while ((env = s.inputs.poll()) != null) {
            try {
                if (!"COMMAND".equals(env.t)) continue;
                var d = env.data; if (d == null) continue;

                long seq = d.path("seq").asLong(-1);
                var cmdNode = d.get("cmd"); if (cmdNode == null) continue;

                // ↓↓↓ decode without leaving "type" in the JSON sent to Jackson
                ClientCommand cmd = decodeCmd(cmdNode);

                if (cmd instanceof common.dto.cmd.ReadyCmd) {
                    if (s == a) readyA = true; else if (s == b) readyB = true;
                } else {
                    level.enqueue(cmd);
                }

                NetIO.send(s, net.Wire.of("CMD_ACK", s.sid, java.util.Map.of("seq", seq)));
            } catch (Exception ex) {
                System.err.println("[Room " + id + "] bad command: " + ex);
            }
        }
    }
    // Room.java
    private ClientCommand decodeCmd(com.fasterxml.jackson.databind.JsonNode cmdNode) {
        if (cmdNode == null || !cmdNode.isObject())
            throw new IllegalArgumentException("bad command json");

        var on = (com.fasterxml.jackson.databind.node.ObjectNode) cmdNode;
        String t = on.path("type").asText("").trim();
        on.remove("type"); // never let Jackson see it again

        // common scalars
        long  seq = on.path("seq").asLong(-1);
        int   fs  = on.path("fromSystemId").asInt(-1);
        int   fo  = on.path("fromOutputIndex").asInt(-1);
        int   ts  = on.path("toSystemId").asInt(-1);
        int   ti  = on.path("toInputIndex").asInt(-1);

        // tiny helper for points
        java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, common.dto.PointDTO> P =
                j -> new common.dto.PointDTO(j.path("x").asInt(), j.path("y").asInt());

        switch (t) {
            case "AddLineCmd", "addLine" -> {
                return new common.dto.cmd.AddLineCmd(seq, fs, fo, ts, ti);
            }
            case "RemoveLineCmd", "removeLine" -> {
                return new common.dto.cmd.RemoveLineCmd(seq, fs, fo, ts, ti);
            }
            case "MoveSystemCmd", "moveSystem" -> {
                int sysId = on.path("systemId").asInt();
                int x     = on.path("x").asInt();
                int y     = on.path("y").asInt();
                return new common.dto.cmd.MoveSystemCmd(seq, sysId, x, y);
            }
            case "AddBendCmd", "addBend" -> {
                var footA  = P.apply(on.path("footA"));
                var middle = P.apply(on.path("middle"));
                var footB  = P.apply(on.path("footB"));
                return new common.dto.cmd.AddBendCmd(seq, fs, fo, ts, ti, footA, middle, footB);
            }
            case "MoveBendCmd", "moveBend" -> {
                int bendIndex = on.path("bendIndex").asInt();
                var newMid    = P.apply(on.path("newMiddle"));
                return new common.dto.cmd.MoveBendCmd(seq, fs, fo, ts, ti, bendIndex, newMid);
            }
            case "UseAbilityCmd", "useAbility" -> {
                String aStr = on.path("ability").asText();
                var ability = common.dto.AbilityType.valueOf(aStr);
                var at      = P.apply(on.path("at"));
                return new common.dto.cmd.UseAbilityCmd(seq, ability, fs, fo, ts, ti, at);
            }
            case "ReadyCmd", "ready" -> {
                return new common.dto.cmd.ReadyCmd(seq);
            }
            case "LaunchCmd", "launch" -> {
                return new common.dto.cmd.LaunchCmd(seq);
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
