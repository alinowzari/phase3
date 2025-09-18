package common.util;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class Canon {
    // Deterministic serializer (ordered keys, no pretty)
    public static final ObjectMapper M = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    public static byte[] bytes(Object pojo) {
        try { return M.writeValueAsBytes(pojo); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public static byte[] bytes(JsonNode node) {
        try { return M.writeValueAsBytes(node); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
