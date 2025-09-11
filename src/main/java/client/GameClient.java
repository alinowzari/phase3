package client;

import net.Wire;

import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;

public final class GameClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int    port = args.length > 1 ? Integer.parseInt(args[1]) : 5555;
        new GameClient(host, port).start();
    }

    private final String host;
    private final int port;

    // ---- session / wire ----
    private volatile String sid = null;
    private volatile String mySide = "?";
    private volatile PrintWriter writer = null;

    // ---- input state (shared with scheduler) ----
    private final Object keyLock = new Object();
    private int  keyDx = 0, keyDy = 0;
    private long seq   = 0;

    // ---- background executors ----
    private ScheduledExecutorService heartbeatExec;
    private ScheduledExecutorService inputExec;
    private ExecutorService keyboardExec;

    public GameClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() throws Exception {
        try (Socket s = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {

            this.writer = out;

            // stdin updater (so you can type w/a/s/d once and it keeps sending that vector)
            keyboardExec = Executors.newSingleThreadExecutor();
            keyboardExec.execute(this::keyboardLoop);

            // read loop (blocking on this thread)
            String line;
            while ((line = in.readLine()) != null) {
                Wire.Envelope e = Wire.decode(line);
                switch (e.t) {
                    case "HELLO_S" -> {
                        this.sid = e.sid;
                        System.out.println("[HELLO_S] sid=" + sid + " data=" + e.data);
                        startHeartbeat();
                        startInputSender();
                        // join queue right away (no timing races)
                        send(Wire.of("JOIN_QUEUE", sid, Map.of("mode", "1v1")));
                    }
                    case "START" -> {
                        this.mySide = e.data.path("side").asText("?");
                        System.out.println("[START] you are side=" + mySide + " data=" + e.data);
                        // (optional) auto-move so you can see both players changing without typing:
                        // if ("A".equals(mySide)) setDirection(+1, 0); else setDirection(0, +1);
                    }
                    case "SNAPSHOT" -> {
                        System.out.println("SNAP " + e.data);
                    }
                    case "PONG" -> { /* ok */ }
                    case "ERROR" -> System.err.println("[ERR] " + e.data);
                    default -> System.out.println("[MSG " + e.t + "] " + e.data);
                }
            }
        } finally {
            stopExecutors();
        }
    }

    /* ================= helpers ================= */

    private void startHeartbeat() {
        stopIfNotNull(heartbeatExec);
        heartbeatExec = Executors.newSingleThreadScheduledExecutor();
        heartbeatExec.scheduleAtFixedRate(() ->
                        send(Wire.of("PING", sid, Map.of("ts", System.currentTimeMillis()))),
                1, 2, TimeUnit.SECONDS
        );
    }

    private void startInputSender() {
        stopIfNotNull(inputExec);
        inputExec = Executors.newSingleThreadScheduledExecutor();
        inputExec.scheduleAtFixedRate(() -> {
            int dx, dy;
            synchronized (keyLock) { dx = keyDx; dy = keyDy; }
            send(Wire.of("INPUT", sid, Map.of("seq", ++seq, "dx", dx, "dy", dy)));
        }, 0, 33, TimeUnit.MILLISECONDS); // ~30 Hz
    }

    private void keyboardLoop() {
        Scanner sc = new Scanner(System.in);
        System.out.println("Controls: w/a/s/d + Enter, 'stop' to zero, 'q' to quit");
        try {
            while (true) {
                String l = sc.nextLine();
                if ("q".equals(l)) {
                    send(Wire.of("BYE", sid, Map.of()));
                    break;
                }
                int dx = 0, dy = 0;
                if (l.contains("w")) dy = -1;
                if (l.contains("s")) dy = +1;
                if (l.contains("a")) dx = -1;
                if (l.contains("d")) dx = +1;
                if ("stop".equals(l)) { dx = 0; dy = 0; }
                setDirection(dx, dy);
            }
        } catch (Exception ignore) { }
    }

    private void setDirection(int dx, int dy) {
        synchronized (keyLock) { keyDx = dx; keyDy = dy; }
    }

    private synchronized void send(Wire.Envelope e) {
        try {
            if (writer != null) {
                writer.print(Wire.encode(e)); // NDJSON (adds '\n')
                writer.flush();
            }
        } catch (Exception ex) {
            System.err.println("[SEND ERR] " + ex.getMessage());
        }
    }

    private void stopExecutors() {
        stopIfNotNull(heartbeatExec);
        stopIfNotNull(inputExec);
        stopIfNotNull(keyboardExec);
    }

    private static void stopIfNotNull(ExecutorService ex) {
        if (ex != null) {
            ex.shutdownNow();
        }
    }
}
