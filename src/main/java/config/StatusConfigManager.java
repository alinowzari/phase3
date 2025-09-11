// src/main/java/config/StatusConfigManager.java
package config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Loads/saves gamestatus.json. Mirrors ConfigManager’s style. */
public final class StatusConfigManager {
    private static StatusConfigManager instance;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String fileName = "gameStatus.json";
    private final StatusConfig status;  // in-memory DTO

    private StatusConfigManager() {
        try {
            // 1) Try classpath (resources/gamestatus.json)
            StatusConfig loaded = null;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(fileName)) {
                if (in != null) {
                    loaded = mapper.readValue(in, StatusConfig.class);
                }
            }
            if (loaded == null) {
                Path p = Paths.get(fileName);
                if (Files.exists(p)) {
                    loaded = mapper.readValue(Files.readAllBytes(p), StatusConfig.class);
                }
            }

            // 3) Default if nothing found
            if (loaded == null) {
                loaded = new StatusConfig(0, java.util.List.of());
            }

            this.status = loaded;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + fileName, e);
        }
    }

    public static synchronized StatusConfigManager getInstance() {
        if (instance == null) instance = new StatusConfigManager();
        return instance;
    }

    /** Current DTO (read-only). */
    public StatusConfig getStatus() {
        return status;
    }

    /** Persist a DTO as ./gamestatus.json (pretty). */
    public void save(StatusConfig cfg) {
        try {
            Path out = Paths.get(fileName);
            Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cfg);
            Files.write(out, json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save " + fileName, e);
        }
    }
    public StatusConfig newGameDefaults(List<GameConfig> levels) {
        var levelStatuses = new java.util.ArrayList<StatusConfig.LevelStatus>();
        for (var lv : levels) {
            // choose sensible default wire budgets (or read from config if present)
            float defaultWire = switch (lv.levelName()) {
                case "Level 1" -> 5000f;
                case "Level 2" -> 20500f;
                case "Level 3" -> 30500f;
                case "Level 4" -> 40500f;
                case "Level 5" -> 50500f;
                default -> 5000f;
            };
            levelStatuses.add(new StatusConfig.LevelStatus(lv.levelName(), 0, defaultWire, false));
        }
        return new StatusConfig(100, levelStatuses); // totalCoin = 100
    }
}
