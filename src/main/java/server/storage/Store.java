package server.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

public final class Store implements Closeable {
    private static final ObjectMapper M = new ObjectMapper();

    private final Path dir;
    private final Path file;
    private RandomAccessFile raf;
    private FileChannel channel;
    private BufferedWriter appender;

    public Store(Path dir) { this.dir = dir; this.file = dir.resolve("events.ndjson"); }

    public synchronized void open() throws IOException {
        Files.createDirectories(dir);
        if (!Files.exists(file)) Files.createFile(file);
        raf = new RandomAccessFile(file.toFile(), "rw");
        channel = raf.getChannel();
        raf.seek(raf.length());
        appender = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        sanitizeTail();
        System.out.println("[Store] writing to: " + file.toAbsolutePath()); // <— add this
    }

    private void sanitizeTail() throws IOException {
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            long goodBytes = 0;
            String ln;
            while ((ln = br.readLine()) != null) {
                try { M.readTree(ln); goodBytes += (ln + System.lineSeparator()).getBytes(StandardCharsets.UTF_8).length; }
                catch (Exception bad) { break; }
            }
            if (goodBytes < raf.length()) {
                channel.truncate(goodBytes);
                channel.force(true);
                raf.seek(goodBytes);
            }
        }
    }

    private synchronized void append(ObjectNode n) {
        try {
            n.put("ts", Instant.now().toEpochMilli());
            String s = n.toString();
            appender.write(s);
            appender.write(System.lineSeparator());
            appender.flush();
            channel.force(true);
        } catch (IOException e) {
            System.err.println("[Store] append failed: " + e.getMessage());
        }
    }

    /* ========== Public helpers (tiny schema) ========== */

    // Called when a room is created and START is sent
    public void matchStarted(String roomId, String level, String aToken, String bToken) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "match_started");
        n.put("roomId", roomId);
        n.put("level", level);
        n.put("aToken", aToken);
        n.put("bToken", bToken);
        append(n);
    }

    // Called when room flips BUILD -> ACTIVE (first time)
    public void matchActive(String roomId) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "match_active");
        n.put("roomId", roomId);
        append(n);
    }

    // Called when a player leaves during ACTIVE (forfeit win)
    public void matchForfeit(String roomId, String winnerToken, String loserToken, String reason) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "match_forfeit");
        n.put("roomId", roomId);
        n.put("winnerToken", winnerToken);
        n.put("loserToken", loserToken);
        n.put("reason", reason);
        append(n);
    }

    // Called when room ends (cleanup)
    public void matchEnded(String roomId, String reason) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "match_ended");
        n.put("roomId", roomId);
        n.put("reason", reason);
        append(n);
    }

    public Path file() { return file; }

    @Override public synchronized void close() throws IOException {
        if (appender != null) appender.close();
        if (channel != null) channel.close();
        if (raf != null) raf.close();
    }
}
