package server;

import com.sun.net.httpserver.HttpServer;
import common.NetSnapshotDTO;
import common.RoomState;
import common.util.Hex;
import net.Wire;
import net.Wire.Envelope;

import com.fasterxml.jackson.databind.ObjectMapper;

import model.LevelsManager;
import server.ops.Metrics;
import server.storage.Store;

import java.io.*;
import java.net.InetSocketAddress;
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
    private final server.storage.Store store =
            new server.storage.Store(java.nio.file.Paths.get(System.getProperty("user.home"), ".phase3", "server"));


    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService tickExec = Executors.newSingleThreadScheduledExecutor();
    static Store _storeRef;
    static Metrics _metricsRef;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Queue<Session> matchmaking = new ConcurrentLinkedQueue<>();

    private final LevelsManager levels = new LevelsManager();
    private final Matchmaker matchmaker = new Matchmaker(matchmaking, levels);
    private final Metrics metrics = new Metrics();
    public GameServer(int port) { this.port = port; }

    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Server on " + ss.getLocalPort());

            // === storage ===
            try { store.open(); }
            catch (Exception e) { System.err.println("[Store] open: " + e.getMessage()); }
            System.out.println("[Store] writing to: " + store.file().toAbsolutePath());
            server.GameServer.bindStore(store);  // for Room→ACTIVE callback

            // === metrics ===
            metrics.bind(
                    (ConcurrentMap<String, ?>) sessions,
                    (ConcurrentMap<String, ?>) rooms,
                    (ConcurrentLinkedQueue<?>) matchmaking
            );
            server.GameServer.bindMetrics(metrics); // let onRoomActive bump matchesActive
            startHttpSidecar();                     // /health & /metrics on 8081

            // periodic metrics snapshot (every 5s)
            tickExec.scheduleAtFixedRate(
                    () -> System.out.println("[METRICS] " + metrics.snapshotJson()),
                    5, 5, TimeUnit.SECONDS
            );

            // === game tick ===
            tickExec.scheduleAtFixedRate(this::tickAll, 0, 33, TimeUnit.MILLISECONDS);

            // === accept loop ===
            while (true) {
                Socket s = ss.accept();
                pool.execute(() -> handleClient(s));
            }
        }
    }


    private void handleClient(Socket s) {
        Session session = null;
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             // IMPORTANT: autoFlush=false (second ctor arg)
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), false)) {

            s.setTcpNoDelay(true); // avoid Nagle adding extra latency

            String sid = UUID.randomUUID().toString();
            String token = UUID.randomUUID().toString();
            session = new Session(sid, token, out);
            sessions.put(sid, session);
            sessionsByToken.put(token, session);
            metrics.sessionsOpened.incrementAndGet();

            // start writer thread
            session.startWriterLoop();

            // ... send HELLO_S using NetIO (this will queue & writer will flush)
            NetIO.send(session, Wire.of("HELLO_S", sid, Map.of(
                    "serverTime", java.time.Instant.now().toEpochMilli(),
                    "reconnectToken", token,
                    "hmacKey", java.util.Base64.getEncoder().encodeToString(session.hmacKey)
            )));

            // read loop
            String line;
            while ((line = in.readLine()) != null) {
                Envelope env = Wire.decode(line);
                session.lastSeen = System.currentTimeMillis();

                switch (env.t) {
                    case "PING"      -> NetIO.send(session, Wire.of("PONG", sid, env.data));
                    case "JOIN_QUEUE" -> {
                        // If already matched, do not re-queue (prevents duplicate rooms).
                        if (session.room != null && session.room.started) {
                            NetIO.send(session, err("already_in_room", "Already in match " + session.room.id));
                            break;
                        }

                        String requested = (env.data != null) ? env.data.path("level").asText("") : "";
                        requested = (requested == null ? "" : requested.trim());

                        if (levels.getLevelConfigs().isEmpty()) {
                            NetIO.send(session, err("no_levels", "No levels loaded."));
                            break;
                        }
                        session.levelName = (requested.isBlank() || "default".equalsIgnoreCase(requested))
                                ? levels.getLevelName(0) : requested;

                        var smProbe = levels.getLevelManager(session.levelName);
                        if (smProbe == null) {
                            NetIO.send(session, err("bad_level", "unknown level: " + session.levelName));
                            break;
                        }

                        // ensure at most one copy in queue
                        while (matchmaking.remove(session)) {/* purge dup */}
                        session.inputs.clear();
                        matchmaking.add(session);

                        System.out.println("[MM] enqueued sid=" + session.sid + " level=" + session.levelName
                                + " qsize=" + matchmaking.size());

                        Room r;
                        synchronized (matchmaker) {
                            r = matchmaker.tryMatch();
                            if (r != null) rooms.put(r.id, r);
                        }

                        if (r != null) {
                            metrics.matchesStarted.incrementAndGet();

                            final String rid  = r.id;
                            final String lvl  = r.levelNameA;   // both sides play same level
                            final String tokA = r.a.token, tokB = r.b.token;

                            System.out.println(json("match_started",
                                    java.util.Map.of("roomId", rid, "level", lvl, "a", r.a.sid, "b", r.b.sid)));
                            storeSafe("matchStarted", () -> store.matchStarted(rid, lvl, tokA, tokB));

                            // DO NOT: r.beginBuildPhase(..)
                            // DO NOT: send START here — Matchmaker already did this

                            NetIO.send(session, Wire.of("JOINED", session.sid,
                                    java.util.Map.of("queued", false, "level", session.levelName)));
                        } else {
                            NetIO.send(session, Wire.of("JOINED", session.sid,
                                    java.util.Map.of("queued", true, "level", session.levelName)));
                        }
                    }
                    case "COMMAND" -> {
                        var d = env.data;
                        long seq = (d == null) ? -1 : d.path("seq").asLong(-1);

                        if (session.room == null) {
                            System.out.println("[CMD DROP] no room sid=" + session.sid + " seq=" + seq);
                            break;
                        }
                        if (!session.room.started) {
                            System.out.println("[CMD DROP] room not started sid=" + session.sid + " seq=" + seq);
                            break;
                        }
                        if (seq <= session.lastSeq) {
                            System.out.println("[CMD DROP] dup/out-of-order sid=" + session.sid
                                    + " seq=" + seq + " last=" + session.lastSeq);
                            break;
                        }
                        //new code
                        if (!session.cmdRate.tryAcquire()) {
                            long nowMs = System.currentTimeMillis();
                            if (nowMs - session.lastRateWarnMs > 1000) {
                                session.lastRateWarnMs = nowMs;
                                NetIO.send(session, err("rate_limited", "too many commands"));
                            }
                            System.out.println("[CMD DROP] rate_limited sid=" + session.sid + " seq=" + seq);
                            break;
                        }
                        Room r = session.room;
                        if (r == null || (! (session == r.a || session == r.b))) {
                            System.out.println("[CMD DROP] session not bound to room players sid=" + session.sid + " seq=" + seq);
                            break;
                        }

// (optional) quick trace — super helpful while you test
                        System.out.println("[CMD] sid=" + session.sid
                                + " side=" + ((session == r.a) ? "A" : "B")
                                + " seq=" + seq);

                        session.lastSeq = seq;
                        if (session.inputs.size() >= 512) session.inputs.poll();
                        session.inputs.add(env);
                    }


                    case "RESUME" -> {
                        // Identify target session by token (preferred) or sid
                        String tokenReq = (env.data != null) ? env.data.path("token").asText(null) : null;
                        Session target = (tokenReq != null) ? sessionsByToken.get(tokenReq) : null;
                        if (target == null) {
                            String sSid = (env.data != null ? env.data.path("sid").asText(null) : null);
                            if (sSid == null) sSid = env.sid;
                            target = (sSid != null) ? sessions.get(sSid) : null;
                        }
                        if (target == null) {
                            NetIO.send(session, err("resume_fail", "unknown token_or_sid"));
                            break;
                        }

                        // Optional integrity info from client
                        long lastSeqCli = (env.data != null) ? env.data.path("lastSeq").asLong(-2) : -2;
                        String lastMacHexCli = (env.data != null) ? env.data.path("lastMac").asText("") : "";
                        byte[] lastMacCli = new byte[0];
                        try { if (!lastMacHexCli.isEmpty()) lastMacCli = Hex.decode(lastMacHexCli); }
                        catch (Exception ignore) { lastMacCli = new byte[0]; }

                        // Rebind writer to the authoritative session
                        sessions.remove(session.sid);
                        sessionsByToken.remove(session.token);
                        target.rebindOut(out);
                        session = target;  // this IO thread now serves the resumed session

                        // Decide if client’s view matches server; if not we still RESUME but client should adopt server tip
                        boolean matches =
                                (lastSeqCli == session.lastSeq) &&
                                        (java.util.Arrays.equals(lastMacCli, session.lastMac) || lastMacCli.length == 0);

                        // Send RESUMED with authoritative chain tip and identifiers
                        NetIO.send(session, Wire.of("RESUMED", session.sid, Map.of(
                                "ok", true,
                                "sid", session.sid,
                                "reconnectToken", session.token,
                                "serverLastSeq", session.lastSeq,
                                "serverLastMac", Hex.encode(session.lastMac),
                                "match", matches
                        )));

                        // If in a room, re-send START & a fresh SNAPSHOT
//                        Room r = session.room;
//                        if (r != null && r.started) {
//                            final String side = (r.a == session) ? "A" : "B";
//                            final long buildMsLeft =
//                                    (r.state == RoomState.BUILD)
//                                            ? Math.max(0L, r.buildDeadlineMs - System.currentTimeMillis())
//                                            : 0L;
//
//                            NetIO.send(session, Wire.of("START", session.sid, Map.of(
//                                    "roomId", r.id,
//                                    "side", side,
//                                    "tick", r.tick,
//                                    "level", r.levelNameA, // same for both sides
//                                    "state", r.state.name(),
//                                    "buildMs", buildMsLeft
//                            )));
//
//                            var snap = side.equals("A")
//                                    ? r.levelA.toSnapshot(r.id, r.state, "A")
//                                    : r.levelB.toSnapshot(r.id, r.state, "B");
//                            NetIO.send(session, Wire.of("SNAPSHOT", session.sid, snap));
//                        }
                        Room r = session.room;
                        if (r != null && r.started) {
                            final String side = (r.a == session) ? "A" : "B";
                            final long buildMsLeft =
                                    (r.state == RoomState.BUILD)
                                            ? Math.max(0L, r.buildDeadlineMs - System.currentTimeMillis())
                                            : 0L;

                            session.room = r; // keep the back reference in sync

                            NetIO.send(session, Wire.of("START", session.sid, Map.of(
                                    "roomId", r.id,
                                    "side", side,
                                    "tick", r.tick,
                                    "level", r.levelNameA,
                                    "state", r.state.name(),
                                    "buildMs", buildMsLeft
                            )));

                            // ✅ unified, side-aware composer (you already switched to this):
                            NetSnapshotDTO snap = r.composeSnapshotFor(session);
                            NetIO.send(session, Wire.of("SNAPSHOT", session.sid, snap));
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
            if (session != null) {
                dropSession(session, "io_error_or_closed");
            }
        }
    }

    private void tickAll() {
        final long tickStartNs = System.nanoTime();
        final long now = System.currentTimeMillis();

        // timeouts
        for (Session s : sessions.values()) {
            if (now - s.lastSeen > 12_000) dropSession(s, "timeout");
        }

        // rooms
        for (Room r : rooms.values()) {
            if (!r.started) continue;
            r.tickOnce();
            rooms.entrySet().removeIf(e -> e.getValue() == null || !e.getValue().started);
        }
        final long elapsedNs = System.nanoTime() - tickStartNs;
        metrics.observeTickNanos(elapsedNs);
    }

    private static Wire.Envelope err(String code, String msg) {
        return Wire.of("ERROR", null, Map.of("code", code, "msg", msg));
    }

    private void dropSession(Session s, String reason) {
        if (s == null) return;
        s.stopWriterLoop(); // <— stop the writer thread first

        metrics.sessionsClosed.incrementAndGet();
        System.out.println(json("session_close", Map.of("sid", s.sid, "reason", reason)));
        try { NetIO.send(s, err("disconnect", reason)); } catch (Exception ignored) {}
        sessions.remove(s.sid);
        sessionsByToken.remove(s.token);
        matchmaking.remove(s);
        Room r = s.room;
        if (r != null) {
            boolean wasActive = (r.state == RoomState.ACTIVE);
            r.started = false;

            if (r.a == s && r.b != null) {
                NetIO.send(r.b, err("opponent_left", reason));
                if (wasActive) {
                    metrics.forfeitWins.incrementAndGet();
                    storeSafe("matchForfeit(A left)",
                            () -> store.matchForfeit(r.id, r.b.token, r.a.token, reason));
                }
            }
            if (r.b == s && r.a != null) {
                NetIO.send(r.a, err("opponent_left", reason));
                if (wasActive) {
                    metrics.forfeitWins.incrementAndGet();
                    storeSafe("matchForfeit(B left)",
                            () -> store.matchForfeit(r.id, r.a.token, r.b.token, reason));
                }
            }
            storeSafe("matchEnded", () -> store.matchEnded(r.id, reason));
            metrics.matchesEnded.incrementAndGet();
            rooms.remove(r.id);
        }

    }
    static void bindStore(server.storage.Store s) { _storeRef = s; }
    static void bindMetrics(Metrics m) { _metricsRef = m; }
    static void onRoomActive(Room r) {
        if (_storeRef != null) _storeRef.matchActive(r.id);
    }
    private void storeSafe(String op, Runnable r) {
        try { r.run(); }
        catch (Exception e) {
            System.err.println("[Store] " + op + " failed: " + e.getMessage());
            // e.printStackTrace(); // uncomment in dev if you want the stack
        }
    }
    private void startHttpSidecar() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(8081), 0);
            http.createContext("/health", ex -> {
                byte[] b = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (var os = ex.getResponseBody()) { os.write(b); }
            });
            http.createContext("/metrics", ex -> {
                byte[] b = metrics.snapshotJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (var os = ex.getResponseBody()) { os.write(b); }
            });
            http.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MetricsHttp"); t.setDaemon(true); return t;
            }));
            http.start();
            System.out.println("[HTTP] metrics on http://127.0.0.1:8081/metrics  health on /health");
        } catch (Exception e) {
            System.err.println("[HTTP] sidecar failed: " + e.getMessage());
        }
    }
    private static String json(String type, Map<String, ?> fields) {
        try {
            var m = new com.fasterxml.jackson.databind.ObjectMapper();
            var n = m.createObjectNode();
            n.put("ev", type);
            for (var e : fields.entrySet()) n.putPOJO(e.getKey(), e.getValue());
            return n.toString();
        } catch (Exception e) { return "{\"ev\":\"" + type + "\"}"; }
    }
}
