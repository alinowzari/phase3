package common.dto.util;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Hmac {
    public static byte[] sha256(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
