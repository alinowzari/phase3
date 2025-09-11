// src/main/java/model/LayoutIO.java
package model.Loader;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import config.GameConfig;
import config.LevelPack;
import config.GameConfig.*;
import model.System;
import model.SystemManager;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public final class LayoutIO {
    private static final ObjectMapper OM = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private LayoutIO() {}

    // ... your applyLayoutFromConfig(...) as you have it ...

    public static GameConfig snapshotToConfig(GameConfig base, SystemManager model) {
        Map<Integer, model.System> byId = model.getAllSystems()
                .stream().collect(Collectors.toMap(System::getId, s -> s));

        List<SystemConfig> systemsNew = base.systems().stream().map(sc -> {
            model.System sys = byId.get(sc.id());
            Position pos = (sys != null)
                    ? new Position(sys.getLocation().x, sys.getLocation().y)
                    : (sc.position() == null ? new Position(0,0) : sc.position());
            return new SystemConfig(sc.id(), sc.type(), pos, sc.inputPorts(), sc.outputPorts(), sc.initialPackets());
        }).toList();

        List<LineConfig> linesNew = model.allLines.stream().map(l -> {
            model.System sA = l.getStart().getParentSystem();
            System sB = l.getEnd().getParentSystem();
            int outIdx = sA.getOutputPorts().indexOf(l.getStart());
            int inIdx  = sB.getInputPorts().indexOf(l.getEnd());

            List<BendTriplet> bends = l.getBendPoints().stream().map(bp ->
                    new BendTriplet(
                            new Position(bp.getStart().x,  bp.getStart().y),
                            new Position(bp.getMiddle().x, bp.getMiddle().y),
                            new Position(bp.getEnd().x,    bp.getEnd().y)
                    )
            ).toList();

            return new LineConfig(sA.getId(), outIdx, sB.getId(), inIdx, bends);
        }).toList();

        return new GameConfig(base.levelName(), systemsNew, linesNew);
    }

    /** Replace *this* level’s systems & lines for all indices ≥ fromIdx. */
    public static List<GameConfig> propagateToNextLevels(List<GameConfig> all, int fromIdx, SystemManager model) {
        if (fromIdx < 0 || fromIdx >= all.size()) return all;
        GameConfig snap = snapshotToConfig(all.get(fromIdx), model);

        List<GameConfig> out = new ArrayList<>(all);
        for (int i = fromIdx; i < out.size(); i++) {
            GameConfig base = out.get(i);
            out.set(i, new GameConfig(base.levelName(), snap.systems(), snap.lines()));
        }
        return out;
    }

    /* ---------- SAVE HELPERS (Jackson) ---------- */

    public static void saveGameConfig(Path path, GameConfig cfg) {
        try { OM.writeValue(path.toFile(), cfg); }
        catch (Exception e) { e.printStackTrace(); }
    }

    public static void saveLevelPack(Path path, List<GameConfig> levels) {
        try { OM.writeValue(path.toFile(), new LevelPack(levels)); }
        catch (Exception e) { e.printStackTrace(); }
    }
}
