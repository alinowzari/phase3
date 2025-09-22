package server;

public record PlayerStats(
        boolean levelsPassed,
        int coins,
        double wireUsed// first tick when required levels were satisfied, else Long.MAX_VALUE
) {}