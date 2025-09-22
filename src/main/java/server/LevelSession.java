package server;

import common.AbilityType;
import common.MatchInfoDTO;
import common.NetSnapshotDTO;
import common.PointDTO;
import common.RoomState;
import common.cmd.*;
import model.BendPoint;
import model.Line;
import model.System;
import model.SystemManager;
import model.ports.InputPort;
import model.ports.OutputPort;
import server.ops.Arsenal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/** One player's authoritative match state backed by a single SystemManager. */
public final class LevelSession {

    // --- public meta (room will read these) ---
    private final String levelId;
    private long   tick       = 0;
    private long   timeLeftMs = 60_000;   // set by room
    private int    score      = 0;
    private volatile boolean levelPassed = false;
    private volatile boolean ready = false;
    // --- core model ---
    public final SystemManager sm;        // authoritative model for THIS side

    // --- inbound intents from reader threads ---
    private final ConcurrentLinkedQueue<ClientCommand> pending = new ConcurrentLinkedQueue<>();

    private PlayerStats playerStats;

    // (optional) “controllable systems” if you want per-system cooldowns
    private final List<Integer> controllable = new ArrayList<>();
    private final Arsenal arsenal = new Arsenal(3, 3, 3);
    private volatile boolean launched = false;
    // Timed effects (local to this side)
    private final List<Effect> effects = new ArrayList<>();

    // --- costs (match your UI) ---
    private static final int COST_CENTER_20S  = 10;
    private static final int COST_ZEROACC_20S = 20;

    // --- ability timing constants ---
    private static final int PENIA_TOTAL_MS = 10_000;
    private static final int PENIA_STEP_MS  = 2_000;
    private static final int BOOST_MS       = 10_000;
    private static final float BOOST_FACTOR = 1.03f;

    // geometry constants (keep in sync with client)
    private static final int SYS_W = 90, SYS_H = 70;

    private interface Effect {
        /** advance effect; return true when finished */
        boolean tick(long nowMs, int dtMs);
    }

    public LevelSession(String levelId, SystemManager sm, long durationMs) {
        this.levelId    = levelId;
        this.sm         = sm;
        this.timeLeftMs = durationMs;
    }

    // ===== API for Room to define which systems this side controls (optional) =====
    public void setControllable(List<Integer> ids) {
        controllable.clear();
        if (ids != null) controllable.addAll(ids);
    }

    // ===== enqueue from GameServer reader thread =====
    public void enqueue(ClientCommand cmd) { if (cmd != null) pending.add(cmd); }

    // ===== tick thread only =====
    public void step(int dtMs) {
        // 1) apply all enqueued commands on the sim thread
        ClientCommand c;
        while ((c = pending.poll()) != null) {
            try {
                switch (c) {
                    case AddLineCmd a    -> addLine(a);
                    case RemoveLineCmd r -> removeLine(r);
                    case AddBendCmd b    -> addBend(b);
                    case MoveBendCmd m   -> moveBend(m);
                    case MoveSystemCmd m -> moveSystem(m);
                    case UseAbilityCmd u -> useAbility(u);
                    case ReadyCmd rdy    -> { /* room handles phase; no-op here */ }
                    case LaunchCmd l     -> {
                        sm.launchPackets();
                        launched = true;
                        java.lang.System.out.println("is it launched "+launched);
                    }
                    default -> { /* ignore unknown */ }
                }
            } catch (Exception ex) {
                java.lang.System.out.println("[LevelSession] cmd failed: " + ex);
            }
        }

        // 2) advance authoritative simulation
        sm.update(dtMs / 1000f);
        tick++;
        timeLeftMs = Math.max(0, timeLeftMs - dtMs);

        levelPassed = sm.isLevelPassed();   // <-- you were missing this line
        score   = sm.coinCount;
        ready=sm.isReady();
        if(activePackets()==0){
            playerStats=new PlayerStats(isLevelPassed(),score(),wireUsedPx());
        }
        // 3) advance effects
        long now = java.lang.System.currentTimeMillis();
        for (var it = effects.iterator(); it.hasNext();) {
            if (it.next().tick(now, dtMs)) it.remove();
        }

        // 4) scoring source
    }

    // ===== authoritative mutations =====

