package server;

import model.LevelsManager;
import model.SystemManager;

import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;

final class Matchmaker {
    private final Queue<Session> queue;
    private final LevelsManager levels;

    Matchmaker(Queue<Session> queue, LevelsManager levels) {
        this.queue = queue;
        this.levels = levels;
    }

    /** Try to create one room; returns it if started, else null. */
    synchronized Room tryMatch() {
        Session a = queue.poll();
        if (a == null) return null;

        // Find same-level opponent only
        Session b = null;
        for (var it = queue.iterator(); it.hasNext();) {
            Session s = it.next();
            if (java.util.Objects.equals(s.levelName, a.levelName)) {
                b = s; it.remove(); break;
            }
        }
        // No same-level opponent yet → requeue A and wait
        if (b == null) { queue.add(a); return null; }

        return startRoomSameLevel(a, b);
    }


    /** Start a room where BOTH sides play the SAME level (independent instances). */
    private Room startRoomSameLevel(Session a, Session b) {
        // Choose the common level: A's preference wins if different.
        final String commonLevel = (a.levelName == null) ? "" : a.levelName;

        // Build two brand-new SystemManagers from the SAME level config (no sharing).
        SystemManager smA = levels.getSystemManagerByName(null, commonLevel);
        SystemManager smB = levels.getSystemManagerByName(null, commonLevel);

        // Sanity: these must be different objects
        java.lang.System.out.println("[MATCH] commonLevel=" + commonLevel
                + "  smA@" + java.lang.System.identityHashCode(smA)
                + "  smB@" + java.lang.System.identityHashCode(smB)
                + "  sameRef? " + (smA == smB));

        long durationMs = 180_000L; // tweak if needed
        LevelSession levelA = new LevelSession(commonLevel, smA, durationMs);
        LevelSession levelB = new LevelSession(commonLevel, smB, durationMs);

        String roomId = UUID.randomUUID().toString();
        Room r = new Room(roomId, a, b, commonLevel, commonLevel, levelA, levelB);

        // Bind and start BUILD phase
        a.room = r; b.room = r;
        r.beginBuildPhase(180_000L);

        // START messages — both sides get the SAME level name
        NetIO.send(a, net.Wire.of("START", a.sid, java.util.Map.of(
                "roomId", roomId, "side", "A", "tick", r.tick,
                "level",  commonLevel, "state", "BUILD", "buildMs", 180_000L
        )));
        NetIO.send(b, net.Wire.of("START", b.sid, java.util.Map.of(
                "roomId", roomId, "side", "B", "tick", r.tick,
                "level",  commonLevel, "state", "BUILD", "buildMs", 180_000L
        )));
        return r;
    }
}
