// src/main/java/server/LevelSession.java
package server;

import common.AbilityType;
import common.MatchInfoDTO;
import common.NetSnapshotDTO;
import common.PointDTO;
import common.RoomState;
import common.cmd.*;
import model.BendPoint;
import model.Line;
import model.Packet;
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

/** Authoritative match state with TWO independent layers (A and B). */
public final class LevelSession {

    // --- public meta (room will read these) ---
    private final String levelId;
    private long tick = 0;
    private long timeLeftMs = 60_000; // set by room
    private int scoreA = 0, scoreB = 0;

    // --- core models (separate per side) ---
    public final SystemManager smA;  // player A's authoritative layer
    public final SystemManager smB;  // player B's authoritative layer

    // geometry constants (keep in sync with client)
    private static final int SYS_W = 90, SYS_H = 70;

    // --- inbound intents from reader threads ---
    private record Pending(String side, ClientCommand cmd) {}
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();

    // --- “controllable systems” for each side (filled by Room) ---
    private final List<Integer> controllableA = new ArrayList<>();
    private final List<Integer> controllableB = new ArrayList<>();

    // Simple inventories (tweak starting counts as you like)
    private final Arsenal arsA = new Arsenal(3, 3, 3);
    private final Arsenal arsB = new Arsenal(3, 3, 3);

    // Timed effects
    private final List<Effect> effects = new ArrayList<>();

    // --- costs (match your UI) ---
    private static final int COST_MOVE_SYSTEM = 15;
    private static final int COST_CENTER_20S  = 10;
    private static final int COST_ZEROACC_20S = 20;

    // --- ability timing constants ---
    private static final int PENIA_TOTAL_MS = 10_000;
    private static final int PENIA_STEP_MS  = 2_000;
    private static final int AERGIA_MS      = 10_000;
    private static final int BOOST_MS       = 10_000;
    private static final float BOOST_FACTOR = 1.03f;

    private interface Effect {
        /** advance effect; return true when finished */
        boolean tick(long nowMs, int dtMs);
    }

    public LevelSession(String levelId, SystemManager a, SystemManager b, long durationMs) {
        this.levelId = levelId;
        this.smA = a;
        this.smB = b;
        this.timeLeftMs = durationMs;
    }

    // ===== API for Room to define controllables =====
    public void setControllable(List<Integer> aSide, List<Integer> bSide) {
        controllableA.clear(); controllableA.addAll(aSide);
        controllableB.clear(); controllableB.addAll(bSide);
    }

    // ===== enqueue from GameServer reader thread =====
    public void enqueue(ClientCommand cmd, String side) {
        if (cmd != null) pending.add(new Pending(side, cmd));
    }

    // ===== tick thread only =====
    public void step(int dtMs) {
        // 1) apply all enqueued commands on the sim thread
        Pending p;
        while ((p = pending.poll()) != null) {
            final String side = p.side();
            final ClientCommand c = p.cmd();
            final SystemManager sm = side.equals("A") ? smA : smB;

            try {
                switch (c) {
                    case AddLineCmd a    -> addLine(sm, a);
                    case RemoveLineCmd r -> removeLine(sm, r);
                    case AddBendCmd b    -> addBend(sm, b);
                    case MoveBendCmd m   -> moveBend(sm, m);
                    case MoveSystemCmd m -> moveSystem(sm, m);
                    case UseAbilityCmd u -> useAbility(side, u); // side-explicit
                    case ReadyCmd rdy    -> { /* room handles phase; no-op here */ }
                    case LaunchCmd l     -> sm.launchPackets();
                    default -> { /* ignore unknown */ }
                }
            } catch (Exception ex) {
               java.lang.System.out.println("[LevelSession] cmd failed: " + ex);
            }
        }

        // 2) advance authoritative simulation for BOTH layers
        smA.update(dtMs / 1000f);
        smB.update(dtMs / 1000f);
        tick++;
        timeLeftMs = Math.max(0, timeLeftMs - dtMs);

        // 3) advance active timed effects
        long now = java.lang.System.currentTimeMillis();
        for (var it = effects.iterator(); it.hasNext();) {
            if (it.next().tick(now, dtMs)) it.remove();
        }

        // 4) scoring (you can replace with a finer rule)
        scoreA = smA.coinCount;
        scoreB = smB.coinCount;
    }

    // ===== authoritative mutations (per target SystemManager) =====

    private void addLine(SystemManager sm, AddLineCmd c) {
        System sysA = sysById(sm, c.fromSystemId());
        System sysB = sysById(sm, c.toSystemId());
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
    }