    private void addLine(AddLineCmd c) {
        System sysA = sysById(c.fromSystemId());
        System sysB = sysById(c.toSystemId());
        if (sysA == null || sysB == null) {
            java.lang.System.out.println("[LevelSession] addLine reject: bad systemIds " + c);
            return;
        }

        List<OutputPort> outs = sysA.getOutputPorts();
        List<InputPort>  ins  = sysB.getInputPorts();
        if (!inRange(c.fromOutputIndex(), outs) || !inRange(c.toInputIndex(), ins)) {
            java.lang.System.out.println("[LevelSession] addLine reject: bad port idx O=" + c.fromOutputIndex()
                    + " I=" + c.toInputIndex() + " " + c);
            return;
        }

        OutputPort op = outs.get(c.fromOutputIndex());
        InputPort  ip = ins.get(c.toInputIndex());

        if (op.getLine() != null || ip.getLine() != null) {
            java.lang.System.out.println("[LevelSession] addLine reject: endpoint busy " + c);
            return;
        }
        if (ip.getType() != op.getType()) {
            java.lang.System.out.println("[LevelSession] addLine reject: type mismatch op=" + op.getType() + " ip=" + ip.getType());
            return;
        }
        if (!sm.canCreateWire(op, ip)) {
            java.lang.System.out.println("[LevelSession] addLine reject: over wire budget " + c);
            return;
        }

        Line line = new Line(op, ip); // no default bends
        op.setLine(line);
        ip.setLine(line);
        sm.addLine(line);
        sm.recomputeUsedWireLength();
        sm.syncPortCenters(sysA);
        sm.syncPortCenters(sysB);
        java.lang.System.out.println("[LevelSession] addLine OK: " + sysA.getId()+ " -> " + sysB.getId() );
    }

    private void removeLine(RemoveLineCmd c) {
        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
        if (line == null) return;

        // detach + remove from manager
        line.getStart().setLine(null);
        line.getEnd().setLine(null);
        sm.removeLine(line);
        sm.recomputeUsedWireLength();
    }

    private void addBend(AddBendCmd c) {
        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
        if (line == null) return;

        if (line.getBendPoints().size() >= 3) return;

        int before = line.lengthPx();
        BendPoint added = line.addBendPoint(pt(c.footA()), pt(c.middle()), pt(c.footB()));
        int after = line.lengthPx();

        if (!sm.tryCommitLineGeometryChange(line, before, after)) {
            line.removeBendPoint(added);
        }
    }

    private void moveBend(MoveBendCmd c) {
        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
        if (line == null) return;

        var bends = line.getBendPoints();
        if (c.bendIndex() < 0 || c.bendIndex() >= bends.size()) return;

        var bend = bends.get(c.bendIndex());
        Point prev = new Point(bend.getMiddle());
        int before = line.lengthPx();

        bend.setMiddle(pt(c.newMiddle()));
        line.invalidateLengthCache();
        int after = line.lengthPx();

        if (!sm.tryCommitLineGeometryChange(line, before, after)) {
            bend.setMiddle(prev);
            line.invalidateLengthCache();
        }
    }

    private void moveSystem(MoveSystemCmd m) {
        System sys = sysById(m.systemId());
        if (sys == null) return;

        Point oldTopLeft = sys.getLocation();
        int dx = m.x() - oldTopLeft.x;
        int dy = m.y() - oldTopLeft.y;

        int incidentBefore = 0, incidentAfter = 0;
        for (Line l : new ArrayList<>(sm.allLines)) {
            boolean shiftsStart = sys.getOutputPorts().contains(l.getStart());
            boolean shiftsEnd   = sys.getInputPorts().contains(l.getEnd());
            if (!shiftsStart && !shiftsEnd) continue;

            incidentBefore += l.lengthPx();
            if (shiftsStart && !shiftsEnd)      incidentAfter += l.lengthIfShiftStartBy(dx, dy);
            else if (!shiftsStart && shiftsEnd) incidentAfter += l.lengthIfShiftEndBy(dx, dy);
            else                                 incidentAfter += l.lengthPx(); // both on same system → pure translate
        }

        int delta = incidentAfter - incidentBefore;
        if (delta > 0 && !sm.canAffordDelta(delta)) {
            java.lang.System.out.println("[LevelSession] moveSystem REJECT id=" + m.systemId()
                    + " delta="+delta+" used="+sm.getWireUsedPx()+" budget="+(int)sm.getWireBudgetPx());
            return;
        }


        sys.setLocation(new Point(m.x(), m.y()));
        if (delta != 0) sm.applyWireDelta(delta);
        sm.syncPortCenters(sys);
        sm.recomputeUsedWireLength();
    }

