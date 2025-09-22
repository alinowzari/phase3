package server;

public record MatchResult(
        Winner winner,
        String reason,
        PlayerStats p1,
        PlayerStats p2
) {}