package common.dto.util;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Bytes {
    public static byte[] concat(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }
    public static byte[] le64(long x) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(x);
        return bb.array();
    }
}
