// src/main/java/model/LevelsManager.java
package model;

import config.ConfigManager;
import config.GameConfig;
import config.GameConfig.SystemConfig;
import config.GameConfig.PacketConfig;
import config.GameConfig.LineConfig;
import config.GameConfig.BendTriplet;
import model.Loader.GameStatus;
import model.ports.InputPort;
import model.ports.OutputPort;
import model.packets.*;
import model.ports.inputs.*;
import model.ports.outputs.*;
import model.systems.*;

import java.awt.Point;
import java.util.*;
import java.util.stream.Collectors;

public class LevelsManager {
    public static GameStatus gameStatus = new GameStatus();
    private final List<GameConfig> configs;
    private final List<SystemManager> levelManagers;

    public LevelsManager() {
        this.configs = ConfigManager.getInstance().getAllLevels();
        this.levelManagers = new ArrayList<>(configs.size());

        for (GameConfig cfg : configs) {
            SystemManager sm = new SystemManager(gameStatus, cfg.levelName());

            // ---------- build systems ----------
            for (SystemConfig sc : cfg.systems()) {
                List<InputPort>  inputPorts  = new ArrayList<>();
                List<OutputPort> outputPorts = new ArrayList<>();
                Point loc = new Point(sc.position().x(), sc.position().y());

                System sys = switch (sc.type()) {
                    case "ReferenceSystem"    -> new ReferenceSystem    (loc, inputPorts, outputPorts, sm, sc.id());
                    case "NormalSystem"       -> new NormalSystem       (loc, inputPorts, outputPorts, sm, sc.id());
                    case "SpySystem"          -> new SpySystem          (loc, inputPorts, outputPorts, sm, sc.id());
                    case "VpnSystem"          -> new VpnSystem          (loc, inputPorts, outputPorts, sm, sc.id());
                    case "AntiTrojanSystem"   -> new AntiTrojanSystem   (loc, inputPorts, outputPorts, sm, sc.id());
                    case "DestroyerSystem"    -> new DestroyerSystem    (loc, inputPorts, outputPorts, sm, sc.id());
                    case "DistributionSystem" -> new DistributionSystem (loc, inputPorts, outputPorts, sm, sc.id());
                    case "MergerSystem"       -> new MergerSystem       (loc, inputPorts, outputPorts, sm, sc.id());
                    default -> new ReferenceSystem(loc, inputPorts, outputPorts, sm, sc.id());
                };

                // port centers (must match view.SystemView.WIDTH/HEIGHT)
                int sysX = sc.position().x();
                int sysY = sc.position().y();
                int sysW = view.SystemView.WIDTH;
                int sysH = view.SystemView.HEIGHT;

                // inputs on the left
                List<String> inNames = sc.inputPorts();
                for (int i = 0; i < inNames.size(); i++) {
                    int x = sysX;
                    int y = sysY + (i + 1) * sysH / (inNames.size() + 1);
                    inputPorts.add(makeInputPort(sys, new Point(x, y), inNames.get(i)));
                }

                // outputs on the right
                List<String> outNames = sc.outputPorts();
                for (int i = 0; i < outNames.size(); i++) {
                    int x = sysX + sysW;
                    int y = sysY + (i + 1) * sysH / (outNames.size() + 1);
                    outputPorts.add(makeOutputPort(sys, new Point(x, y), outNames.get(i)));
                }

                sm.addSystem(sys);

                // initial packets
                for (PacketConfig pc : sc.initialPackets()) {
                    for (int i = 0; i < pc.count(); i++) {
                        Packet pkt = switch (pc.type()) {
                            case "SquarePacket"   -> new SquarePacket();
                            case "TrianglePacket" -> new TrianglePacket();
                            case "InfinityPacket" -> new InfinityPacket();
                            case "BigPacket1"     -> new BigPacket1(pc.colorId());
                            case "BigPacket2"     -> new BigPacket2(pc.colorId());
                            case "ProtectedPacket"-> new ProtectedPacket<>(new SquarePacket());
                            case "SecretPacket1"  -> new SecretPacket1();
                            case "SecretPacket2"  -> new SecretPacket2<>(new ProtectedPacket<>(new SquarePacket()));
                            default -> throw new IllegalArgumentException("Unknown packet type: " + pc.type());
                        };
                        sys.addPacket(pkt);
                        sm.addPacket(pkt);
                        sm.addToFirstCountPacket();
                    }
                }
            }

            // ---------- NEW: build lines/bends from config ----------
            if (cfg.lines() != null && !cfg.lines().isEmpty()) {
                Map<Integer, System> byId = sm.getAllSystems()
                        .stream().collect(Collectors.toMap(System::getId, s -> s));

                for (LineConfig lc : cfg.lines()) {
                    System sA = byId.get(lc.startSystemId());
                    System sB = byId.get(lc.endSystemId());
                    if (sA == null || sB == null) continue;

                    List<OutputPort> outs = sA.getOutputPorts();
                    List<InputPort>  ins  = sB.getInputPorts();
                    if (lc.startOutputIndex() < 0 || lc.startOutputIndex() >= outs.size()) continue;
                    if (lc.endInputIndex()    < 0 || lc.endInputIndex()    >= ins.size())  continue;

                    OutputPort op = outs.get(lc.startOutputIndex());
                    InputPort  ip = ins.get(lc.endInputIndex());
                    if (op.getLine() != null || ip.getLine() != null) continue;

                    Line wire = new Line(op, ip);
                    op.setLine(wire);
                    ip.setLine(wire);

                    if (lc.bends() != null) {
                        for (BendTriplet bt : lc.bends()) {
                            wire.addBendPoint(
                                    new Point(bt.start().x(),  bt.start().y()),
                                    new Point(bt.middle().x(), bt.middle().y()),
                                    new Point(bt.end().x(),    bt.end().y()));
                        }
                    }
                    sm.addLine(wire); // updates used length
                }
                sm.recomputeUsedWireLength();
            }

            levelManagers.add(sm);
        }
    }

