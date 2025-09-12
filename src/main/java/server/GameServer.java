//// src/main/java/server/GameServer.java
//package server;
//
//import common.dto.RoomState;
//import common.dto.cmd.ClientCommand;
//import net.Wire;
//import net.Wire.Envelope;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//
//import model.LevelsManager;
//import model.SystemManager;
//
//import java.io.BufferedReader;
//import java.io.IOException;
//import java.io.InputStreamReader;
//import java.io.OutputStreamWriter;
//import java.io.PrintWriter;
//import java.net.ServerSocket;
//import java.net.Socket;
//import java.nio.charset.StandardCharsets;
//import java.time.Instant;
//import java.util.*;
//import java.util.concurrent.*;
//
///**
// * Online authoritative server using the real game model:
// *  • Client tells server which level → JOIN_QUEUE { "level": "Level 1" }
// *  • Matchmaking prefers same-level pairs
// *  • Each room owns a LevelSession(SystemManager) built for that level
// *  • IO thread enqueues ClientCommand; tick thread applies + steps session
// *  • Snapshots are NetSnapshotDTO from LevelSession.toSnapshot(...)
// *  • Per-session send lock prevents interleaved NDJSON
// */
//public final class GameServer {
//    public static void main(String[] args) throws Exception {
//        new GameServer(5555).start();
//    }
//
//    private static final ObjectMapper JSON = new ObjectMapper();
//    private final model.LevelsManager levels = new model.LevelsManager();
//    private final int port;
//    private final ExecutorService pool = Executors.newCachedThreadPool();
//    private final ScheduledExecutorService tickExec = Executors.newSingleThreadScheduledExecutor();
//    private static final long BUILD_DURATION_MS = 30_000L;
//
//    // sessions & rooms
//    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
//    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
//    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
//    private final Queue<Session> matchmaking = new ConcurrentLinkedQueue<>();
//
//    public GameServer(int port) { this.port = port; }
//
//    public void start() throws IOException {
//        try (ServerSocket ss = new ServerSocket(port)) {
//            System.out.println("Server on " + ss.getLocalPort());
//            // ~30Hz tick
//            tickExec.scheduleAtFixedRate(this::tickAll, 0, 33, TimeUnit.MILLISECONDS);
//
//            while (true) {
//                Socket s = ss.accept();
//                pool.execute(() -> handleClient(s));
//            }
//        }
//    }
//
//    private void handleClient(Socket s) {
//        Session session = null;
//        try (s;
//             BufferedReader in = new BufferedReader(
//                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
//             PrintWriter out = new PrintWriter(
//                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
//
//            // bootstrap
//            String sid = UUID.randomUUID().toString();
//            String token = UUID.randomUUID().toString();
//            session = new Session(sid, token, out);
//            sessions.put(sid, session);
//            sessionsByToken.put(token, session);
//
//            send(session, Wire.of("HELLO_S", sid, Map.of(
//                    "serverTime", Instant.now().toEpochMilli(),
//                    "reconnectToken", token
//            )));
//
//            // read loop
//            String line;
//            while ((line = in.readLine()) != null) {
//                Envelope env = Wire.decode(line);
//                session.lastSeen = System.currentTimeMillis();
//
//                switch (env.t) {
//                    case "PING" -> send(session, Wire.of("PONG", sid, env.data));
//
//                    case "HELLO_C" -> { /* reserved */ }
//
//                    case "JOIN_QUEUE" -> {
//                        // Client may send {"level":"Level 1"}; default "default"
//                        String requested = (env.data != null)
//                                ? env.data.path("level").asText("default")
//                                : "default";
//                        session.levelName = (requested == null || requested.isBlank())
//                                ? "default" : requested;
//
//                        session.inputs.clear();
//                        matchmaking.add(session);
//                        tryMatch();
//                        send(session, Wire.of("JOINED", sid, Map.of(
//                                "queued", true,
//                                "level", session.levelName
//                        )));
//                    }
//
//                    // The real path: client sends typed command JSON
//                    case "COMMAND" -> {
//                        JsonNode d = env.data;
//                        long seq = d == null ? -1 : d.path("seq").asLong(-1);
//                        if (seq > session.lastSeq) {
//                            session.lastSeq = seq;
//                            // Only accept commands when the player is in an active room
//                            if (session.room != null && session.room.started) {
//                                session.inputs.add(env);            // ok, will be drained
//                            }
//                            // else: ignore (or queue per your design)
//                        }
//                    }
//
//                    // (optional legacy) ignore toy "INPUT" in the real server
//                    case "INPUT" -> { /* no-op in authoritative model */ }
//
//                    case "RESUME" -> {
//                        // prefer resume via token; fallback to sid
//                        String tokenReq = env.data != null ? env.data.path("token").asText(null) : null;
//                        Session target = tokenReq != null ? sessionsByToken.get(tokenReq) : null;
//                        if (target == null) {
//                            String sSid = (env.data != null ? env.data.path("sid").asText(null) : null);
//                            if (sSid == null) sSid = env.sid;
//                            target = sSid != null ? sessions.get(sSid) : null;
//                        }
//                        if (target != null) {
//                            // drop the temp session we just created for this connection
//                            sessions.remove(session.sid);
//                            sessionsByToken.remove(session.token);
//                            // re-bind writer to the existing session (under its send lock)
//                            synchronized (target.sendLock) {
//                                target.out = out;
//                            }
//                            session = target; // from now on, this IO thread serves the resumed session
//                            send(session, Wire.of("RESUMED", session.sid, Map.of("ok", true)));
//                        } else {
//                            send(session, err("resume_fail", "unknown token_or_sid"));
//                        }
//                    }
//
//                    case "BYE" -> dropSession(session, "client_bye");
//
//                    default -> send(session, err("unknown_type", env.t));
//                }
//            }
//        } catch (IOException ignored) {
//            // connection closed/reset; cleanup below
//        } finally {
//            if (session != null) dropSession(session, "io_error_or_closed");
//        }
//    }
//
//    /** Matchmaking that prefers same-level opponents; builds a LevelSession for that level. */
//    private void tryMatch() {
//        Session a = matchmaking.poll();
//        if (a == null) return;
//
//        // look for an opponent who picked the same level
//        for (Iterator<Session> it = matchmaking.iterator(); it.hasNext();) {
//            Session b = it.next();
//            if (Objects.equals(b.levelName, a.levelName)) {
//                it.remove();
//                startRoom(a, b, a.levelName);
//                return;
//            }
//        }
//        // no same-level opponent yet → put A back and wait
//        matchmaking.add(a);
//    }
//    private void startRoom(Session a, Session b, String levelName) {
//        // Server loads its own model from the same JSON used offline
//        model.SystemManager sm = levels.getLevelManager(levelName);
//        if (sm == null) {
//            // safe fallback so we never NPE; construct an empty/default manager
//            sm = new model.SystemManager(new model.Loader.GameStatus(), levelName);
//        }
//
//        server.LevelSession level = new server.LevelSession(levelName, sm, 180_000L);
//
//        String roomId = java.util.UUID.randomUUID().toString();
//        Room r = new Room(roomId, a, b, levelName, level);
//        rooms.put(roomId, r);
//        a.room = r; b.room = r;
//        r.started = true; r.tick = 0;
//        r.state = RoomState.BUILD;
//        r.buildDeadlineMs = System.currentTimeMillis() + BUILD_DURATION_MS;
//        r.readyA = false; r.readyB = false;
//
//
//        send(a, net.Wire.of("START", a.sid, Map.of(
//                "roomId", roomId, "side", "A", "tick", r.tick, "level", levelName,"state", "BUILD", "buildMs", BUILD_DURATION_MS
//        )));
//        send(b, net.Wire.of("START", b.sid, Map.of(
//                "roomId", roomId, "side", "B", "tick", r.tick, "level", levelName,"state", "BUILD", "buildMs", BUILD_DURATION_MS
//        )));
//    }
//    /** Main tick: timeouts + drain → apply → step → snapshot. */
//    private void tickAll() {
//        long now = System.currentTimeMillis();
//
//        // time out dead sessions
//        for (Session s : sessions.values()) {
//            if (now - s.lastSeen > 12_000) dropSession(s, "timeout");
//        }
//
//        // update rooms
//        for (Room r : rooms.values()) {
//            if (!r.started) continue;
//
//            r.tick++;
//
//            // drain queued envelopes into ClientCommand → enqueue to LevelSession
//            drainInputsToLevel(r.a, r);
//            drainInputsToLevel(r.b, r);
//
//            // advance authoritative simulation (~33ms)
//            r.level.step(33);
//
//            // snapshots (one per side; LevelSession supplies state/ui and meta)
//            var snapA = r.level.toSnapshot(r.id, r.state, "A");
//            var snapB = r.level.toSnapshot(r.id, r.state, "B");
//
//            send(r.a, Wire.of("SNAPSHOT", r.a.sid, snapA));
//            send(r.b, Wire.of("SNAPSHOT", r.b.sid, snapB));
//
//            if (r.state == RoomState.BUILD) {
//                long nowMs = System.currentTimeMillis();
//                boolean timeUp = nowMs >= r.buildDeadlineMs;
//                if (timeUp || (r.readyA && r.readyB)) {
//                    r.state = RoomState.ACTIVE;
//                }
//            }
//        }
//    }
//
//    /** Convert queued JSON to ClientCommand and feed LevelSession (tick thread). */
//    private static void drainInputsToLevel(Session s, Room r) {
//        Envelope e;
//        while ((e = s.inputs.poll()) != null) {
//            if (!"COMMAND".equals(e.t)) continue;
//            try {
//                ClientCommand cmd = JSON.treeToValue(e.data, ClientCommand.class);
//                // Detect ready-up
//                if (cmd instanceof common.dto.cmd.LaunchCmd) {
//                    if (s == r.a) r.readyA = true;
//                    else if (s == r.b) r.readyB = true;
//                }
//            } catch (Exception ex) {
//                ex.printStackTrace();
//            }
//        }
//    }
//
//    /** Thread-safe send: prevents interleaved NDJSON for a single session. */
//    private static void send(Session s, Envelope e) {
//        synchronized (s.sendLock) {
//            PrintWriter pw = s.out;
//            if (pw == null) return;                 // ← add this guard
//            pw.print(Wire.encode(e));
//            pw.flush();
//        }
//    }
//
//    private static Envelope err(String code, String msg) {
//        return Wire.of("ERROR", null, Map.of("code", code, "msg", msg));
//    }
//
//    /* ---------- inner models ---------- */
//
//    private static final class Session {
//        final String sid, token;
//        volatile PrintWriter out;
//        final Object sendLock = new Object();
//        volatile long lastSeen = System.currentTimeMillis();
//        final Queue<Envelope> inputs = new ConcurrentLinkedQueue<>();
//        volatile long lastSeq = -1;
//        volatile Room room;
//        volatile String levelName = "default";
//
//        Session(String sid, String token, PrintWriter out) {
//            this.sid = sid; this.token = token; this.out = out;
//        }
//    }
//
//    private static final class Room {
//        final String id; final Session a, b;
//        final String levelName;
//        final LevelSession level;
//        volatile boolean started;
//        volatile long tick;
//        volatile RoomState state = RoomState.BUILD;
//        volatile long buildDeadlineMs;
//        volatile boolean readyA;
//        volatile boolean readyB;
//
//        Room(String id, Session a, Session b, String levelName, LevelSession level) {
//            this.id = id; this.a = a; this.b = b; this.levelName = levelName; this.level = level;
//        }
//    }
//
//    private void dropSession(Session s, String reason) {
//        try { send(s, err("disconnect", reason)); } catch (Exception ignored) {}
//        sessions.remove(s.sid);
//        sessionsByToken.remove(s.token);
//        matchmaking.remove(s);
//
//        Room r = s.room;
//        if (r != null) {
//            r.started = false;
//            if (r.a == s && r.b != null) send(r.b, err("opponent_left", reason));
//            if (r.b == s && r.a != null) send(r.a, err("opponent_left", reason));
//            rooms.remove(r.id);
//        }
//    }
//}
// src/main/java/server/GameServer.java
package server;

