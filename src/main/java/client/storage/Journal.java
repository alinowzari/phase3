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

    public Journal(Path dir) {
        this.dir = dir;
        this.file = dir.resolve("cmd.journal");
        this.tmpFile = dir.resolve("cmd.journal.tmp");
        this.bakFile = dir.resolve("cmd.journal.bak");
    }

    public void open() throws IOException {
        Files.createDirectories(dir);
        if (!Files.exists(file)) Files.createFile(file);

        // Open low-level handles first
        raf = new RandomAccessFile(file.toFile(), "rw");
        channel = raf.getChannel();

        // Sanitize any torn/partial last line from a previous crash,
        // then position to end for appends.
        sanitizeTail();
        raf.seek(raf.length());

        // Create the high-level appender AFTER sanitization/truncation
        appender = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /**
     * Truncate the journal at the first invalid JSON line.
     * Streams line-by-line and computes the exact byte length using the same newline we write with.
     */
    private void sanitizeTail() throws IOException {
        final String nl = System.lineSeparator();
        long goodBytes = 0;

        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String ln;
            while ((ln = br.readLine()) != null) {
                try {
                    M.readTree(ln); // validate JSON line
                    // Count the bytes we actually wrote: line + newline
                    goodBytes += (ln.getBytes(StandardCharsets.UTF_8).length + nl.getBytes(StandardCharsets.UTF_8).length);
                } catch (Exception bad) {
                    break; // stop at first bad/torn line
                }
            }
        }

        if (goodBytes < raf.length()) {
            channel.truncate(goodBytes);
            channel.force(true);
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

        // Durability: we write via 'appender' but fsync via the underlying channel.
        // It's the same inode; force(true) ensures metadata + data hit disk.
        channel.force(true);
    }

    /** Read entries with seq > fromSeq (exclusive). Tolerates bad lines. */
    public List<Entry> readAfter(long fromSeq) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String ln;
            while ((ln = br.readLine()) != null) {
                final JsonNode n;
                try {
                    n = M.readTree(ln);
                } catch (Exception bad) {
                    // skip malformed/torn lines, keep going
                    continue;
                }

                long seq = n.path("seq").asLong(-1);
                if (seq <= fromSeq) continue;

                String mac = n.path("mac").asText("");
                JsonNode cmdNode = n.get("cmd");
                if (cmdNode == null || !cmdNode.isObject()) {
                    // malformed entry; skip
                    continue;
                }

                out.add(new Entry(seq, mac, (ObjectNode) cmdNode));
            }
        }
        return out;
    }

    /**
     * Compact journal to keep only entries with seq > keepAfter (exclusive).
     * Windows-safe: we close all writers/channels BEFORE doing atomic moves.
     */
    public synchronized void compact(long keepAfter) throws IOException {
        // Build the compacted temp file first
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             BufferedWriter bw = Files.newBufferedWriter(
                     tmpFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            String ln;
            while ((ln = br.readLine()) != null) {
                JsonNode n;
                try {
                    n = M.readTree(ln);
                } catch (Exception ignore) {
                    continue; // skip malformed lines
                }
                long seq = n.path("seq").asLong(-1);
                if (seq > keepAfter) {
                    bw.write(ln);
                    bw.write(System.lineSeparator());
                }
            }
        }

        // Close open handles BEFORE moving files (important on Windows)
        closeInternal();

        // Backup old, then replace with tmp
        if (Files.exists(bakFile)) Files.delete(bakFile);
        Files.move(file, bakFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // Reopen appender/channel/raf at end of file
        open();
    }

    /** Reset fully (e.g., after RESUME_RESET). */
    public synchronized void reset() throws IOException {
        closeInternal();
        Files.deleteIfExists(file);
        Files.deleteIfExists(tmpFile);
        Files.deleteIfExists(bakFile);
        open();
    }

    @Override
    public void close() throws IOException {
        closeInternal();
    }

    private void closeInternal() throws IOException {
        IOException first = null;
        try { if (appender != null) appender.close(); }
        catch (IOException ex) { first = (first == null ? ex : first); }
        finally { appender = null; }

        try { if (channel != null) channel.close(); }
        catch (IOException ex) { first = (first == null ? ex : first); }
        finally { channel = null; }

        try { if (raf != null) raf.close(); }
        catch (IOException ex) { first = (first == null ? ex : first); }
        finally { raf = null; }

        if (first != null) throw first;
    }
}
