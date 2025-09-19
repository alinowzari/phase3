package client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import common.AbilityType;
import common.NetSnapshotDTO;
import common.PointDTO;
import common.cmd.ClientCommand;
import common.cmd.UseAbilityCmd;
import common.util.Hex;
import client.net.*;

import net.Wire;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Facade that wires transport + dispatcher + mac chain + journal + heartbeat + backlog. */
public final class GameClient implements Closeable, MessageDispatcher.Runtime {

    // ---- storage (persisted command journal) ----
    private client.storage.Journal journal;
    private Path journalDir = java.nio.file.Paths.get(System.getProperty("user.home"), ".phase3", "journal");

    //snapshot ordering
    private volatile long lastTick = -1;
    // ---- pluggable subsystems ----
    private final ClientTransport transport;
    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private final MacChain mac = new MacChain();
    private final CommandBacklog backlog = new CommandBacklog();
    private final HeartbeatService hb = new HeartbeatService();

    // ---- session/state ----
    private volatile String sid = null;
    private volatile String mySide = "?";
    private volatile String phase = "UNKNOWN";    // BUILD / ACTIVE
    private volatile String desiredLevel = "";
    private volatile String resumeToken = null;
    private volatile boolean wantResume = false;
    public volatile boolean inMatch = false;

    // ---- callbacks ----
    private volatile Consumer<NetSnapshotDTO> onSnapshot = snap -> {};
    private volatile Consumer<String>         onStart    = side -> {};
    private volatile Consumer<String>         onError    = msg  -> {};
    private volatile Consumer<String>         onOpponentLeft = msg -> {};
    private volatile Consumer<String>         onLog      = msg  -> {};