import net.Wire;
import net.Wire.Envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import model.LevelsManager;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class GameServer {
    public static void main(String[] args) throws Exception {
        new GameServer(5555).start();
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService tickExec = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Queue<Session> matchmaking = new ConcurrentLinkedQueue<>();

    private final LevelsManager levels = new LevelsManager();
    private final Matchmaker matchmaker = new Matchmaker(matchmaking, levels);

    public GameServer(int port) { this.port = port; }

    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Server on " + ss.getLocalPort());

            tickExec.scheduleAtFixedRate(this::tickAll, 0, 33, TimeUnit.MILLISECONDS);

            while (true) {
                Socket s = ss.accept();
                pool.execute(() -> handleClient(s));
            }
        }
    }

    private void handleClient(Socket s) {
        Session session = null;
        try (s;
             BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out    = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // bootstrap
            String sid = UUID.randomUUID().toString();
            String token = UUID.randomUUID().toString();
            session = new Session(sid, token, out);
            sessions.put(sid, session);
            sessionsByToken.put(token, session);

            NetIO.send(session, Wire.of("HELLO_S", sid, Map.of(
                    "serverTime", Instant.now().toEpochMilli(),
                    "reconnectToken", token
            )));

            // read loop
            String line;
            while ((line = in.readLine()) != null) {
                Envelope env = Wire.decode(line);
                session.lastSeen = System.currentTimeMillis();

                switch (env.t) {
                    case "PING"      -> NetIO.send(session, Wire.of("PONG", sid, env.data));
                    case "HELLO_C"   -> { /* reserved */ }

                    case "JOIN_QUEUE" -> {
                        String requested = (env.data != null) ? env.data.path("level").asText("") : "";
                        requested = (requested == null ? "" : requested.trim());
                        if (levels.getLevelConfigs().isEmpty()) {
                            NetIO.send(session, err("no_levels", "No levels loaded."));
                            break;
                        }

                        session.levelName = (requested.isBlank() || "default".equalsIgnoreCase(requested))
                                ? levels.getLevelName(0)
                                : requested;

                        var smProbe = levels.getLevelManager(session.levelName);
                        if (smProbe == null) {
                            NetIO.send(session, err("bad_level", "unknown level: " + session.levelName));
                            break;
                        }

                        session.inputs.clear();
                        matchmaking.add(session);

                        // inside GameServer, in the JOIN_QUEUE case — after you compute session.levelName and add to matchmaking
                        Room r = matchmaker.tryMatch();
                        if (r != null) {
                            rooms.put(r.id, r);

                            // --- KICK OFF MATCH ---
                            long buildMs = 30_000L;
                            r.beginBuildPhase(buildMs);

                            // Tell both clients to start (BUILD phase)
                            NetIO.send(r.a, Wire.of("START", r.a.sid,
                                    Map.of("side","A","level", r.levelName, "state","BUILD", "buildMs", buildMs)));
                            NetIO.send(r.b, Wire.of("START", r.b.sid,
                                    Map.of("side","B","level", r.levelName, "state","BUILD", "buildMs", buildMs)));

                            System.out.println("[ROOM " + r.id + "] START sent → A=" + r.a.sid + " B=" + r.b.sid +
                                    " level=" + r.levelName);
                        }

                        NetIO.send(session, Wire.of("JOINED", session.sid,
                                Map.of("queued", true, "level", session.levelName)));

                    }


                    case "COMMAND" -> {
                        JsonNode d = env.data;
                        long seq = (d == null) ? -1 : d.path("seq").asLong(-1);
                        if (seq > session.lastSeq && session.room != null && session.room.started) {
                            // ✅ Rate limit per-session (drops if exceeded)
                            if (!session.cmdRate.tryAcquire()) {
                                long nowMs = System.currentTimeMillis();
                                if (nowMs - session.lastRateWarnMs > 1000) {
                                    session.lastRateWarnMs = nowMs;
                                    NetIO.send(session, err("rate_limited", "too many commands"));
                                }
                                break;
                            }
                            session.lastSeq = seq;
                            if (session.inputs.size() >= 512){
                                session.inputs.poll();}
                            session.inputs.add(env);
                        }
                    }

                    case "RESUME" -> {
                        // resume by token (preferred), fallback to sid
                        String tokenReq = env.data != null ? env.data.path("token").asText(null) : null;
                        Session target = (tokenReq != null) ? sessionsByToken.get(tokenReq) : null;
                        if (target == null) {
                            String sSid = (env.data != null ? env.data.path("sid").asText(null) : null);
                            if (sSid == null) sSid = env.sid;
                            target = (sSid != null) ? sessions.get(sSid) : null;
                        }
                        if (target != null) {
                            sessions.remove(session.sid);
                            sessionsByToken.remove(session.token);
                            target.rebindOut(out);
                            session = target; // this IO thread now serves the resumed session
                            NetIO.send(session, Wire.of("RESUMED", session.sid, Map.of("ok", true)));
                            Room r = session.room;
                            if (r != null && r.started) {
                                final String side = (r.a == session) ? "A" : "B";
                                final long buildMsLeft =
                                        (r.state == common.dto.RoomState.BUILD)
                                                ? Math.max(0L, r.buildDeadlineMs - System.currentTimeMillis())
                                                : 0L;
                                // Re-send START with current state and remaining build time (if any)
                                NetIO.send(session, Wire.of("START", session.sid, Map.of(
                                        "roomId", r.id,
                                        "side", side,
                                        "tick", r.tick,
                                        "level", r.levelName,
                                        "state", r.state.name(),
                                        "buildMs", buildMsLeft
                                )));
                                // Then push a fresh snapshot so the client UI can repaint instantly
                                var snap = r.level.toSnapshot(r.id, r.state, side);
                                NetIO.send(session, Wire.of("SNAPSHOT", session.sid, snap));
                            }
                        } else {
                            NetIO.send(session, err("resume_fail", "unknown token_or_sid"));
                        }
                    }

                    case "BYE" -> dropSession(session, "client_bye");

                    default -> NetIO.send(session, err("unknown_type", env.t));
                }
            }
        }
        catch (IOException ignored) {
            System.out.println(ignored.getMessage());
        }
        finally {
            if (session != null) dropSession(session, "io_error_or_closed");
        }
    }

    private void tickAll() {
        long now = System.currentTimeMillis();

        // timeouts
        for (Session s : sessions.values()) {
            if (now - s.lastSeen > 12_000) dropSession(s, "timeout");
        }

        // rooms
        for (Room r : rooms.values()) {
            if (!r.started) continue;
            r.tickOnce();
        }
    }

    private static Wire.Envelope err(String code, String msg) {
        return Wire.of("ERROR", null, Map.of("code", code, "msg", msg));
    }

    private void dropSession(Session s, String reason) {
        try { NetIO.send(s, err("disconnect", reason)); } catch (Exception ignored) {}
        sessions.remove(s.sid);
        sessionsByToken.remove(s.token);
        matchmaking.remove(s);

        Room r = s.room;
        if (r != null) {
            r.started = false;
            if (r.a == s && r.b != null) NetIO.send(r.b, err("opponent_left", reason));
            if (r.b == s && r.a != null) NetIO.send(r.a, err("opponent_left", reason));
            rooms.remove(r.id);
        }
    }
}
