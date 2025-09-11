// server/SimServer.java
package server;
import java.util.concurrent.*;
import model.SystemManager;

public final class SimServer {
    private final SystemManager sim;
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = false;

    public SimServer(SystemManager sim){ this.sim = sim; }

    public void start(){
        if (running) return;
        running = true;
        exec.scheduleAtFixedRate(() -> sim.update(1f/60f), 0, 16, TimeUnit.MILLISECONDS);
    }
    public void stop(){ exec.shutdownNow(); running = false; }
    public SystemManager sim(){ return sim; }
}

