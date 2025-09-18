package client.net;

import net.Wire;

import java.util.Map;
import java.util.concurrent.*;

public final class HeartbeatService implements AutoCloseable {
    private ScheduledExecutorService exec;

    public void start(Runnable ping) {
        stop();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClientHB"); t.setDaemon(true); return t;
        });
        exec.scheduleAtFixedRate(ping, 1, 2, TimeUnit.SECONDS);
    }

    public void stop() {
        if (exec != null) { exec.shutdownNow(); exec = null; }
    }

    @Override public void close() { stop(); }
}
