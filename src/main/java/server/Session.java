package server;

import net.Wire.Envelope;

import java.io.PrintWriter;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

final class Session {
    final String sid, token;
    volatile PrintWriter out;               // rebind on RESUME
    final Object sendLock = new Object();   // per-session writer lock
    volatile long lastSeen = System.currentTimeMillis();

    final Queue<Envelope> inputs = new ConcurrentLinkedQueue<>();
    volatile long lastSeq = -1;

    volatile Room room;                     // set when matched
    volatile String levelName = "default";  // requested by client
    final RateLimiter cmdRate = new RateLimiter(120, 240); // 120/s, burst 240
    volatile long lastRateWarnMs = 0L;

    Session(String sid, String token, PrintWriter out) {
        this.sid = sid; this.token = token; this.out = out;
    }

    void rebindOut(PrintWriter newOut) {
        synchronized (sendLock) { this.out = newOut; }
    }
}
