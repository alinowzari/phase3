package client.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class Journal implements Closeable {
    private static final ObjectMapper M = new ObjectMapper();

    private final Path dir;
    private final Path file;        // .../cmd.journal
    private final Path tmpFile;     // .../cmd.journal.tmp
    private final Path bakFile;     // .../cmd.journal.bak

    private RandomAccessFile raf;
    private FileChannel channel;
    private BufferedWriter appender;

    public static final class Entry {
        public final long seq;
        public final String macHex;
        public final ObjectNode cmdNode;

        public Entry(long seq, String macHex, ObjectNode cmdNode) {
            this.seq = seq; this.macHex = macHex; this.cmdNode = cmdNode;
        }
    }

    public Journal(Path dir) { this.dir = dir; this.file = dir.resolve("cmd.journal");
        this.tmpFile = dir.resolve("cmd.journal.tmp"); this.bakFile = dir.resolve("cmd.journal.bak"); }

    public void open() throws IOException {
        Files.createDirectories(dir);
        if (!Files.exists(file)) Files.createFile(file);
        raf = new RandomAccessFile(file.toFile(), "rw");
        channel = raf.getChannel();
        // position at end; create writer in append mode
        raf.seek(raf.length());
        appender = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        // sanitize trailing partial line if any (from crash)
        sanitizeTail();
    }

    private void sanitizeTail() throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        long goodBytes = 0;
        for (String ln : lines) {
            try {
                M.readTree(ln); // validate JSON line
                goodBytes += (ln + System.lineSeparator()).getBytes(StandardCharsets.UTF_8).length;
            } catch (Exception bad) {
                break; // truncate at first bad line
            }
        }
        if (goodBytes < raf.length()) {
            channel.truncate(goodBytes);
            channel.force(true);
            raf.seek(goodBytes);
        }
    }

    /** Append one entry and fsync. */
    public synchronized void append(Entry e) throws IOException {
        ObjectNode line = M.createObjectNode();
        line.put("seq", e.seq);
        line.put("mac", e.macHex);
        line.set("cmd", e.cmdNode);
        String s = line.toString();
        appender.write(s);
        appender.write(System.lineSeparator());
        appender.flush();
        channel.force(true);
    }

    /** Read entries with seq > fromSeq (exclusive). */
    public List<Entry> readAfter(long fromSeq) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String ln;
            while ((ln = br.readLine()) != null) {
                JsonNode n = M.readTree(ln);
                long seq = n.path("seq").asLong(-1);
                if (seq > fromSeq) {
                    String mac = n.path("mac").asText("");
                    ObjectNode cmd = (ObjectNode) n.path("cmd");
                    out.add(new Entry(seq, mac, cmd));
                }
            }
        }
        return out;
    }

    /** Compact journal to keep only entries with seq > keepAfter (exclusive). */
    public synchronized void compact(long keepAfter) throws IOException {
        // write survivors to tmp, then atomically replace
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             BufferedWriter bw = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            String ln;
            while ((ln = br.readLine()) != null) {
                JsonNode n;
                try { n = M.readTree(ln); } catch (Exception ignore) { continue; }
                long seq = n.path("seq").asLong(-1);
                if (seq > keepAfter) {
                    bw.write(ln);
                    bw.write(System.lineSeparator());
                }
            }
        }
        // backup old, then replace
        if (Files.exists(bakFile)) Files.delete(bakFile);
        Files.move(file, bakFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // reopen appender at end
        if (appender != null) appender.close();
        if (channel != null) channel.close();
        if (raf != null) raf.close();
        open();
    }

    /** Reset fully (e.g., after RESUME_RESET). */
    public synchronized void reset() throws IOException {
        if (appender != null) appender.close();
        if (channel != null) channel.close();
        if (raf != null) raf.close();
        Files.deleteIfExists(file);
        Files.deleteIfExists(tmpFile);
        Files.deleteIfExists(bakFile);
        open();
    }

    @Override public void close() throws IOException {
        if (appender != null) appender.close();
        if (channel != null) channel.close();
        if (raf != null) raf.close();
    }
}
