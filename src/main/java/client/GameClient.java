package client;

import common.dto.NetSnapshotDTO;
import common.dto.cmd.ClientCommand;
import net.Wire;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thin network client for the multiplayer game. */
public final class GameClient implements Runnable {

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
    private final AtomicLong seq = new AtomicLong();

    public GameClient(String host, int port) {
        this.host = host;
        this.port = port;
    }
    @Override public void run() {
        try {
            connect(); // uses ctor host/port
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
        if (socket != null && socket.isConnected()) return;

        socket = new Socket(host, port);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        // dedicated reader
        readExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ClientRead");
            t.setDaemon(true);
            return t;
        });
        readExec.execute(this::readLoop);
        // heartbeat starts after HELLO_S (when sid is known)
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
        } catch (Exception ignore) {
        }
        try {
            if (out != null) out.close();
        } catch (Exception ignore) {
        }
        in = null;
        out = null;
        socket = null;
    }

    /** Send a typed command (used by ConnectionController / OnlineGameController). */
    public void send(ClientCommand cmd) {
        send(Wire.of("COMMAND", sid, cmd));
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
                        startHeartbeat();
                        // auto-join with requested level (what controller set)
                        joinQueueLevel(desiredLevel);
                    }
                    case "START" -> {
                        this.mySide = env.data.path("side").asText("?");
                        log("[START] side=" + mySide + " level=" + env.data.path("level").asText("?"));
                        onStart.accept(mySide);
                    }
                    case "SNAPSHOT" -> {
                        NetSnapshotDTO dto = Wire.read(env.data, NetSnapshotDTO.class);
                        onSnapshot.accept(dto);
                    }
                    case "PONG" -> { /* ok */ }
                    case "ERROR" -> {
                        String code = env.data != null ? env.data.path("code").asText("") : "";
                        String msg = env.data != null ? env.data.path("msg").asText("") : "unknown";
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
            }
        } catch (Exception ex) {
            if (!closing) {
                onError.accept("Client error: " + ex.getMessage());
            }
        } finally {
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
        } catch (Exception ex) {
            if(!closing) {
                onError.accept("Send failed: " + ex.getMessage());
            }
        }
    }

    private void stop(ExecutorService ex) {
        if (ex != null) ex.shutdownNow();
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

    private void log(String s) { onLog.accept(s); }
    public  void setLogHandler(Consumer<String> cb) { this.onLog = (cb != null) ? cb : (x)->{}; }

    // Accessors you might want
    public String  getSid()  { return sid; }
    public String  getSide() { return mySide; }
    public boolean isOpen()  { return socket != null && socket.isConnected() && !socket.isClosed(); }
}