    private void removeLine(SystemManager sm, RemoveLineCmd c) {
        Line line = lineByEndpoints(sm, c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
        if (line == null) return;

        // detach + remove from manager
        line.getStart().setLine(null);
        line.getEnd().setLine(null);
        sm.removeLine(line);
        sm.recomputeUsedWireLength();
    }

    private void addBend(SystemManager sm, AddBendCmd c) {
        Line line = lineByEndpoints(sm, c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
        if (line == null) return;

        if (line.getBendPoints().size() >= 3) return;

        int before = line.lengthPx();
        BendPoint added = line.addBendPoint(pt(c.footA()), pt(c.middle()), pt(c.footB()));
        int after = line.lengthPx();

        if (!sm.tryCommitLineGeometryChange(line, before, after)) {
            line.removeBendPoint(added);
        }
    }

    private void moveBend(SystemManager sm, MoveBendCmd c) {
        Line line = lineByEndpoints(sm, c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
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

    private void moveSystem(SystemManager sm, MoveSystemCmd m) {
        System sys = sysById(sm, m.systemId());
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
            else                                 incidentAfter += l.lengthPx();
        }

        int delta = incidentAfter - incidentBefore;
        if (delta > 0 && !sm.canAffordDelta(delta)) {
            return; // reject – would exceed wire budget
        }

        // accept move
        sys.setLocation(new Point(m.x(), m.y()));
        if (delta != 0) sm.applyWireDelta(delta);
        sm.recomputeUsedWireLength();
    }

    private void useAbility(String side, UseAbilityCmd u) {
        final boolean isA  = side.equals("A");
        final SystemManager my  = isA ? smA : smB;
        final SystemManager opp = isA ? smB : smA;

        // Abilities are endpoint-based to locate the line (on MY layer)
        Line line = lineByEndpoints(my, u.fromSystemId(), u.fromOutputIndex(), u.toSystemId(), u.toInputIndex());
        if (line == null) return;

        long now = java.lang.System.currentTimeMillis();

        var myArs  = isA ? arsA : arsB;
        var oppArs = isA ? arsB : arsA;
        var oppCtr = isA ? controllableB : controllableA;

        // per-system cooldown by my arsenal
        if (myArs.onCooldown(u.fromSystemId(), now)) return;

        switch (u.ability()) {
            case BRING_BACK_TO_CENTER -> {
                if (!my.spendTotalCoins(COST_CENTER_20S)) return;
                Point at = pt(u.at());
                line.addChangeCenter(at); // Line.tickDownEffects + SystemManager.update handle timing
            }
            case ZERO_ACCEL -> {
                if (!my.spendTotalCoins(COST_ZEROACC_20S)) return;
                Point at = pt(u.at());
                line.addZeroAccelPoint(at);
            }
            case WRATH_OF_PENIA -> {
                if (!myArs.take(AbilityType.WRATH_OF_PENIA)) return;
                myArs.arm(u.fromSystemId(), now);
                effects.add(new PeriodicInjector(opp, oppCtr, now)); // inject into opponent layer
            }
            case WRATH_OF_AERGIA -> {
                if (!myArs.take(AbilityType.WRATH_OF_AERGIA)) return;
                myArs.arm(u.fromSystemId(), now);
                effects.add(new Aergia(oppArs, now)); // stretch opponent cooldowns
            }
            case SPEED_BOOST -> {
                if (!myArs.take(AbilityType.SPEED_BOOST)) return;
                myArs.arm(u.fromSystemId(), now);
                effects.add(new SpeedBoost(my, now)); // boost MY layer only
            }
            default -> { /* add more as needed */ }
        }
    }

    // ===== effect implementations =====

    /** Injects a small packet into random opponent-controllable systems every 2s for 10s. */
    private final class PeriodicInjector implements Effect {
        private final SystemManager targetSm;
        private final List<Integer> targets;
        private long nextMs, endMs;
        PeriodicInjector(SystemManager targetSm, List<Integer> targets, long now) {
            this.targetSm = targetSm;
            this.targets = targets;
            this.nextMs = now; this.endMs = now + PENIA_TOTAL_MS;
        }
        @Override public boolean tick(long now, int dt) {
            if (now >= endMs) return true;
            if (now >= nextMs && !targets.isEmpty()) {
                int idx = ThreadLocalRandom.current().nextInt(targets.size());
                int sysId = targets.get(idx);
                injectAtSystem(targetSm, sysId, new model.packets.SquarePacket());
                nextMs += PENIA_STEP_MS;
            }
            return false;
        }
    }

    /** Gradually stretches opponent ability cooldowns by ~1% of elapsed time over 10s. */
    private final class Aergia implements Effect {
        private final Arsenal opp;
        private final long end;
        private double carryMs = 0.0;
        Aergia(Arsenal opp, long now) { this.opp = opp; this.end = now + AERGIA_MS; }
        @Override public boolean tick(long now, int dt) {
            carryMs += dt * 0.01; // accumulate fractional ms
            long add = (long) carryMs;
            if (add > 0) {
                final long inc = add;
                opp.cooldownUntil.replaceAll((id, ts) -> ts + inc);
                carryMs -= add;
            }
            return now >= end;
        }
    }

    /** Speed boost affecting exactly one layer for 10s. */
    private final class SpeedBoost implements Effect {
        private final SystemManager target;
        private final long end;
        SpeedBoost(SystemManager target, long now) {
            this.target = target;
            this.end = now + BOOST_MS;
            onSpeedBoostStart(target);
        }
        @Override public boolean tick(long now, int dt) {
            if (now >= end) { onSpeedBoostEnd(target); return true; }
            return false;
        }
    }

    private void onSpeedBoostStart(SystemManager sm) { sm.startPacketSpeedBoost(BOOST_FACTOR); }
    private void onSpeedBoostEnd(SystemManager sm)   { sm.endPacketSpeedBoost(BOOST_FACTOR); }

    // ===== snapshot for clients (server → client) =====

    /**
     * Build a per-side snapshot. The recipient (sideTag) sees their OWN layer in stateDto.
     * HUD also includes split wire lists and budgets for A and B.
     */
    public NetSnapshotDTO toSnapshot(String roomId, RoomState state, String sideTag) {
        final boolean isA = sideTag.equals("A");
        final SystemManager mine = isA ? smA : smB;

        var info = new MatchInfoDTO(
                roomId, levelId, state, tick, timeLeftMs,
                /*scoreA*/ scoreA, /*scoreB*/ scoreB,
                sideTag
        );

        // If your client still expects StateDTO, give them their own layer.
        var stateDto = mapper.Mapper.toState(mine);

        var ui = new java.util.HashMap<String, Object>();
        ui.put("wireUsedA",   smA.getWireUsedPx());
        ui.put("wireBudgetA", (int) smA.getWireBudgetPx());
        ui.put("wireUsedB",   smB.getWireUsedPx());
        ui.put("wireBudgetB", (int) smB.getWireBudgetPx());
        ui.put("hudLinesA",   hudLinesFor(smA));
        ui.put("hudLinesB",   hudLinesFor(smB));

        return new NetSnapshotDTO(info, stateDto, ui);
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

    private List<Map<String, Object>> hudLinesFor(SystemManager sm) {
        var list = new ArrayList<Map<String, Object>>();
        for (Line l : sm.allLines) {
            var poly = new ArrayList<Map<String, Integer>>();
            for (var p : orthoPolyline(l)) poly.add(Map.of("x", p.x, "y", p.y));

            int fromSys = l.getStart().getParentSystem().getId();
            int toSys   = l.getEnd().getParentSystem().getId();
            int fromOut = l.getStart().getParentSystem().getOutputPorts().indexOf(l.getStart());
            int toIn    = l.getEnd().getParentSystem().getInputPorts().indexOf(l.getEnd());

            list.add(Map.of(
                    "fromSystemId", fromSys,
                    "fromOutputIndex", fromOut,
                    "toSystemId", toSys,
                    "toInputIndex", toIn,
                    "polyline", poly
            ));
        }
        return list;
    }

    /** Build a drawable polyline for a Line using its bend geometry.
     *  If the line has no bends yet, synthesize a simple orthogonal route. */
    @SuppressWarnings("unused")
    private static List<Map<String, Integer>> polylineFor(Line l) {
        var out = new ArrayList<Map<String, Integer>>();

        var bends = l.getBendPoints();
        if (bends != null && !bends.isEmpty()) {
            for (var b : bends) {
                var a = b.getStart();
                var m = b.getMiddle();
                var c = b.getEnd();
                out.add(Map.of("x", a.x, "y", a.y));
                out.add(Map.of("x", m.x, "y", m.y));
                out.add(Map.of("x", c.x, "y", c.y));
            }
            return out;
        }

        final int KICK = 8;
        var sA = l.getStart().getParentSystem();
        var sB = l.getEnd().getParentSystem();
        java.awt.Point aTL = sA.getLocation();
        java.awt.Point bTL = sB.getLocation();

        boolean goRight = bTL.x >= aTL.x;
        var footA = new java.awt.Point(aTL.x + (goRight ? +KICK : -KICK), aTL.y);
        var footB = new java.awt.Point(bTL.x - (goRight ? +KICK : -KICK), bTL.y);
        int midY  = aTL.y + (bTL.y - aTL.y) / 2;

        out.add(Map.of("x", footA.x, "y", footA.y));
        out.add(Map.of("x", footA.x, "y", midY));
        out.add(Map.of("x", footB.x, "y", midY));
        out.add(Map.of("x", footB.x, "y", footB.y));
        return out;
    }

    // ===== helpers =====

    private static System sysById(SystemManager sm, int id) {
        for (System s : sm.getAllSystems()) if (s.getId() == id) return s;
        return null;
    }

    private static Line lineByEndpoints(SystemManager sm, int sA, int outIdx, int sB, int inIdx) {
        System a = sysById(sm, sA), b = sysById(sm, sB);
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

    private static void injectAtSystem(SystemManager sm, int systemId, Packet p) {
        System s = sysById(sm, systemId);
        if (s == null) return;
        Point c = new Point(s.getLocation().x + 1, s.getLocation().y + 1);
        p.setPoint(c);
        s.getPackets().add(p);
        sm.addPacket(p);
    }

    // getters
    public String levelId()    { return levelId; }
    public long   tick()       { return tick; }
    public long   timeLeftMs() { return timeLeftMs; }
    public int    scoreA()     { return scoreA; }
    public int    scoreB()     { return scoreB; }
}
