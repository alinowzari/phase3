// src/main/java/server/GameServer.java
package server;

import net.Wire;
import net.Wire.Envelope;

import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
public final class GameServer {
    public static void main(String[] args) throws Exception {
        new GameServer(5555).start();
    }

    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService tickExec = Executors.newSingleThreadScheduledExecutor();

    // sessions & rooms
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Queue<Session> matchmaking = new ConcurrentLinkedQueue<>();

    public GameServer(int port) { this.port = port; }

    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Server on " + ss.getLocalPort());
            tickExec.scheduleAtFixedRate(this::tickAll, 0, 33, TimeUnit.MILLISECONDS); // ~30Hz
            while (true) {
                Socket s = ss.accept();
                pool.execute(() -> handleClient(s));
            }
        }
    }

    private void handleClient(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {

            // session bootstrap
            String sid = UUID.randomUUID().toString();
            String token = UUID.randomUUID().toString();
            Session session = new Session(sid, token, out);
            sessions.put(sid, session);

            send(out, Wire.of("HELLO_S", sid, Map.of(
                    "serverTime", Instant.now().toEpochMilli(),
                    "reconnectToken", token
            )));

            String line;
            while ((line = in.readLine()) != null) {
                Wire.Envelope env = Wire.decode(line);
                session.lastSeen = System.currentTimeMillis();

                session.lastSeen = System.currentTimeMillis();

                switch (env.t) {
                    case "PING" -> send(out, Wire.of("PONG", sid, env.data));
                    case "HELLO_C" -> { /* no-op in MVP */ }
                    case "JOIN_QUEUE" -> {
                        session.inputs.clear();
                        matchmaking.add(session);
                        tryMatch();
                        send(out, Wire.of("JOINED", sid, Map.of("queued", true)));
                    }
                    case "INPUT" -> {
                        JsonNode d = env.data;                       // <-- requires the import below
                        long seq = d.path("seq").asLong(-1);
                        if (seq <= session.lastSeq) break;           // drop stale or duplicate inputs
                        session.lastSeq = seq;
                        session.inputs.add(env);                     // queued for the tick thread
                        System.out.println("[INPUT] sid=" + session.sid + " seq=" + seq + " data=" + d);
                    }
                    case "RESUME" -> {
                        String sSid = env.sid;
                        if (sSid != null && sessions.containsKey(sSid)) {
                            send(out, Wire.of("RESUMED", sSid, Map.of("ok", true)));
                        } else {
                            send(out, err("resume_fail", "unknown session"));
                        }
                    }
                    case "BYE" -> dropSession(session, "client_bye");
                    default -> send(out, err("unknown_type", env.t));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void tryMatch() {
        Session a = matchmaking.poll();
        Session b = matchmaking.poll();
        if (a == null || b == null) {
            if (a != null) matchmaking.add(a);
            return;
        }
        String roomId = UUID.randomUUID().toString();
        Room r = new Room(roomId, a, b);
        rooms.put(roomId, r);
        a.room = r; b.room = r;
        r.started = true;
        r.tick = 0;
        send(a.out, Wire.of("START", a.sid, Map.of("roomId", roomId, "side", "A", "tick", r.tick)));
        send(b.out, Wire.of("START", b.sid, Map.of("roomId", roomId, "side", "B", "tick", r.tick)));
    }

    private void tickAll() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            if (now - s.lastSeen > 12_000) {        // 12s timeout
                dropSession(s, "timeout");
            }
        }
        // rooms
        for (Room r : rooms.values()) {
            if (!r.started) continue;
            r.tick++;

            // consume inputs
            drainInputs(r.a, r);
            drainInputs(r.b, r);

            // toy simulation: move by vx,vy inside {0..100}
            r.xA = clamp(r.xA + r.vxA, 0, 100);
            r.yA = clamp(r.yA + r.vyA, 0, 100);
            r.xB = clamp(r.xB + r.vxB, 0, 100);
            r.yB = clamp(r.yB + r.vyB, 0, 100);

            // snapshot
            Map<String,Object> snap = Map.of(
                    "tick", r.tick,
                    "ackSeqA", r.a.lastSeq,
                    "ackSeqB", r.b.lastSeq,
                    "players", List.of(
                            Map.of("id","A","x",r.xA,"y",r.yA),
                            Map.of("id","B","x",r.xB,"y",r.yB)
                    )
            );
            send(r.a.out, Wire.of("SNAPSHOT", r.a.sid, snap));
            send(r.b.out, Wire.of("SNAPSHOT", r.b.sid, snap));
        }
    }

    private static void drainInputs(Session s, Room r) {
        Wire.Envelope e;
        while ((e = s.inputs.poll()) != null) {
            JsonNode d = e.data;
            int dx = d.path("dx").asInt(0);
            int dy = d.path("dy").asInt(0);
            if (s == r.a) { r.vxA = dx; r.vyA = dy; }
            else          { r.vxB = dx; r.vyB = dy; }
        }
    }

    private static void send(PrintWriter out, Envelope e) { out.print(Wire.encode(e)); out.flush(); }
    private static Envelope err(String code, String msg) {
        return Wire.of("ERROR", null, Map.of("code", code, "msg", msg));
    }
    private static int clamp(int v,int lo,int hi){ return Math.max(lo, Math.min(hi, v)); }

    /* ---------- inner models ---------- */
    private static final class Session {
        final String sid, token;
        final PrintWriter out;
        volatile long lastSeen = System.currentTimeMillis();
        final Queue<Envelope> inputs = new ArrayDeque<>();
        volatile long lastSeq = -1;
        volatile Room room;

        Session(String sid, String token, PrintWriter out) {
            this.sid = sid; this.token = token; this.out = out;
        }
    }
    private static final class Room {
        final String id; final Session a, b;
        volatile boolean started;
        volatile long tick;
        int xA=50,yA=50,vxA,vyA, xB=50,yB=50,vxB,vyB;
        Room(String id, Session a, Session b){ this.id=id; this.a=a; this.b=b; }
    }
    private void dropSession(Session s, String reason) {
        try { if (s.out != null) send(s.out, err("disconnect", reason)); } catch (Exception ignored) {}
        sessions.remove(s.sid);

        // remove from matchmaking queue
        matchmaking.remove(s);

        // clean room
        Room r = s.room;
        if (r != null) {
            r.started = false;
            if (r.a == s && r.b != null) send(r.b.out, err("opponent_left", reason));
            if (r.b == s && r.a != null) send(r.a.out, err("opponent_left", reason));
            rooms.remove(r.id);
        }
    }
}
