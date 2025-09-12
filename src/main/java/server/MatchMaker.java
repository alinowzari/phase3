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

        // Prefer same-level opponent
        for (Iterator<Session> it = queue.iterator(); it.hasNext();) {
            Session b = it.next();
            if (Objects.equals(b.levelName, a.levelName)) {
                it.remove();
                return startRoom(a, b, a.levelName);
            }
        }
        // none yet → put A back and wait
        queue.add(a);
        return null;
    }

    private Room startRoom(Session a, Session b, String levelName) {
        SystemManager sm = levels.getLevelManager(levelName);
        if (sm == null) sm = new SystemManager(new model.Loader.GameStatus(), levelName);
        LevelSession level = new LevelSession(levelName, sm, 180_000L);

        String roomId = UUID.randomUUID().toString();
        Room r = new Room(roomId, a, b, levelName, level);
        a.room = r; b.room = r;
        r.beginBuildPhase(30_000L);

        NetIO.send(a, net.Wire.of("START", a.sid, java.util.Map.of(
                "roomId", roomId, "side", "A", "tick", r.tick, "level", levelName,
                "state", "BUILD", "buildMs", 30_000L
        )));
        NetIO.send(b, net.Wire.of("START", b.sid, java.util.Map.of(
                "roomId", roomId, "side", "B", "tick", r.tick, "level", levelName,
                "state", "BUILD", "buildMs", 30_000L
        )));
        return r;
    }
}
