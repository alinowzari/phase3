// server/Session.java
package server;

import net.Wire.Envelope;
import java.io.PrintWriter;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

final class Session {
    final String sid, token;
    volatile PrintWriter out;
    final Object sendLock = new Object();

    // ⬇⬇ NEW: writer plumbing
    final BlockingQueue<String> outQueue = new LinkedBlockingQueue<>(2048);
    final AtomicReference<String> latestSnapshot = new AtomicReference<>(null);
    volatile boolean writerRunning = false;
    Thread writerThread;

    volatile long lastSeen = System.currentTimeMillis();
    final Queue<Envelope> inputs = new ConcurrentLinkedQueue<>();
    volatile long lastSeq = -1;

    volatile Room room;
    volatile String levelName = "default";
    final RateLimiter cmdRate = new RateLimiter(120, 240);
    volatile long lastRateWarnMs = 0L;

    byte[] hmacKey = new byte[32];
    byte[] lastMac = new byte[32];
    java.util.Map<Long, byte[]> seen = new java.util.concurrent.ConcurrentHashMap<>();

    Session(String sid, String token, PrintWriter out) {
        this.sid = sid; this.token = token; this.out = out;
    }

    void rebindOut(PrintWriter newOut) { synchronized (sendLock) { this.out = newOut; } }

    // ⬇⬇ NEW
    void startWriterLoop() {
        if (writerRunning) return;
        writerRunning = true;
        writerThread = new Thread(this::writerRun, "Writer-" + sid);
        writerThread.setDaemon(true);
        writerThread.start();
        System.out.println("[WRITER] started for sid=" + sid);
    }
    void stopWriterLoop() {
        writerRunning = false;
        if (writerThread != null) writerThread.interrupt();
    }
    void offerSnapshot(String encodedLine) { latestSnapshot.set(encodedLine); }
    void offerPriority(String encodedLine) {
        if (!outQueue.offer(encodedLine)) { outQueue.poll(); outQueue.offer(encodedLine); }
    }
    private void writerRun() {
        final long FLUSH_INTERVAL_MS = 33; // ~30Hz
        long lastFlush = System.currentTimeMillis();
        try {
            while (writerRunning) {
                String first = outQueue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                PrintWriter pw = this.out;
                if (pw == null) continue;

                boolean wrote = false;
                if (first != null) {
                    pw.print(first); wrote = true;
                    for (int i = 0; i < 1024; i++) {
                        String m = outQueue.poll();
                        if (m == null) break;
                        pw.print(m); wrote = true;
                    }
                }
                String snap = latestSnapshot.getAndSet(null);
                if (snap != null) { pw.print(snap); wrote = true; }

                long now = System.currentTimeMillis();
                if (wrote || (now - lastFlush) >= FLUSH_INTERVAL_MS) {
                    pw.flush();
                    lastFlush = now;
                }
            }
        } catch (InterruptedException ignore) {
            // exit
        } catch (Throwable t) {
            System.err.println("[WRITER " + sid + "] error: " + t);
        }
    }
}
