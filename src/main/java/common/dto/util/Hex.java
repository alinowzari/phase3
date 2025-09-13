package common.dto.util;


public final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    public static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            out[j++] = DIGITS[b >>> 4];
            out[j++] = DIGITS[b & 0x0f];
        }
        return new String(out);
    }
    public static byte[] decode(String s) {
        if (s == null) return new byte[0];
        int len = s.length();
        if ((len & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[len / 2];
        for (int i = 0, j = 0; i < len; i += 2) {
            out[j++] = (byte)((nibble(s.charAt(i)) << 4) | nibble(s.charAt(i+1)));
        }
        return out;
    }
    private static int nibble(char c) {
        if ('0' <= c && c <= '9') return c - '0';
        if ('a' <= c && c <= 'f') return 10 + c - 'a';
        if ('A' <= c && c <= 'F') return 10 + c - 'A';
        throw new IllegalArgumentException("bad hex char: " + c);
    }
}
