// src/main/java/client/ClientApp.java
package client;

import common.dto.NetSnapshotDTO;

public final class ClientApp {
    public static void main(String[] args) throws Exception {
        String host  = args.length > 0 ? args[0] : "127.0.0.1";
        int    port  = args.length > 1 ? Integer.parseInt(args[1]) : 5555;
        String level = args.length > 2 ? args[2] : "default";

        GameClient c = new GameClient(host, port);
        c.setDesiredLevel(level);
        c.setSnapshotHandler((NetSnapshotDTO s) -> System.out.println("SNAP " + s));
        c.setStartHandler(side -> System.out.println("START side=" + side));
        c.setErrorHandler(err -> System.err.println("ERR " + err));

        // choose one style:
        // A) library style
        c.connect();
        Thread.currentThread().join();

        // B) thread style (alternative):
        // new Thread(c, "GameClient").start();
        // Thread.currentThread().join();
    }
}
