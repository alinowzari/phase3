// src/main/java/tools/MatchSmoke.java
package tools;

import client.GameClient;
import common.NetSnapshotDTO;
import common.cmd.LaunchCmd;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Headless end-to-end smoke test: server + 2 clients + BUILD→ACTIVE. */
public final class MatchSmoke {

    public static void main(String[] args) throws Exception {
        final String host = "127.0.0.1";
        final int    port = 5555;
        final String level = (args.length > 0) ? args[0] : "Level 1";

        // 1) Start server in a background daemon thread
        Thread serverThread = new Thread(() -> {
            try { new server.GameServer(port).start(); }
            catch (Exception e) { e.printStackTrace(); }
        }, "GameServer");
        serverThread.setDaemon(true);
        serverThread.start();

        // Tiny delay to let the server bind its port
        Thread.sleep(300);

        // 2) Two clients
        GameClient a = new GameClient(host, port);
        GameClient b = new GameClient(host, port);
        a.setDesiredLevel(level);
        b.setDesiredLevel(level);

        // 3) Latches for START and ACTIVE
        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch activeLatch = new CountDownLatch(2);

        AtomicReference<String> aSide = new AtomicReference<>("?");
        AtomicReference<String> bSide = new AtomicReference<>("?");

        a.setStartHandler(side -> { aSide.set(side); startLatch.countDown(); });
        b.setStartHandler(side -> { bSide.set(side); startLatch.countDown(); });

        a.setSnapshotHandler((NetSnapshotDTO snap) -> {
            if ("ACTIVE".equalsIgnoreCase(snap.info().state().name())) {
                activeLatch.countDown();
            }
        });
        b.setSnapshotHandler((NetSnapshotDTO snap) -> {
            if ("ACTIVE".equalsIgnoreCase(snap.info().state().name())) {
                activeLatch.countDown();
            }
        });

        a.setErrorHandler(err -> System.err.println("[A ERR] " + err));
        b.setErrorHandler(err -> System.err.println("[B ERR] " + err));

        // 4) Connect both (they will auto JOIN_QUEUE { level } on HELLO_S)
        a.connect();
        b.connect();

        // 5) Wait for START from both
        if (!startLatch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Did not receive START from both clients in time.");
        }
        System.out.println("Both clients STARTed. A=" + aSide.get() + " B=" + bSide.get());

        // 6) Send LaunchCmd from both to flip BUILD → ACTIVE
        a.send(new LaunchCmd(a.nextSeq()));
        b.send(new LaunchCmd(b.nextSeq()));

        // 7) Wait for ACTIVE state to appear in snapshots
        if (!activeLatch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Did not reach ACTIVE within timeout.");
        }
        System.out.println("Room is ACTIVE. Smoke test PASS ✅");

        // 8) Close clients (server stays up as daemon thread; JVM exits after main)
        a.close();
        b.close();
    }
}
