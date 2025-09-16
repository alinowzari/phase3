package client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import common.dto.NetSnapshotDTO;
import common.dto.cmd.ClientCommand;
import common.dto.util.Bytes;
import common.dto.util.Canon;
import common.dto.util.Hex;
import common.dto.util.Hmac;
import net.Wire;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static common.dto.util.Canon.M;

/** Thin network client for the multiplayer game. */
public final class GameClient implements Runnable {

    // persistent command journal
    private client.storage.Journal journal;
    private java.nio.file.Path journalDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".phase3", "journal");


    // ---- connection ----
    private final String host;
    private final int    port;

    private Socket          socket;
    private BufferedReader  in;
    private PrintWriter     out;
    private volatile boolean closing = false;

    // ---- session ----
    private volatile String sid    = null;
    private volatile String mySide = "?";

    // level the UI (controller) asked to play
    private volatile String desiredLevel = "";
    // ---- schedulers ----
    private ScheduledExecutorService heartbeatExec;
    private ExecutorService          readExec;
    private ExecutorService          consoleExec;

    // ---- callbacks (UI can subscribe) ----
    private volatile Consumer<NetSnapshotDTO> onSnapshot = snap -> {};
    private volatile Consumer<String>         onStart    = side -> {};
    private volatile Consumer<String>         onError    = msg  -> {};
    private volatile Consumer<String>         onOpponentLeft = msg -> {};
    private volatile Consumer<String>         onLog      = msg  -> {};
    // ---- client-side seq generator for commands (optional) ----
    private final AtomicLong seq = new AtomicLong(-1);
    private volatile byte[] keyK    = new byte[0];   // HMAC key from HELLO_S.reconnectToken
    private volatile long   lastSeq = -1L;           // match server baseline (-1)
    private volatile byte[] lastMac = new byte[32];  // 32 zero bytes initial prevMac

    // mac used for each sent seq (so we can advance our chain on ACK)
    private final ConcurrentHashMap<Long, byte[]> sentMacBySeq = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ClientCommand> backlog = new ConcurrentLinkedQueue<>();
    private final Set<Long> inflight = ConcurrentHashMap.newKeySet();
    private volatile String resumeToken = null;      // token from the last good session
    private volatile boolean wantResume = false;     // set true on disconnects


    private volatile boolean online = false;        // connection health
    private volatile String  phase  = "UNKNOWN";    // BUILD / ACTIVE
    private final Object replayLock = new Object();
    private volatile boolean replaying = false;



    public GameClient(String host, int port) {
        this.host = host;
        this.port = port;
    }
    @Override public void run() {
        try {
            connect();
            online = true;
        } catch (IOException e) {
            onError.accept("Connect failed: " + e.getMessage());
        }
    }

    /* ==========================================================
       Public API for controllers
       ========================================================== */

    /** Set the level that will be sent in JOIN_QUEUE as soon as HELLO_S arrives. */
    public void setDesiredLevel(String levelName) {
        this.desiredLevel = (levelName == null) ? "" : levelName.trim();
        // If we already know sid, join immediately.
        if (sid != null) joinQueueLevel(this.desiredLevel);
    }
    /** Open the socket, start read loop. Non-blocking. */
    public synchronized void connect() throws IOException {
        // 1) Guard double-connect
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            log("connect(): already connected");
            return;
        }

        // 2) We are (re)connecting — do NOT reset lastSeq/lastMac/resumeToken here.
        closing = false;

        // 3) Bring up the socket
        socket = new java.net.Socket();
        socket.setTcpNoDelay(true);                 // optional, lowers input lag
        socket.connect(new java.net.InetSocketAddress(host, port), 3000);

        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        // 4) (Optional – Step 9.3) open journal once per process
        //    If you skipped 9.3, you can delete this block.
        if (journal == null) {
            try {
                journal = new client.storage.Journal(journalDir);
                journal.open();
            } catch (Exception ex) {
                onError.accept("Journal open failed: " + ex.getMessage());
            }
        }

        // 5) Start/restore the reader thread
        if (readExec == null || readExec.isShutdown()) {
            readExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ClientRead");
                t.setDaemon(true);
                return t;
            });
        }
        readExec.execute(this::readLoop);
    }

    /** Close socket and stop background tasks. */
    public synchronized void close() {
        closing = true;
        // Closing the socket unblocks readLoop() without NPEs
        try {
            if (socket != null) socket.close();
        } catch (Exception ignore) {
        }
        stop(heartbeatExec);
        // Let the reader thread exit; then we can clear streams
        if (readExec != null) {
            readExec.shutdownNow();
            try {
                readExec.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignore) {
            }
        }
        stop(consoleExec);
        heartbeatExec = null;
        readExec = null;
        consoleExec = null;

        try {
            if (in != null) in.close();
        }
        catch (Exception ignore) {}
        try {
            if (out != null) out.close();
        }
        catch (Exception ignore) {}
        try {
            if (journal != null) journal.close();
        }
        catch (Exception ignore) {}
        journal = null;
        in = null;
        out = null;
        socket = null;
    }

    /** Send a typed command (used by ConnectionController / OnlineGameController). */
    public void send(ClientCommand cmd) {
        if (!isOpen()) {
            // If you kept the offline backlog from step 8, use it instead of dropping:
            // backlog.add(cmd); return;
            onError.accept("Offline: dropping command " + cmd.getClass().getSimpleName());
            return;
        }

        long seqNum = nextSeq();
        setSeqIfPresent(cmd, seqNum);
        // Build command node and ensure discriminator is present
        ObjectNode cmdNode = Canon.M.valueToTree(cmd);
// Always set canonical type name expected by the server decoder:
        cmdNode.put("type", cmd.getClass().getSimpleName());

        // mac = HMAC(k, lastMac || le64(seq) || canon(cmdNode))
        byte[] body = Canon.bytes(cmdNode);
        byte[] msg  = Bytes.concat(lastMac, Bytes.le64(seqNum), body);
        byte[] mac  = (keyK.length == 0) ? new byte[32] : Hmac.sha256(keyK, msg);

        // Track the mac we used for this seq (advanced on CMD_ACK)
        sentMacBySeq.put(seqNum, mac);

        // (Optional: 9.3) persist to journal for crash-safe replay
        try {
            if (journal != null) {
                client.storage.Journal.Entry je =
                        new client.storage.Journal.Entry(seqNum, Hex.encode(mac), cmdNode);
                journal.append(je);
            }
        } catch (Exception jex) {
            onLog.accept("Journal append warn: " + jex.getMessage());
        }

        // Wrap into COMMAND envelope
        ObjectNode data = Canon.M.createObjectNode();
        data.put("seq", seqNum);
        data.put("mac", Hex.encode(mac));
        data.set("cmd", cmdNode);

        send(net.Wire.of("COMMAND", sid, data));
    }


    private long extractSeq(ClientCommand cmd) {
        try {
            var f = cmd.getClass().getDeclaredField("seq"); // your commands use 'long seq' (e.g., LaunchCmd(long seq))
            f.setAccessible(true);
            Object v = f.get(cmd);
            return (v instanceof Number) ? ((Number) v).longValue() : -1L;
        } catch (Exception ignore) { return -1L; }
    }

    /** Convenience sequence generator (if the controller wants one). */
    public long nextSeq() { return seq.incrementAndGet(); }

    /** Subscribe to server snapshots. */
    public void setSnapshotHandler(Consumer<NetSnapshotDTO> cb) {
        this.onSnapshot = (cb != null) ? cb : (s) -> {};
    }
    public void setOpponentLeftHandler(Consumer<String> cb) {
        this.onOpponentLeft = (cb != null) ? cb : (s) -> {};
    }
    /** Subscribe to START (side assignment). */
    public void setStartHandler(Consumer<String> cb) {
        this.onStart = (cb != null) ? cb : (s) -> {};
    }

    /** Subscribe to error strings. */
    public void setErrorHandler(Consumer<String> cb) {
        this.onError = (cb != null) ? cb : (s) -> {};
    }

    /** Optional console controls (WASD in terminal) for quick tests. */
    public void enableConsoleControls() {
        if (consoleExec != null) return;
        consoleExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ClientConsole");
            t.setDaemon(true);
            return t;
        });
        consoleExec.execute(this::consoleLoop);
    }

    /** Explicit join with a given level (safe to call anytime). */
    public void joinQueueLevel(String level) {
        String lvl = (level == null) ? "" : level.trim();
        if (sid != null) {
            send(Wire.of("JOIN_QUEUE", sid, Map.of("level", lvl)));
        } else {
            // If sid unknown yet, remember it; HELLO_S handler will send it.
            this.desiredLevel = lvl;
        }
    }

    /* ==========================================================
       Internal I/O
       ========================================================== */

    private void readLoop() {
        try {
            final BufferedReader reader = in; // avoid races with close() nulling 'in'
            String line;
            while (!closing && reader != null && (line = reader.readLine()) != null) {
                Wire.Envelope env = Wire.decode(line);
                switch (env.t) {
                    case "HELLO_S" -> {
                        this.sid = env.sid;
                        log("[HELLO_S] sid=" + sid + " data=" + env.data);

                        // Read values from server HELLO
                        String hmacB64  = (env.data != null) ? env.data.path("hmacKey").asText("")        : "";
                        String tokenStr = (env.data != null) ? env.data.path("reconnectToken").asText("") : "";

                        // Prefer explicit hmacKey; else derive from token (UUID is not Base64!)
                        try {
                            if (!hmacB64.isEmpty()) {
                                keyK = java.util.Base64.getDecoder().decode(hmacB64);
                            } else if (!tokenStr.isEmpty()) {
                                java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
                                keyK = sha.digest(tokenStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            } else {
                                keyK = new byte[0]; // integrity disabled (dev)
                            }
                        } catch (Exception e) {
                            onLog.accept("[HELLO_S] key init warning: " + e.getMessage());
                            keyK = new byte[0];
                        }

                        // If we have a previous live session, attempt RESUME to that session
                        if (wantResume && resumeToken != null && !resumeToken.isBlank()) {
                            sendResume(resumeToken, lastSeq, lastMac);   // <— uses prior chain
                            // Do NOT reset seq/lastMac here; wait for RESUMED response
                        } else {
                            // Fresh session: remember this token for future resume
                            resumeToken = tokenStr;
                            lastSeq = -1L; lastMac = new byte[32];       // align to server baseline
                            startHeartbeat();
                            joinQueueLevel(desiredLevel);
                        }
                    }
                    case "START" -> {
                        this.mySide = env.data.path("side").asText("?");
                        String lvl = env.data.path("level").asText("?");
                        phase = env.data.path("state").asText("BUILD");
                        log("[START] side=" + mySide + " level=" + lvl + " state=" + phase);
                        onStart.accept(mySide);
                        // If we're in BUILD, kick a replay
                        if ("BUILD".equalsIgnoreCase(phase)) {
                            replayBacklogAsync();
                        }
                    }
                    case "SNAPSHOT" -> {
                        NetSnapshotDTO dto = Wire.read(env.data, NetSnapshotDTO.class);
                        phase = dto.info().state().name(); // keep phase updated
                        onSnapshot.accept(dto);
                        if ("BUILD".equalsIgnoreCase(phase)){
                            replayBacklogAsync();
                        }
                    }
                    case "CMD_ACK" -> {
                        long seqAck = (env.data != null) ? env.data.path("seq").asLong(-1) : -1;
                        boolean accepted = env.data != null && env.data.path("accepted").asBoolean(false);
                        boolean dup      = env.data != null && env.data.path("dup").asBoolean(false);

                        if (seqAck >= 0) {
                            byte[] mac = sentMacBySeq.remove(seqAck);
                            // Any handled ACK (accepted true/false) should advance the rolling state
                            if (mac != null && seqAck == lastSeq + 1 && (accepted || !dup)) {
                                lastSeq = seqAck;
                                lastMac = mac;
                                try { if (journal != null) journal.compact(lastSeq); } catch (Exception ignore) {}
                            }
                            // if dup==true, server already processed it earlier; we usually won’t see this
                        }
                    }
                    case "RESUMED" -> {
                        // Server confirms which session we’re bound to and its authoritative chain tip
                        long serverLastSeq = (env.data != null) ? env.data.path("serverLastSeq").asLong(-1) : -1;
                        String lastMacHex  = (env.data != null) ? env.data.path("serverLastMac").asText("") : "";
                        String boundSid    = (env.data != null) ? env.data.path("sid").asText(sid)          : sid;
                        String tokenStr    = (env.data != null) ? env.data.path("reconnectToken").asText(""): "";

                        // Adopt server’s authoritative chain
                        this.sid = boundSid;
                        resumeToken = tokenStr.isBlank() ? resumeToken : tokenStr;

                        if (!lastMacHex.isEmpty()) {
                            try { lastMac = Hex.decode(lastMacHex); }
                            catch (Exception ignore) {}
                        }
                        lastSeq = serverLastSeq;
                        // make nextSeq() produce lastSeq+1
                        while (this.seq.get() < serverLastSeq) this.seq.set(serverLastSeq);

                        wantResume = false;
                        startHeartbeat();

                        // Room START + SNAPSHOT will arrive from server’s RESUME path; do not auto-join here.
                        onLog.accept("[RESUMED] boundSid=" + this.sid + " lastSeq=" + lastSeq);
                    }

                    case "ERROR" -> {
                        String code = env.data != null ? env.data.path("code").asText("") : "";
                        String msg  = env.data != null ? env.data.path("msg").asText("")  : "unknown";
                        if ("opponent_left".equals(code)) {
                            onOpponentLeft.accept(msg);
                        } else {
                            onError.accept(env.data != null ? env.data.toString() : "unknown");
                        }
                    }
                    default -> log("[MSG " + env.t + "] " + env.data);
                }
            }
        } catch (IOException ioe) {
            if (!closing) {
                onError.accept("Disconnected: " + ioe.getMessage());
                wantResume = true;              // <— ask to resume on next connect
            }
        } catch (Exception ex) {
            if (!closing) {
                onError.accept("Client error: " + ex.getMessage());
                wantResume = true;              // <— also resume after unexpected error
            }
        }
        finally {
            if (!closing) {
                close();
            }
        }
    }

    private void startHeartbeat() {
        stop(heartbeatExec);
        heartbeatExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClientHB");
            t.setDaemon(true);
            return t;
        });
        heartbeatExec.scheduleAtFixedRate(
                () -> send(Wire.of("PING", sid, Map.of("ts", System.currentTimeMillis()))),
                1, 2, TimeUnit.SECONDS
        );
    }

    // Single place that actually writes to the socket
    private synchronized void send(Wire.Envelope e) {
        try {
            if (!closing && out != null) {
                out.print(Wire.encode(e)); // adds '\n'
                out.flush();
            }
        }
        catch (Exception ex) {
            if(!closing) {
                onError.accept("Send failed: " + ex.getMessage());
            }
        }
    }

    private void stop(ExecutorService ex) {
        if (ex != null) ex.shutdownNow();
    }
    private void replayBacklogAsync() {
        if (!isOpen() || !"BUILD".equalsIgnoreCase(phase)) return;
        synchronized (replayLock) {
            if (replaying) return;
            replaying = true;
        }
        // Use readExec to serialize with the reader context
        readExec.execute(() -> {
            try {
                ClientCommand cmd;
                while (isOpen() && "BUILD".equalsIgnoreCase(phase) && (cmd = backlog.poll()) != null) {
                    // IMPORTANT: go through the MAC-packing path
                    send(cmd);
                    // tiny pacing to avoid flooding
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                }
            } catch (Exception ex) {
                if (!closing) onError.accept("Replay failed: " + ex.getMessage());
            } finally {
                replaying = false;
            }
        });
    }


    private void sendResume(String token, long lastSeqClient, byte[] lastMacClient) {
        try {
            var data = Canon.M.createObjectNode();
            data.put("token", token);
            data.put("lastSeq", lastSeqClient);
            data.put("lastMac", Hex.encode(lastMacClient));
            send(net.Wire.of("RESUME", sid, data));
            onLog.accept("[RESUME] token=" + token + " lastSeq=" + lastSeqClient);
        } catch (Exception e) {
            onError.accept("Resume send failed: " + e.getMessage());
        }
    }
    /* ==========================================================
       Optional console WASD test loop (kept from your original)
       ========================================================== */
    private void consoleLoop() {
        System.out.println("Console controls: w/a/s/d then Enter, 'stop' to zero, 'q' to quit");
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                String l = sc.nextLine();
                if ("q".equalsIgnoreCase(l)) {
                    send(Wire.of("BYE", sid, Map.of()));
                    break;
                }
                int dx = 0, dy = 0;
                if (l.contains("w")) dy = -1;
                if (l.contains("s")) dy = +1;
                if (l.contains("a")) dx = -1;
                if (l.contains("d")) dx = +1;
                if ("stop".equalsIgnoreCase(l)) { dx = 0; dy = 0; }

                // Kept for your toy protocol tests; safe to remove in the real flow.
                send(Wire.of("INPUT", sid, Map.of("seq", nextSeq(), "dx", dx, "dy", dy)));
            }
        } catch (Exception ignore) { }
    }
    private void setSeqIfPresent(ClientCommand cmd, long seqNum) {
        try {
            var f = cmd.getClass().getDeclaredField("seq");
            f.setAccessible(true);
            f.setLong(cmd, seqNum);
        } catch (NoSuchFieldException ignore) {
            // command type doesn't carry its own seq → nothing to do
        } catch (Exception ex) {
            onLog.accept("setSeq warn: " + ex.getMessage());
        }
    }
    private void log(String s) { onLog.accept(s); }
    public  void setLogHandler(Consumer<String> cb) { this.onLog = (cb != null) ? cb : (x)->{}; }

    // Accessors you might want
    public String  getSid()  { return sid; }
    public String  getSide() { return mySide; }
    public boolean isOpen()  { return socket != null && socket.isConnected() && !socket.isClosed(); }
    public void useAbility(common.dto.AbilityType a,
                           int fromSys, int fromOut,
                           int toSys,   int toIn,
                           common.dto.PointDTO at) {
        send(new common.dto.cmd.UseAbilityCmd(nextSeq(), a, fromSys, fromOut, toSys, toIn, at));
    }
}
