package server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.dto.RoomState;
import common.dto.cmd.ClientCommand;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.AnyPhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
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

    /** Called by the server’s tick loop (~33ms). */
//    void tickOnce() {
//        tick++;
//        drainInputs(a);
//        drainInputs(b);
//        level.step(33);
//
//        var snapA = level.toSnapshot(id, state, "A");
//        var snapB = level.toSnapshot(id, state, "B");
//        NetIO.send(a, Wire.of("SNAPSHOT", a.sid, snapA));
//        NetIO.send(b, Wire.of("SNAPSHOT", b.sid, snapB));
//
//        if (state == RoomState.BUILD) {
//            boolean timeUp = System.currentTimeMillis() >= buildDeadlineMs;
//            if (timeUp || (readyA && readyB)) {
//                state = RoomState.ACTIVE;
//                System.out.println("[ROOM " + id + "] → ACTIVE (timeUp=" + timeUp
//                        + " readyA=" + readyA + " readyB=" + readyB + ")");
//            }
//        }
//        if (state == RoomState.ACTIVE && !activeLogged) {
//            activeLogged = true;
//            GameServer.onRoomActive(this); // bumps matchesActive + store.matchActive
//        }
//    }

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
        drainInputs(a);
        drainInputs(b);
        level.step(33);

        var snapA = level.toSnapshot(id, state, "A");
        var snapB = level.toSnapshot(id, state, "B");
        NetIO.send(a, net.Wire.of("SNAPSHOT", a.sid, snapA));
        NetIO.send(b, net.Wire.of("SNAPSHOT", b.sid, snapB));

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