    // ---- worker for console/replay ----
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ClientWorker"); t.setDaemon(true); return t;
    });

    public GameClient(String host, int port) {
        this.transport = new ClientTransport(host, port);
    }

    /* =========================================
       Public API (kept stable for ClientApp)
       ========================================= */
    public synchronized void connect() throws IOException {
        if (transport.isOpen()) { log("connect(): already connected"); return; }

        if (journal == null) { // open once per process
            try {
                journal = new client.storage.Journal(journalDir);
                journal.open();
            } catch (Exception ex) {
                onError.accept("Journal open failed: " + ex.getMessage());
            }
        }

        transport.connect(
                env -> dispatcher.handle(env, this, e -> transport.send(e, onError)),
                err -> { onError.accept(err); wantResume = true; }
        );
    }

    @Override public synchronized void close() throws IOException {
        hb.close();
        transport.close();
        worker.shutdownNow();
        try { if (journal != null) journal.close(); } catch (Exception ignore) {}
        journal = null;
    }

    /** Keep this for callers that need a pre-made sequence. */
    public long nextSeq() { return mac.nextSeq(); }

    public void setDesiredLevel(String levelName) {
        this.desiredLevel = (levelName == null) ? "" : levelName.trim();
        if (sid != null) joinQueueLevel(this.desiredLevel);
    }
    @Override
    public void joinQueueLevel(String level) {
        if (inMatch) {                       // NEW: prevent requeue during a match
            onLog.accept("[JOIN_QUEUE] ignored: already in a match");
            return;
        }
        String lvl = (level == null || level.isBlank()) ? desiredLevel : level.trim();
        if (sid != null) {
            sendEnvelope(net.Wire.of("JOIN_QUEUE", sid, java.util.Map.of("level", lvl)));
        } else {
            this.desiredLevel = lvl;
        }
    }

    public void send(ClientCommand cmd) {
        if (!transport.isOpen()) {
            onError.accept("Offline: dropping command " + cmd.getClass().getSimpleName());
            return;
        }

        // 1) Sign and wrap
        MacChain.MacResult signed = mac.signCommand(cmd);

        // 2) Journal (best-effort)
        try {
            if (journal != null) {
                journal.append(new client.storage.Journal.Entry(
                        signed.seq(), Hex.encode(signed.mac()), (ObjectNode) signed.data().get("cmd")));
            }
        } catch (Exception jex) {
            onLog.accept("Journal append warn: " + jex.getMessage());
        }

        // 3) Send COMMAND envelope
        sendEnvelope(Wire.of("COMMAND", sid, signed.data()));
    }

    public void useAbility(AbilityType a, int fromSys, int fromOut, int toSys, int toIn, PointDTO at) {
        send(new UseAbilityCmd(mac.nextSeq(), a, fromSys, fromOut, toSys, toIn, at));
    }

    public void enableConsoleControls() {
        worker.execute(this::consoleLoop);
    }

    /* =========================================
       MessageDispatcher.Runtime hooks
       ========================================= */
    @Override public void log(String s) { onLog.accept(s); }
    @Override public void error(String s) { onError.accept(s); }
    @Override public void opponentLeft(String msg) { onOpponentLeft.accept(msg); }
    @Override public void resetSnapshotOrdering() { lastTick = -1; }
    @Override public void setSid(String sid) { this.sid = sid; }
    @Override public String sid() { return sid; }
    @Override public void setPhase(String phase) { this.phase = phase; }
    @Override public void setMySide(String side) { this.mySide = side; }

    @Override public void saveResumeToken(String token) { this.resumeToken = token; }
    @Override public String resumeToken() { return resumeToken; }

    @Override public void initHmacKey(byte[] key) { mac.initKey(key); }
    @Override public void adoptChain(long lastSeq, byte[] lastMac) { mac.adopt(lastSeq, lastMac); }
    @Override public void resetBaseline() { mac.resetBaseline(); }
    @Override public long lastSeq() { return mac.lastSeq(); }
    @Override public byte[] lastMac() { return mac.lastMac(); }

    // compactJournal(...) here also handles the MacChain ACK.
    @Override public void compactJournal(long ackSeq) {
        mac.ack(ackSeq, true, false);
        try { if (journal != null) journal.compact(ackSeq); } catch (Exception ignore) {}
    }

    @Override public void startHeartbeat() {
        hb.start(() -> sendEnvelope(Wire.of("PING", sid, Map.of("ts", System.currentTimeMillis()))));
    }

    @Override
    public void onSnapshot(NetSnapshotDTO dto) {
        if (dto == null || dto.info() == null) return;

        // Allow snapshots until we know our side; afterwards, enforce side match.
        String snapSide = dto.info().side();
        if (mySide != null && !"?".equals(mySide) && snapSide != null
                && !snapSide.equalsIgnoreCase(mySide)) {
            onLog.accept("[SNAPSHOT] wrong side: snap=" + snapSide + " mine=" + mySide);
            return;
        }

        long t = dto.info().tick();
        if (t >= 0 && t <= lastTick) {
            onLog.accept("[SNAPSHOT] drop stale/dup tick=" + t + " last=" + lastTick);
            return;
        }
        lastTick = t;

        this.phase = dto.info().state().name();
        onSnapshot.accept(dto);
        if ("BUILD".equalsIgnoreCase(phase)) replayBacklogAsync();
    }


    @Override public void onStart(String side) {
        this.inMatch=true;
        this.onStart.accept(side);
        this.lastTick = -1;
        if ("BUILD".equalsIgnoreCase(phase)) replayBacklogAsync();
    }

    @Override public boolean wantResume() { return wantResume; }

    /* =========================================
       internals
       ========================================= */
    private void sendEnvelope(Wire.Envelope e) { transport.send(e, onError); }

    private void replayBacklogAsync() {
        if (!transport.isOpen() || !"BUILD".equalsIgnoreCase(phase)) return;
        if (!backlog.tryStartReplay()) return;
        worker.execute(() -> {
            try {
                ClientCommand cmd;
                while (transport.isOpen()
                        && "BUILD".equalsIgnoreCase(phase)
                        && (cmd = backlog.poll()) != null) {
                    send(cmd);
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                }
            } finally { backlog.endReplay(); }
        });
    }

    private void consoleLoop() {
        System.out.println("Console controls: w/a/s/d then Enter, 'stop' to zero, 'q' to quit");
        try (java.util.Scanner sc = new java.util.Scanner(System.in, StandardCharsets.UTF_8)) {
            while (true) {
                String l = sc.nextLine();
                if ("q".equalsIgnoreCase(l)) {
                    sendEnvelope(Wire.of("BYE", sid, Map.of()));
                    break;
                }
                int dx = 0, dy = 0;
                if (l.contains("w")) dy = -1;
                if (l.contains("s")) dy = +1;
                if (l.contains("a")) dx = -1;
                if (l.contains("d")) dx = +1;
                if ("stop".equalsIgnoreCase(l)) { dx = 0; dy = 0; }
                sendEnvelope(Wire.of("INPUT", sid, Map.of("seq", mac.nextSeq(), "dx", dx, "dy", dy)));
            }
        } catch (Exception ignore) {}
    }

    // handlers registration
    public void setSnapshotHandler(Consumer<NetSnapshotDTO> cb) { this.onSnapshot = (cb != null) ? cb : (s)->{}; }
    public void setOpponentLeftHandler(Consumer<String> cb)    { this.onOpponentLeft = (cb != null) ? cb : (s)->{}; }
    public void setStartHandler(Consumer<String> cb)           { this.onStart = (cb != null) ? cb : (s)->{}; }
    public void setErrorHandler(Consumer<String> cb)           { this.onError = (cb != null) ? cb : (s)->{}; }
    public void setLogHandler(Consumer<String> cb)             { this.onLog = (cb != null) ? cb : (x)->{}; }

    // accessors
    public String  getSid()  { return sid; }
    public String  getSide() { return mySide; }
    public boolean isOpen()  { return transport.isOpen(); }
    public String phase() { return phase; }
}