    // ---------- accessors ----------
    public List<GameConfig> getLevelConfigs()             { return List.copyOf(configs); }
    public List<SystemManager> getAllLevelManagers()      { return List.copyOf(levelManagers); }
    public SystemManager getLevelManager(int idx)         { return levelManagers.get(idx); }
    public SystemManager getLevelManager(String levelName){
        for (int i = 0; i < configs.size(); i++)
            if (Objects.equals(configs.get(i).levelName(), levelName)) return levelManagers.get(i);
        return null;
    }

    // ---------- NEW: snapshot helpers ----------

    /** Build a fresh GameConfig for this level using current system positions + lines. */
    public GameConfig snapshotLevelConfig(int idx) {
        GameConfig base = configs.get(idx);
        SystemManager sm = levelManagers.get(idx);

        // systems with updated positions
        Map<Integer, System> byId = sm.getAllSystems().stream()
                .collect(Collectors.toMap(System::getId, s -> s));

        List<SystemConfig> systemsNew = base.systems().stream().map(sc -> {
            System sys = byId.get(sc.id());
            GameConfig.Position pos = (sys != null)
                    ? new GameConfig.Position(sys.getLocation().x, sys.getLocation().y)
                    : sc.position();
            return new SystemConfig(
                    sc.id(), sc.type(), pos, sc.inputPorts(), sc.outputPorts(), sc.initialPackets()
            );
        }).collect(Collectors.toList());

        // lines from model
        List<LineConfig> linesNew = sm.allLines.stream().map(l -> {
            System sA = l.getStart().getParentSystem();
            System sB = l.getEnd().getParentSystem();
            int outIdx = sA.getOutputPorts().indexOf(l.getStart());
            int inIdx  = sB.getInputPorts().indexOf(l.getEnd());

            List<BendTriplet> bends = l.getBendPoints().stream().map(bp ->
                    new BendTriplet(
                            new GameConfig.Position(bp.getStart().x,  bp.getStart().y),
                            new GameConfig.Position(bp.getMiddle().x, bp.getMiddle().y),
                            new GameConfig.Position(bp.getEnd().x,    bp.getEnd().y)
                    )
            ).collect(Collectors.toList());

            return new LineConfig(
                    sA.getId(), outIdx, sB.getId(), inIdx, bends
            );
        }).collect(Collectors.toList());

        return new GameConfig(base.levelName(), systemsNew, linesNew);
    }

    /** Snapshot all levels (useful when saving). */
    public List<GameConfig> snapshotAll() {
        List<GameConfig> out = new ArrayList<>(configs.size());
        for (int i = 0; i < configs.size(); i++) out.add(snapshotLevelConfig(i));
        return out;
    }

    // ---------- helpers ----------
    private static InputPort makeInputPort(System sys, Point centre, String jsonName) {
        return switch (jsonName) {
            case "SquarePort"   -> new SquareInput(sys, centre);
            case "TrianglePort" -> new TriangleInput(sys, centre);
            case "InfinityPort" -> new InfinityInput(sys, centre);
            default             -> new SquareInput(sys, centre);
        };
    }
    private static OutputPort makeOutputPort(System sys, Point centre, String jsonName) {
        return switch (jsonName) {
            case "SquarePort"   -> new SquareOutput(sys, centre);
            case "TrianglePort" -> new TriangleOutput(sys, centre);
            case "InfinityPort" -> new InfinityOutput(sys, centre);
            default             -> new SquareOutput(sys, centre);
        };
    }
    /** @return number of levels loaded from config. */
    public int getLevelCount() {
        return configs.size();
    }

    /**
     * Index of the first level that is not yet passed.
     * If all are passed, returns the last level index (so user can replay).
     * Returns -1 if there are no levels.
     */
    public int firstUnpassedIndex(GameStatus status) {
        int n = configs.size();
        if (n == 0) return -1;

        for (int i = 0; i < n; i++) {
            String name = configs.get(i).levelName();
            if (!isPassed(status, name)) return i;
        }
        return n - 1; // all passed -> default to last
    }

    /** Overload using the static gameStatus you already keep. */
    public int firstUnpassedIndex() {
        return firstUnpassedIndex(gameStatus);
    }

    /**
     * Boolean array of pass states per level index.
     * passed[i] == true  ⇢ level i is passed.
     */
    public boolean[] passedArray(GameStatus status) {
        int n = configs.size();
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            out[i] = isPassed(status, configs.get(i).levelName());
        }
        return out;
    }

    /** Overload using the static gameStatus. */
    public boolean[] passedArray() {
        return passedArray(gameStatus);
    }

    /**
     * A level is enabled if it's the first level or the previous level is passed.
     */
    public boolean isLevelEnabled(int index, GameStatus status) {
        if (index < 0 || index >= configs.size()) return false;
        if (index == 0) return true;
        String prevName = configs.get(index - 1).levelName();
        return isPassed(status, prevName);
    }

    /** Overload using the static gameStatus. */
    public boolean isLevelEnabled(int index) {
        return isLevelEnabled(index, gameStatus);
    }

    /** Convenience: obtain a level name by index. */
    public String getLevelName(int index) {
        return configs.get(index).levelName();
    }

    /* ---------- internal helper to query GameStatus safely ---------- */
    private static boolean isPassed(GameStatus status, String levelName) {
        // If your GameStatus uses a different accessor, change this line to match:
        // e.g., status.isLevelPassed(levelName) or status.getPassed(levelName)
        return status.isPassed(levelName);
    }
}
