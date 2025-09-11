//package config;
//
//import java.io.InputStream;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.util.List;
//import java.util.Map;
//
///**
// * Loads the main *gameConfig.json* (single level that will be played) and, if present,
// * attempts to load a *levels.json* array for the Level‑Select screen.
// *
// * If *levels.json* does not exist, we gracefully fall back to **“the single gameConfig
// * file is the only level available.”**  This removes the hard dependency that was
// * crashing your app.
// */
//public final class ConfigManager {
//    private static ConfigManager instance;
//
//    private final GameConfig config;           // the level currently being played
//    private final List<GameConfig> allLevels;  // every level available in the menu
//
//    private ConfigManager() {
//        try {
//            // ---- 1. Mandatory: load the main level ---------------------------
//// modern, wrapper-aware version
//            Path gameConfigPath = Paths.get("gameConfig.json");
//            List<GameConfig> mainLevels = ConfigLoader.loadLevels(gameConfigPath);
//            this.config = mainLevels.getFirst();
//            // ---- 2. Optional: load levels.json -------------------------------
//            List<GameConfig> levels;
//            try (InputStream in = getClass().getClassLoader().getResourceAsStream("levels.json")) {
//                if (in != null) {
//                    levels = ConfigLoader.loadLevels(in);
//                } else {
//                    Path levelsPath = Paths.get("levels.json");
//                    if (Files.exists(levelsPath)) {
//                        levels = ConfigLoader.loadLevels(levelsPath);
//                    }
//                    else {
//                        // No levels.json?  Use the single gameConfig as the menu list.
//                        levels = mainLevels;
//                    }
//                }
//            }
//            this.allLevels = levels;
//
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to load configuration", e);
//        }
//    }
//
//    public static synchronized ConfigManager getInstance() {
//        if (instance == null) {
//            instance = new ConfigManager();
//        }
//        return instance;
//    }
//
//    public GameConfig getConfig() { return config; }
//
//    public List<GameConfig> getAllLevels() { return allLevels; }
//}
package config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;

public final class ConfigManager {
    private static final Path LEVELS_PATH = Paths.get("levels.json");
    private static final Path GAME_PATH   = Paths.get("gameConfig.json");

    private static ConfigManager instance;

    private volatile List<GameConfig> allLevels = List.of();
    private volatile GameConfig config;

    private volatile FileTime levelsLastMod = FileTime.fromMillis(0);
    private volatile FileTime gameLastMod   = FileTime.fromMillis(0);

    private final Object reloadLock = new Object();

    private ConfigManager() {
        forceReload();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) instance = new ConfigManager();
        return instance;
    }

    /** Public manual reload if you want a button/menu item to refresh. */
    public void forceReload() {
        synchronized (reloadLock) {
            try {
                // ---- Load main level (the one being played) ----
                List<GameConfig> mainLevels = loadLevelsPreferringDiskThenClasspath(GAME_PATH, "gameConfig.json");
                if (mainLevels.isEmpty()) {
                    throw new IllegalStateException("No levels found in gameConfig.json (disk or classpath).");
                }
                config = mainLevels.get(0); // avoid getFirst() for wider Java compatibility

                // ---- Build menu list (all levels) ----
                List<GameConfig> menuLevels;
                if (Files.exists(LEVELS_PATH)) {
                    menuLevels = ConfigLoader.loadLevels(LEVELS_PATH);
                    levelsLastMod = Files.getLastModifiedTime(LEVELS_PATH);
                } else if (mainLevels.size() > 1) {
                    // If gameConfig.json contains a wrapper with multiple levels, use it
                    menuLevels = mainLevels;
                } else {
                    // Finally, try classpath levels.json
                    try (InputStream in = resource("levels.json")) {
                        menuLevels = (in != null) ? ConfigLoader.loadLevels(in) : mainLevels;
                    }
                }
                allLevels = List.copyOf(menuLevels);

                // Record timestamps for hot-reload checks
                if (Files.exists(GAME_PATH))   gameLastMod   = Files.getLastModifiedTime(GAME_PATH);
                if (Files.exists(LEVELS_PATH)) levelsLastMod = Files.getLastModifiedTime(LEVELS_PATH);

            } catch (Exception e) {
                throw new RuntimeException("Failed to load configuration", e);
            }
        }
    }

    public GameConfig getConfig() {
        reloadIfChanged();
        return config;
    }

    public List<GameConfig> getAllLevels() {
        reloadIfChanged();
        return allLevels;
    }

    // ---------- Internals ----------

    private void reloadIfChanged() {
        try {
            boolean changed = false;

            if (Files.exists(LEVELS_PATH)) {
                FileTime t = Files.getLastModifiedTime(LEVELS_PATH);
                if (t.compareTo(levelsLastMod) > 0) changed = true;
            }

            if (Files.exists(GAME_PATH)) {
                FileTime t = Files.getLastModifiedTime(GAME_PATH);
                if (t.compareTo(gameLastMod) > 0) changed = true;
            }

            if (changed) forceReload();
        } catch (IOException e) {
            // Non-fatal: keep old config and log
            e.printStackTrace();
        }
    }

    private static List<GameConfig> loadLevelsPreferringDiskThenClasspath(Path diskPath, String resourceName) {
        // Prefer the editable file on disk
        if (Files.exists(diskPath)) {
            try {
                return ConfigLoader.loadLevels(diskPath); // may throw Exception
            } catch (Exception e) {
                System.err.println("[Config] Failed to load levels from disk: " + diskPath);
                e.printStackTrace();
                // fall through to classpath fallback
            }
        }

        // Fallback: classpath resource (e.g., packed in resources)
        try (InputStream in = resource(resourceName)) {
            if (in != null) {
                try {
                    return ConfigLoader.loadLevels(in); // may throw Exception
                } catch (Exception e) {
                    System.err.println("[Config] Failed to load levels from classpath: " + resourceName);
                    e.printStackTrace();
                }
            }
        } catch (IOException io) {
            // Only opening/closing the resource can throw IOException
            System.err.println("[Config] Error opening resource " + resourceName + ": " + io.getMessage());
        }

        // Final fallback: no levels found here
        return List.of();
    }


    private static InputStream resource(String name) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = (cl != null) ? cl.getResourceAsStream(name) : null;
        return (in != null) ? in : ConfigManager.class.getClassLoader().getResourceAsStream(name);
    }
}
