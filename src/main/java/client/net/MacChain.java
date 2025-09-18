package client.net;

import com.fasterxml.jackson.databind.node.ObjectNode;
import common.cmd.ClientCommand;
import common.util.Bytes;
import common.util.Canon;
import common.util.Hex;
import common.util.Hmac;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maintains rolling HMAC chain and command sequencing.
 * Thread-safe for client-side use.
 */
public final class MacChain {
    private final AtomicLong seq = new AtomicLong(-1);
    private volatile byte[] keyK    = new byte[0];   // HMAC key
    private volatile long   lastSeq = -1L;           // server baseline
    private volatile byte[] lastMac = new byte[32];  // 32 zero bytes baseline

    private final Map<Long, byte[]> sentMacBySeq = new ConcurrentHashMap<>();

    public long nextSeq() { return seq.incrementAndGet(); }

    public void initKey(byte[] key) { this.keyK = (key == null ? new byte[0] : key.clone()); }

    /** Adopt authoritative lastSeq/lastMac from server (RESUMED). */
    public void adopt(long serverLastSeq, byte[] serverLastMac) {
        this.lastSeq = serverLastSeq;
        this.lastMac = (serverLastMac != null ? serverLastMac.clone() : new byte[32]);
        // ensure our generator produces lastSeq+1
        while (this.seq.get() < serverLastSeq) this.seq.set(serverLastSeq);
    }

    /** Reset to baseline (fresh session). */
    public void resetBaseline() {
        this.lastSeq = -1L;
        this.lastMac = new byte[32];
        this.seq.set(-1L);
    }

    /** Build the COMMAND envelope body: {seq, mac, cmd} and remember mac for ack. */
    public MacResult signCommand(ClientCommand cmd) {
        final long s = ensureSeq(cmd);
        final ObjectNode cmdNode = Canon.M.valueToTree(cmd);
        // DO NOT override "type" — Jackson annotations provide it.

        final byte[] body = Canon.bytes(cmdNode);
        final byte[] msg  = Bytes.concat(lastMac, Bytes.le64(s), body);
        final byte[] mac  = (keyK.length == 0) ? new byte[32] : Hmac.sha256(keyK, msg);

        sentMacBySeq.put(s, mac);

        final ObjectNode data = Canon.M.createObjectNode();
        data.put("seq", s);
        data.put("mac", Hex.encode(mac));
        data.set("cmd", cmdNode);

        return new MacResult(s, mac, data);
    }

    /** Advance the rolling chain on ACK (accepted or not, but not a duplicate). */
    public void ack(long seqAck, boolean accepted, boolean dup) {
        if (seqAck < 0) return;
        final byte[] mac = sentMacBySeq.remove(seqAck);
        if (mac != null && seqAck == lastSeq + 1 && (accepted || !dup)) {
            lastSeq = seqAck;
            lastMac = mac;
        }
    }

    public long lastSeq() { return lastSeq; }
    public byte[] lastMac() { return lastMac.clone(); }

    private static long extractSeq(ClientCommand cmd) {
        try {
            var f = cmd.getClass().getDeclaredField("seq");
            f.setAccessible(true);
            Object v = f.get(cmd);
            return (v instanceof Number) ? ((Number) v).longValue() : -1L;
        } catch (Exception ignore) { return -1L; }
    }
    private static void setSeqIfPresent(ClientCommand cmd, long s) {
        try {
            var f = cmd.getClass().getDeclaredField("seq");
            f.setAccessible(true);
            f.setLong(cmd, s);
        } catch (NoSuchFieldException ignore) {
            // command type doesn't carry its own seq → nothing to do
        } catch (Exception ignore) {
        }
    }
    private long ensureSeq(ClientCommand cmd) {
        long s = extractSeq(cmd);
        if (s < 0) {
            s = nextSeq();
            setSeqIfPresent(cmd, s);
        } else {
            // keep generator monotonic if user pre-filled a seq
            while (seq.get() < s) seq.set(s);
        }
        return s;
    }

    public record MacResult(long seq, byte[] mac, ObjectNode data) {}
}
