package client.net;

import common.cmd.ClientCommand;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class CommandBacklog {
    private final Queue<ClientCommand> q = new ConcurrentLinkedQueue<>();
    private volatile boolean replaying = false;

    public void add(ClientCommand c) { if (c != null) q.add(c); }

    public boolean tryStartReplay() {
        if (replaying) return false;
        replaying = true;
        return true;
    }

    public void endReplay() { replaying = false; }

    public ClientCommand poll() { return q.poll(); }

    public boolean isEmpty() { return q.isEmpty(); }
}
