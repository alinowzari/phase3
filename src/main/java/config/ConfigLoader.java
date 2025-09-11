////package config;
////
////import com.fasterxml.jackson.databind.DeserializationFeature;
////import com.fasterxml.jackson.databind.ObjectMapper;
////import com.fasterxml.jackson.core.type.TypeReference;
////
////import java.io.InputStream;
////import java.nio.file.Path;
////import java.util.List;
////
/////**
//// * Loads GameConfig JSON from file or stream.
//// */
////public class ConfigLoader {
////    private static final ObjectMapper M = new ObjectMapper()
////            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
////
////    public static GameConfig loadConfig(Path jsonFile) throws Exception {
////        return M.readValue(jsonFile.toFile(), GameConfig.class);
////    }
////
////    public static List<GameConfig> loadConfigs(Path jsonFile) throws Exception {
////        return M.readValue(
////                jsonFile.toFile(),
////                new TypeReference<List<GameConfig>>() {}
////        );
////    }
////
////    public static List<GameConfig> loadConfigs(InputStream jsonStream) throws Exception {
////        return M.readValue(
////                jsonStream,
////                new TypeReference<List<GameConfig>>() {}
////        );
////    }
////}
//package config;
//
//import com.fasterxml.jackson.core.type.TypeReference;
//import com.fasterxml.jackson.databind.DeserializationFeature;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.databind.exc.MismatchedInputException;
//
//import java.io.InputStream;
//import java.nio.file.Path;
//import java.util.List;
//
///**
// * Loads 1-or-many levels from JSON.
// *
// *  • Old format (single level)       → root = GameConfig
// *  • Legacy “array of levels”        → root = [GameConfig, …]
// *  • New format (recommended)        → root = { "levels": [ … ] }
// */
//public final class ConfigLoader {
//
//    private static final ObjectMapper MAPPER = new ObjectMapper()
//            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
//
//    /* ------------------------------------------------------------------ */
//    /** Preferred helper: always returns a *list*, even if the file holds one level. */
//    public static List<GameConfig> loadLevels(Path json) throws Exception {
//        return loadLevels(json.toFile());
//    }
//    public static List<GameConfig> loadLevels(InputStream in) throws Exception {
//        return loadLevels(in, true);
//    }
//
//    /* ======== legacy entry points (kept for backward compatibility) ==== */
//    @Deprecated public static GameConfig loadConfig(Path json) throws Exception {
//        return MAPPER.readValue(json.toFile(), GameConfig.class);
//    }
//    @Deprecated public static List<GameConfig> loadConfigs(Path json) throws Exception {
//        return loadLevels(json);
//    }
//    @Deprecated public static List<GameConfig> loadConfigs(InputStream in) throws Exception {
//        return loadLevels(in, true);
//    }
//
//    /* ------------------------------------------------------------------ */
//    /* ------------- internal shared implementation --------------------- */
//    private static List<GameConfig> loadLevels(Object src) throws Exception {
//        return loadLevels(src, false);
//    }
//    /* inside ConfigLoader ----------------------------------------------- */
//
//    @SuppressWarnings("unchecked")
//    private static List<GameConfig> loadLevels(Object src, boolean isStream) throws Exception {
//
//        try {                                // ── try newest wrapper first
//            LevelPack pack = (isStream)
//                    ? MAPPER.readValue((InputStream) src, LevelPack.class)
//                    : MAPPER.readValue((java.io.File) src, LevelPack.class);   // <-- fixed
//            return pack.levels();
//
//        } catch (MismatchedInputException ex1) {
//            try {                            // ── maybe it’s a plain array
//                return (isStream)
//                        ? MAPPER.readValue((InputStream) src,
//                        new TypeReference<List<GameConfig>>() {})
//                        : MAPPER.readValue((java.io.File) src,                // <-- fixed
//                        new TypeReference<List<GameConfig>>() {});
//
//            } catch (MismatchedInputException ex2) {   // ── fall back: single level
//                GameConfig single = (isStream)
//                        ? MAPPER.readValue((InputStream) src, GameConfig.class)
//                        : MAPPER.readValue((java.io.File) src, GameConfig.class);   // <-- fixed
//                return List.of(single);
//            }
//        }
//    }
//
//
//    /* utility overloads so we can reuse code */
//    private static List<GameConfig> loadLevels(java.io.File f) throws Exception {
//        return loadLevels((Object) f);
//    }
//    private static List<GameConfig> loadLevels(InputStream in, boolean dummy) throws Exception {
//        return loadLevels((Object) in, true);
//    }
//
//    private ConfigLoader() { /* static utility */ }
//}
// src/main/java/config/ConfigLoader.java
package config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ConfigLoader {
    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static List<GameConfig> loadLevels(Path json) throws Exception {
        byte[] data = Files.readAllBytes(json);
        return loadLevelsFromBytes(data);
    }

    public static List<GameConfig> loadLevels(InputStream in) throws Exception {
        byte[] data = in.readAllBytes();        // <-- buffer once
        return loadLevelsFromBytes(data);
    }

    @Deprecated public static GameConfig loadConfig(Path json) throws Exception {
        return M.readValue(json.toFile(), GameConfig.class);
    }
    @Deprecated public static List<GameConfig> loadConfigs(Path json) throws Exception {
        return loadLevels(json);
    }
    @Deprecated public static List<GameConfig> loadConfigs(InputStream in) throws Exception {
        return loadLevels(in);
    }

    private static List<GameConfig> loadLevelsFromBytes(byte[] data) throws Exception {
        // 1) Try wrapper { "levels": [...] }
        try {
            LevelPack pack = M.readValue(data, LevelPack.class);
            if (pack != null && pack.levels() != null && !pack.levels().isEmpty()) {
                return pack.levels();
            }
        } catch (MismatchedInputException ignore) { /* fall through */ }

        // 2) Try bare array [ {…}, {…} ]
        try {
            return M.readValue(data, new TypeReference<List<GameConfig>>() {});
        } catch (MismatchedInputException ignore) { /* fall through */ }

        // 3) Single object { ... }
        GameConfig single = M.readValue(data, GameConfig.class);
        return List.of(single);
    }

    private ConfigLoader() {}
}
