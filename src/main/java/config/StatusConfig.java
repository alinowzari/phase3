// src/main/java/config/GameStatusConfig.java
package config;

import java.util.List;

/** Pure DTO for loading/saving the GameStatus JSON. */
public record StatusConfig(
        int totalCoinCount,
        List<LevelStatus> levels
)
{
    public record LevelStatus(String levelName, int coins, float wireLength, boolean passed) {}
}
