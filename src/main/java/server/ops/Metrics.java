package server.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class Metrics {
    private static final ObjectMapper M = new ObjectMapper();

    // ---- Counters (monotonic) ----
    public final AtomicLong sessionsOpened  = new AtomicLong();
    public final AtomicLong sessionsClosed  = new AtomicLong();
    public final AtomicLong matchesStarted  = new AtomicLong();
    public final AtomicLong matchesActive   = new AtomicLong();
    public final AtomicLong matchesEnded    = new AtomicLong();
    public final AtomicLong forfeitWins     = new AtomicLong();

    // tick timing (EWMA & last)
    private volatile double tickMsEwma = 0.0;
    private volatile double tickMsLast = 0.0;
    private static final double ALPHA = 0.2; // EWMA smoothing

    // live gauges (bound from GameServer)
    private ConcurrentMap<String, ?> sessionsRef;
    private ConcurrentMap<String, ?> roomsRef;
    private ConcurrentLinkedQueue<?> matchmakingRef;

    public void bind(ConcurrentMap<String, ?> sessions,
                     ConcurrentMap<String, ?> rooms,
                     ConcurrentLinkedQueue<?> mmq) {
        sessionsRef = sessions; roomsRef = rooms; matchmakingRef = mmq;
    }

    public void observeTickNanos(long nanos) {
        double ms = nanos / 1_000_000.0;
        tickMsLast = ms;
        tickMsEwma = (tickMsEwma == 0.0) ? ms : (ALPHA * ms + (1 - ALPHA) * tickMsEwma);
    }

    public ObjectNode snapshot() {
        ObjectNode n = M.createObjectNode();
        n.put("sessions_opened", sessionsOpened.get());
        n.put("sessions_closed", sessionsClosed.get());
        n.put("matches_started", matchesStarted.get());
        n.put("matches_active",  matchesActive.get());
        n.put("matches_ended",   matchesEnded.get());
        n.put("forfeit_wins",    forfeitWins.get());

        int sessions = (sessionsRef != null) ? sessionsRef.size() : -1;
        int rooms    = (roomsRef != null)    ? roomsRef.size()    : -1;
        int queued   = (matchmakingRef != null) ? matchmakingRef.size() : -1;

        n.put("gauge_sessions", sessions);
        n.put("gauge_rooms",    rooms);
        n.put("gauge_queue",    queued);

        n.put("tick_ms_last", tickMsLast);
        n.put("tick_ms_ewma", tickMsEwma);
        return n;
    }

    public String snapshotJson() {
        try { return M.writeValueAsString(snapshot()); }
        catch (Exception e) { return "{\"error\":\"metrics_json\"}"; }
    }
}