    private void useAbility(UseAbilityCmd u) {
        // Abilities are endpoint-based to locate the line
        Line line = lineByEndpoints(u.fromSystemId(), u.fromOutputIndex(), u.toSystemId(), u.toInputIndex());
        if (line == null) return;

        long now = java.lang.System.currentTimeMillis();

        // per-system cooldown (optional)
        if (!controllable.isEmpty() && !controllable.contains(u.fromSystemId())) {
            // not a controllable system → ignore (optional policy)
        }
        if (arsenal.onCooldown(u.fromSystemId(), now)) return;

        switch (u.ability()) {
            case BRING_BACK_TO_CENTER -> {
                if (!sm.spendTotalCoins(COST_CENTER_20S)) return;
                Point at = pt(u.at());
                line.addChangeCenter(at); // timing handled by Line.tickDownEffects + SystemManager.update
            }
            case ZERO_ACCEL -> {
                if (!sm.spendTotalCoins(COST_ZEROACC_20S)) return;
                Point at = pt(u.at());
                line.addZeroAccelPoint(at);
            }
            case WRATH_OF_PENIA -> {
                if (!arsenal.take(AbilityType.WRATH_OF_PENIA)) return;
                arsenal.arm(u.fromSystemId(), now);
                effects.add(new PeriodicInjector(now));
            }
            case WRATH_OF_AERGIA -> {
                if (!arsenal.take(AbilityType.WRATH_OF_AERGIA)) return;
                arsenal.arm(u.fromSystemId(), now);
                effects.add(new Aergia(now));
            }
            case SPEED_BOOST -> {
                if (!arsenal.take(AbilityType.SPEED_BOOST)) return;
                arsenal.arm(u.fromSystemId(), now);
                effects.add(new SpeedBoost(now));
            }
            default -> { /* add more as needed */ }
        }
    }

    // ===== effect implementations (local layer only) =====

    /** Injects a small packet into random controllable systems every 2s for 10s. */
    private final class PeriodicInjector implements Effect {
        private long nextMs, endMs;
        PeriodicInjector(long now) {
            this.nextMs = now; this.endMs = now + PENIA_TOTAL_MS;
        }
        @Override public boolean tick(long now, int dt) {
            if (now >= endMs) return true;
            if (now >= nextMs && !controllable.isEmpty()) {
                int idx = ThreadLocalRandom.current().nextInt(controllable.size());
                int sysId = controllable.get(idx);
                injectAtSystem(sysId, new model.packets.SquarePacket());
                nextMs += PENIA_STEP_MS;
            }
            return false;
        }
    }

    /** Gradually stretches ability cooldowns by ~1% of elapsed time over 10s. */
    private final class Aergia implements Effect {
        private final long end;
        private double carryMs = 0.0;
        Aergia(long now) { this.end = now + 10_000; }
        @Override public boolean tick(long now, int dt) {
            carryMs += dt * 0.01;
            long add = (long) carryMs;
            if (add > 0) {
                final long inc = add;
                arsenal.cooldownUntil.replaceAll((id, ts) -> ts + inc);
                carryMs -= add;
            }
            return now >= end;
        }
    }

    /** Speed boost on this layer for 10s. */
    private final class SpeedBoost implements Effect {
        private final long end;
        SpeedBoost(long now) {
            this.end = now + BOOST_MS;
            onSpeedBoostStart();
        }
        @Override public boolean tick(long now, int dt) {
            if (now >= end) { onSpeedBoostEnd(); return true; }
            return false;
        }
    }

    private void onSpeedBoostStart() { sm.startPacketSpeedBoost(BOOST_FACTOR); }
    private void onSpeedBoostEnd()   { sm.endPacketSpeedBoost(BOOST_FACTOR); }

    // ===== snapshot for clients (server → client) =====

    /** Room usually builds the outer NetSnapshotDTO; this is handy if you need a self-contained version. */
    NetSnapshotDTO toSnapshot(String roomId, RoomState state, String sideTag) {
        var info = new MatchInfoDTO(roomId, levelId, state, tick, timeLeftMs, score, 0, sideTag);
        var stateDto = mapper.Mapper.toState(sm);

        var ui = new java.util.HashMap<String,Object>();
        ui.put("side", sideTag);

        if ("A".equalsIgnoreCase(sideTag)) {
            ui.put("readyA",      sm.isReady());
            ui.put("coinsA",      sm.coinCount);
            ui.put("totalA",      sm.getTotalCoins());
            ui.put("wireUsedA",   sm.getWireUsedPx());
            ui.put("wireBudgetA", (int) sm.getWireBudgetPx());
            ui.put("hudLinesA",   hudLinesForUi());
        } else {
            ui.put("readyB",      sm.isReady());
            ui.put("coinsB",      sm.coinCount);
            ui.put("totalB",      sm.getTotalCoins());
            ui.put("wireUsedB",   sm.getWireUsedPx());
            ui.put("wireBudgetB", (int) sm.getWireBudgetPx());
            ui.put("hudLinesB",   hudLinesForUi());
        }

        return new NetSnapshotDTO(info, stateDto, ui);
    }


