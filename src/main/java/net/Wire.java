package net;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;

import java.io.IOException;

/**
 * Wire helpers for NDJSON messages.
 * IMPORTANT: the ObjectMapper stays PRIVATE; callers use read/tree helpers.
 */
public final class Wire {

    // One shared mapper, configured once. PRIVATE on purpose.
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Wire() {} // no instances

    /** Generic envelope: type + session id + arbitrary JSON payload. */
    public static final class Envelope {
        public String   t;    // message type
        public Long     id;   // optional
        public String   sid;  // session id (optional)
        public JsonNode data; // payload
        // default ctor kept for Jackson
    }

    /** Build an envelope from any POJO or Map. */
    public static Envelope of(String type, String sid, Object payload) {
        Envelope e = new Envelope();
        e.t   = type;
        e.sid = sid;
        e.data = M.valueToTree(payload); // works for Map or any POJO/record
        return e;
    }

    /** Encode as a single NDJSON line (newline appended). */
    public static String encode(Envelope e) {
        try { return M.writeValueAsString(e) + "\n"; }
        catch (JsonProcessingException ex) { throw new RuntimeException("encode failed", ex); }
    }

    /** Decode one NDJSON line into an envelope. */
    public static Envelope decode(String line) {
        try { return M.readValue(line, Envelope.class); }
        catch (IOException ex) { throw new RuntimeException("decode failed", ex); }
    }

    /** Convert a JsonNode payload into a target DTO type. */
    public static <T> T read(JsonNode node, Class<T> type) {
        try { return M.treeToValue(node, type); }
        catch (JsonProcessingException ex) { throw new RuntimeException("treeToValue failed", ex); }
    }

    /** Convert any POJO/Map into a JsonNode (rarely needed directly). */
    public static JsonNode tree(Object value) {
        return M.valueToTree(value);
    }
}
