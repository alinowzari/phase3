package net;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;

import java.util.Map;

public final class Wire {
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static String encode(Envelope e) {
        try {
            return M.writeValueAsString(e) + "\n";   // NDJSON: newline-delimited
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("encode failed", ex);
        }
    }

    public static Envelope decode(String line) {
        try {
            return M.readValue(line, Envelope.class);
        } catch (Exception ex) {
            throw new RuntimeException("decode failed", ex);
        }
    }

    /** Generic envelope: type + session + arbitrary JSON payload. */
    public static final class Envelope {
        public String t;            // message type
        public Long   id;           // optional
        public String sid;          // session id (optional)
        public JsonNode data;       // payload (any shape)
    }

    /** Convenience builder from a Map/POJO payload. */
    public static Envelope of(String type, String sid, Object dataPojoOrMap) {
        Envelope e = new Envelope();
        e.t = type;
        e.sid = sid;
        e.data = M.valueToTree(dataPojoOrMap); // works for Map or any POJO
        return e;
    }
}
