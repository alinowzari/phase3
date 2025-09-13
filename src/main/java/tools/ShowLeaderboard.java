package tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ShowLeaderboard {
    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        final Path p = (args.length > 0)
                ? Paths.get(args[0])
                : Paths.get(System.getProperty("user.home"), ".phase3", "server", "events.ndjson");

        if (!Files.exists(p)) {
            System.out.println("No data at " + p.toAbsolutePath());
            return;
        }

        int seenStarted = 0, seenActive = 0, seenForfeit = 0, seenEnded = 0;
        Map<String, Stats> stats = new HashMap<>();

        try (var br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String ln;
            while ((ln = br.readLine()) != null) {
                JsonNode n;
                try { n = M.readTree(ln); } catch (Exception ignore) { continue; }
                String type = n.path("type").asText("");

                switch (type) {
                    case "match_started" -> {
                        String a = n.path("aToken").asText("");
                        String b = n.path("bToken").asText("");
                        stats.computeIfAbsent(a, Stats::new).started++;
                        stats.computeIfAbsent(b, Stats::new).started++;
                        seenStarted++;
                    }
                    case "match_active" -> { seenActive++; }
                    case "match_forfeit" -> {
                        String w = n.path("winnerToken").asText("");
                        String l = n.path("loserToken").asText("");
                        stats.computeIfAbsent(w, Stats::new).wins++;
                        stats.computeIfAbsent(l, Stats::new).losses++;
                        seenForfeit++;
                    }
                    case "match_ended" -> { seenEnded++; }
                    default -> { /* ignore unknown */ }
                }
            }
        }

        if (stats.isEmpty()) {
            System.out.println("No events found at " + p.toAbsolutePath()
                    + " (started=" + seenStarted
                    + ", active=" + seenActive
                    + ", forfeit=" + seenForfeit
                    + ", ended=" + seenEnded + "). Run a match first.");
            return;
        }

        List<Stats> list = new ArrayList<>(stats.values());
        list.sort(Comparator
                .comparingInt((Stats s) -> s.wins).reversed()
                .thenComparingInt(s -> -s.started)); // tie-breaker

        System.out.printf("Source: %s%n", p.toAbsolutePath());
        System.out.printf("%-16s %6s %6s %6s%n", "player(token…)", "wins", "loss", "start");
        for (int i = 0; i < Math.min(20, list.size()); i++) {
            Stats s = list.get(i);
            System.out.printf("%-16s %6d %6d %6d%n", shorten(s.player), s.wins, s.losses, s.started);
        }
    }

    private static String shorten(String t) {
        return (t == null) ? "" : (t.length() > 12 ? t.substring(0, 12) + "…" : t);
    }

    static final class Stats {
        final String player;
        int wins, losses, started;
        Stats(String p) { this.player = p; }
    }
}
