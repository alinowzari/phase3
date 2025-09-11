package config;

import java.util.List;

/**  Wrapper for the new “many levels in one file” format. */
public record LevelPack(List<GameConfig> levels) { }
