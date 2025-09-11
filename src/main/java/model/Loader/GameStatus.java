// src/main/java/model/GameStatus.java
package model.Loader;

import config.GameConfig;
import config.StatusConfig;
import config.StatusConfigManager;

import java.util.*;

public class GameStatus {

    /** Global wallet across the whole game (requested as static). */
    public static int totalCoinCount;

    /** Per-level fields (keyed by levelName). */
    private final Map<String, Integer>  coinsByLevel   = new HashMap<>();
    private final Map<String, Boolean>  passedByLevel  = new HashMap<>();
    private final Map<String, Float>    wireLenByLevel = new HashMap<>(); // <— NEW

    /** Access to the config loader/saver. */
    private final StatusConfigManager cfgMgr;

    public GameStatus() {
        this.cfgMgr = StatusConfigManager.getInstance();
        loadFromDTO(cfgMgr.getStatus());;
    }

    /** Replace in-memory state with what's in the current DTO. */
    private void applyConfig(StatusConfig cfg) {
        coinsByLevel.clear();
        passedByLevel.clear();
        wireLenByLevel.clear();
        totalCoinCount = Math.max(0, cfg.totalCoinCount());

        for (StatusConfig.LevelStatus ls : cfg.levels()) {
            coinsByLevel.put(ls.levelName(), Math.max(0, ls.coins()));
            passedByLevel.put(ls.levelName(), ls.passed());
            wireLenByLevel.put(ls.levelName(), Math.max(0f, ls.wireLength())); // <— NEW
        }
    }

    /** Build a DTO from the current in-memory state (for saving). */
    private StatusConfig toConfig() {
        // union of keys so we don't drop any level that only exists in one map
        Set<String> keys = new HashSet<>();
        keys.addAll(coinsByLevel.keySet());
        keys.addAll(passedByLevel.keySet());
        keys.addAll(wireLenByLevel.keySet());

        List<StatusConfig.LevelStatus> levels = keys.stream()
                .map(name -> new StatusConfig.LevelStatus(
                        name,
                        coinsByLevel.getOrDefault(name, 0),
                        wireLenByLevel.getOrDefault(name, 0f),   // <— pass the 3rd arg
                        passedByLevel.getOrDefault(name, false)))
                .toList();

        return new StatusConfig(totalCoinCount, levels);
    }
    public void resetNewGame(List<GameConfig> levels) {
        StatusConfig fresh = StatusConfigManager.getInstance().newGameDefaults(levels);
        loadFromDTO(fresh); // write a small private mapper that fills your maps and totalCoinCount
        save();
    }
    /** Persist current state to gameStatus.json. */
    public void save() { cfgMgr.save(toConfig()); }

    // ----- queries -----------------------------------------------------
    public int  getTotalCoins()                       { return totalCoinCount; }
    public int  getCoinsForLevel(String levelName)    { return coinsByLevel.getOrDefault(levelName, 0); }
    public boolean isLevelPassed(String levelName)    { return passedByLevel.getOrDefault(levelName, false); }
    public float getWireLength(String levelName)      { return wireLenByLevel.getOrDefault(levelName, 0f); }
    public Map<String,Integer>  coinsView()           { return Map.copyOf(coinsByLevel); }
    public Map<String,Boolean>  passedView()          { return Map.copyOf(passedByLevel); }
    public Map<String,Float>    wireLengthView()      { return Map.copyOf(wireLenByLevel); }

    // ----- mutations ---------------------------------------------------
    public void setLevelPassed(String levelName, boolean passed) {
        passedByLevel.put(levelName, passed);
    }

    /** Adds best-score coins to a level *and* to the global total. */
    public void addCoinsToLevel(String levelName, int coinsEarned) {
        int previous = coinsByLevel.getOrDefault(levelName, 0); // avoid NPE
        if (coinsEarned > previous) {
            coinsByLevel.put(levelName, coinsEarned);
            totalCoinCount += (coinsEarned - previous);
        }
    }

    /** Update stored wire length for this level (e.g., at end or on save). */
    public void setWireLength(String levelName, float wireLengthPx) {
        wireLenByLevel.put(levelName, Math.max(0f, wireLengthPx));
    }

    /** Attempts to spend from the global wallet. */
    public boolean trySpend(int price) {
        if (price < 0 || totalCoinCount < price) return false;
        totalCoinCount -= price;
        return true;
    }

    /** Mark a win, bank coins, store wire length, and save. */
    public void commitWin(String levelName, int coinsEarned) {
        setLevelPassed(levelName, true);
        addCoinsToLevel(levelName, coinsEarned);;
        save();
    }

    // legacy helpers you already had
    public int  getTotalCoin() { return totalCoinCount; }
    public void setTotalCoin(int totalCoinCount) { GameStatus.totalCoinCount = totalCoinCount; }

    public boolean isPassed(String levelName) { return passedByLevel.getOrDefault(levelName, false); }
    public synchronized void loadFromDTO(StatusConfig dto) {
        Objects.requireNonNull(dto, "dto must not be null");

        // Reset current state
        coinsByLevel.clear();
        passedByLevel.clear();
        wireLenByLevel.clear();

        // Global wallet
        totalCoinCount = Math.max(0, dto.totalCoinCount());

        // Per-level statuses
        List<StatusConfig.LevelStatus> lvls = dto.levels();
        if (lvls == null) return;

        for (StatusConfig.LevelStatus ls : lvls) {
            if (ls == null) continue;
            String name = ls.levelName();
            if (name == null || name.isBlank()) continue;

            coinsByLevel.put(name, Math.max(0,  ls.coins()));
            passedByLevel.put(name,           ls.passed());
            wireLenByLevel.put(name, Math.max(0f, ls.wireLength()));
        }
    }
}