    /** For Room.composeSnapshot(...): return list of {fromSystemId,fromOutputIndex,toSystemId,toInputIndex,polyline:[{x,y}...]}. */
    public List<Map<String,Object>> hudLinesForUi() {
        var list = new ArrayList<Map<String,Object>>();
        for (Line l : sm.allLines) {
            var poly = new ArrayList<Map<String,Integer>>();
            for (var p : orthoPolyline(l)) poly.add(Map.of("x", p.x, "y", p.y));

            int fromSys = l.getStart().getParentSystem().getId();
            int toSys   = l.getEnd().getParentSystem().getId();
            int fromOut = l.getStart().getParentSystem().getOutputPorts().indexOf(l.getStart());
            int toIn    = l.getEnd().getParentSystem().getInputPorts().indexOf(l.getEnd());

            list.add(Map.of(
                    "fromSystemId",     fromSys,
                    "fromOutputIndex",  fromOut,
                    "toSystemId",       toSys,
                    "toInputIndex",     toIn,
                    "polyline",         poly
            ));
        }
        return list;
    }

    // ===== HUD helpers (build orthogonal polylines) =====
    private static java.awt.Point centerOf(OutputPort op) {
        var sys  = op.getParentSystem();
        var outs = sys.getOutputPorts();
        int i    = outs.indexOf(op);
        int x0   = sys.getLocation().x, y0 = sys.getLocation().y;
        return new java.awt.Point(x0 + SYS_W,
                y0 + (i + 1) * SYS_H / (outs.size() + 1));
    }
    private static java.awt.Point centerOf(InputPort ip) {
        var sys = ip.getParentSystem();
        var ins = sys.getInputPorts();
        int i   = ins.indexOf(ip);
        int x0  = sys.getLocation().x, y0 = sys.getLocation().y;
        return new java.awt.Point(x0,
                y0 + (i + 1) * SYS_H / (ins.size() + 1));
    }
    private static List<java.awt.Point> orthoPolyline(Line l) {
        var pts = new ArrayList<java.awt.Point>();
        pts.add(centerOf(l.getStart()));

        var bends = l.getBendPoints();
        if (bends != null && !bends.isEmpty()) {
            for (var b : bends) {
                pts.add(new java.awt.Point(b.getStart()));
                pts.add(new java.awt.Point(b.getMiddle()));
                pts.add(new java.awt.Point(b.getEnd()));
            }
        } else {
            // simple L (horiz then vert)
            var start = pts.get(0);
            var end   = centerOf(l.getEnd());
            pts.add(new java.awt.Point(end.x, start.y));
        }
        pts.add(centerOf(l.getEnd()));

        // coalesce collinear points
        var out = new ArrayList<java.awt.Point>();
        for (var p : pts) {
            if (out.size() < 2) { out.add(p); continue; }
            var p0 = out.get(out.size() - 2);
            var p1 = out.get(out.size() - 1);
            boolean collinear = (p0.x == p1.x && p1.x == p.x) || (p0.y == p1.y && p1.y == p.y);
            if (collinear) out.set(out.size() - 1, p); else out.add(p);
        }
        return out;
    }

    // ===== helpers =====
    private System sysById(int id) {
        for (System s : sm.getAllSystems()) if (s.getId() == id) return s;
        return null;
    }
    private Line lineByEndpoints(int sA, int outIdx, int sB, int inIdx) {
        System a = sysById(sA), b = sysById(sB);
        if (a == null || b == null) return null;

        List<OutputPort> outs = a.getOutputPorts();
        List<InputPort>  ins  = b.getInputPorts();
        if (!inRange(outIdx, outs) || !inRange(inIdx, ins)) return null;

        OutputPort op = outs.get(outIdx);
        Line line = op.getLine();
        if (line == null) return null;
        return (line.getEnd() == ins.get(inIdx)) ? line : null;
    }

    private static boolean inRange(int idx, List<?> list) { return idx >= 0 && idx < list.size(); }
    private static Point pt(PointDTO p) { return new Point(p.x(), p.y()); }

    private void injectAtSystem(int systemId, model.Packet p) {
        System s = sysById(systemId);
        if (s == null) return;
        Point c = new Point(s.getLocation().x + 1, s.getLocation().y + 1);
        p.setPoint(c);
        s.getPackets().add(p);
        sm.addPacket(p);
    }

    // getters expected by Room
    public String levelId()    { return levelId; }
    public long   tick()       { return tick; }
    public long   timeLeftMs() { return timeLeftMs; }
    public int    score()      { return score; }
    public boolean isLaunched() { return launched; }
    public boolean canLaunch()  { return !launched; }
    public boolean isReady(){return ready; }
    public boolean isLevelPassed()      { return levelPassed; }
    public int     coins()          { return score; }                  // already kept in step()
    public int     wireUsedPx()     { return sm.getWireUsedPx(); }
    public int activePackets() { return sm.allPackets.size(); }
    public PlayerStats getPlayerStats() {return playerStats; }
}
