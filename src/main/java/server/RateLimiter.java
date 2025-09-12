// src/main/java/server/RateLimiter.java
package server;

final class RateLimiter {
    private final double ratePerSec;
    private final double burst;
    private double tokens;
    private long lastNanos;

    RateLimiter(double ratePerSec, double burst) {
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.tokens = burst;
        this.lastNanos = System.nanoTime();
    }

    /** Try consume 1 token. Returns true if allowed. */
    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double add = (now - lastNanos) / 1_000_000_000.0 * ratePerSec;
        if (add > 0) {
            tokens = Math.min(burst, tokens + add);
            lastNanos = now;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
