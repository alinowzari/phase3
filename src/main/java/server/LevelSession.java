//// src/main/java/server/LevelSession.java
//package server;
//
//import common.dto.NetSnapshotDTO;
//import common.dto.MatchInfoDTO;
//import common.dto.RoomState;
//import common.dto.PointDTO;
//import common.dto.cmd.*;
//import model.BendPoint;
//import model.Line;
//import model.System;
//import model.SystemManager;
//import model.ports.InputPort;
//import model.ports.OutputPort;
//
//import java.awt.Point;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ConcurrentLinkedQueue;
//
///** One player's authoritative match state backed by your SystemManager. */
//public final class LevelSession {
//
//    // --- public meta (room will read these) ---
//    private final String levelId;
//    private long   tick          = 0;
//    private long   timeLeftMs    = 60_000;   // set by room
//    private int    score         = 0;        // use sm.coinCount if you prefer
//
//    // --- core model ---
//    public final SystemManager sm;           // your real model
//
//    // --- inbound intents from reader threads ---
//    private final ConcurrentLinkedQueue<ClientCommand> pending = new ConcurrentLinkedQueue<>();
//
//    // --- costs (match your UI) ---
//    private static final int COST_MOVE_SYSTEM   = 15;
//    private static final int COST_CENTER_20S    = 10;
//    private static final int COST_ZEROACC_20S   = 20;
//
//    public LevelSession(String levelId, SystemManager sm, long durationMs) {
//        this.levelId   = levelId;
//        this.sm        = sm;
//        this.timeLeftMs = durationMs;
//    }
//
//    // ===== enqueue from GameServer reader thread =====
//    public void enqueue(ClientCommand cmd) { if (cmd != null) pending.add(cmd); }
//
//    // ===== tick thread only =====
//    public void step(int dtMs) {
//        // 1) apply all enqueued commands on the sim thread
//        ClientCommand c;
//        while ((c = pending.poll()) != null) {
//            try {
//                switch (c) {
//                    case AddLineCmd a    -> addLine(a);
//                    case RemoveLineCmd r -> removeLine(r);
//                    case AddBendCmd b    -> addBend(b);
//                    case MoveBendCmd m   -> moveBend(m);
//                    case MoveSystemCmd m -> moveSystem(m);
//                    case UseAbilityCmd u -> useAbility(u);
//                    case ReadyCmd rdy    -> {/* hook in room if you track ready here */}
//                    case LaunchCmd l     -> sm.launchPackets();
//                    default -> { /* ignore unknown */ }
//                }
//            } catch (Exception ex) {
//                // keep server alive; log if you have a logger
//                java.lang.System.out.println("[LevelSession] cmd failed: " + ex);
//            }
//        }
//
//        // 2) advance authoritative simulation
//        sm.update(dtMs / 1000f);
//        tick++;
//        timeLeftMs = Math.max(0, timeLeftMs - dtMs);
//
//        // 3) scoring source (choose one):
//        // Option A: use model coins
//        score = sm.coinCount;
//        // Option B: compute from delivered packets, etc. if you have that hook
//    }
//
//    // ===== authoritative mutations =====
//
//    private void addLine(AddLineCmd c) {
//        System sysA = sysById(c.fromSystemId());
//        System sysB = sysById(c.toSystemId());
//        if (sysA == null || sysB == null) return;
//
//        List<OutputPort> outs = sysA.getOutputPorts();
//        List<InputPort>  ins  = sysB.getInputPorts();
//        if (!inRange(c.fromOutputIndex(), outs) || !inRange(c.toInputIndex(), ins)) return;
//
//        OutputPort op = outs.get(c.fromOutputIndex());
//        InputPort  ip = ins.get(c.toInputIndex());
//
//        // endpoints must be free
//        if (op.getLine() != null || ip.getLine() != null) return;
//
//        // type match (same check your UI does)
//        if (ip.getType() != op.getType()) return;
//
//        // budget check (straight length preview)
//        if (!sm.canCreateWire(op, ip)) return;
//
//        Line line = new Line(op, ip);
//        op.setLine(line);
//        ip.setLine(line);
//        sm.addLine(line);               // updates used length
//        sm.recomputeUsedWireLength();
//    }
//
//    private void removeLine(RemoveLineCmd c) {
//        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
//        if (line == null) return;
//
//        // detach + remove from manager
//        line.getStart().setLine(null);
//        line.getEnd().setLine(null);
//        sm.removeLine(line);
//        sm.recomputeUsedWireLength();
//    }
//
//    private void addBend(AddBendCmd c) {
//        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(),
//                c.toSystemId(),   c.toInputIndex());
//        if (line == null) return;
//
//        // don’t rely on exceptions — check capacity
//        if (line.getBendPoints().size() >= 3) return;
//
//        int before = line.lengthPx();
//
//        // add (addBendPoint already sorts + invalidates cache)
//        BendPoint added = line.addBendPoint(pt(c.footA()), pt(c.middle()), pt(c.footB()));
//
//        int after = line.lengthPx(); // will recompute because cache was invalidated
//
//        // commit wire-length change, or revert the bend
//        if (!sm.tryCommitLineGeometryChange(line, before, after)) {
//            line.removeBendPoint(added); // removeBendPoint invalidates cache again
//            // optional: log/notify rejection
//        }
//    }
//
//    private void moveBend(MoveBendCmd c) {
//        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
//        if (line == null) return;
//        var bends = line.getBendPoints();
//        if (c.bendIndex() < 0 || c.bendIndex() >= bends.size()) return;
//
//        var bend   = bends.get(c.bendIndex());
//        Point prev = new Point(bend.getMiddle());
//        int before = line.lengthPx();
//
//        bend.setMiddle(pt(c.newMiddle()));
//        line.invalidateLengthCache();
//        int after = line.lengthPx();
//
//        if (!sm.tryCommitLineGeometryChange(line, before, after)) {
//            // revert
//            bend.setMiddle(prev);
//            line.invalidateLengthCache();
//        }
//    }
//
//    private void moveSystem(MoveSystemCmd m) {
//        System sys = sysById(m.systemId());
//        if (sys == null) return;
//
//        // compute delta wire length like your ConnectionController
//        Point oldTopLeft = sys.getLocation();
//        int dx = m.x() - oldTopLeft.x;
//        int dy = m.y() - oldTopLeft.y;
//
//        int incidentBefore = 0;
//        int incidentAfter  = 0;
//
//        for (Line l : new ArrayList<>(sm.allLines)) {
//            boolean shiftsStart = sys.getOutputPorts().contains(l.getStart());
//            boolean shiftsEnd   = sys.getInputPorts().contains(l.getEnd());
//            if (!shiftsStart && !shiftsEnd) continue;
//
//            incidentBefore += l.lengthPx();
//            if (shiftsStart && !shiftsEnd)      incidentAfter += l.lengthIfShiftStartBy(dx, dy);
//            else if (!shiftsStart && shiftsEnd) incidentAfter += l.lengthIfShiftEndBy(dx, dy);
//            else                                 incidentAfter += l.lengthPx(); // both on same system → pure translate
//        }
//
//        int delta = incidentAfter - incidentBefore;
//        if (delta > 0 && !sm.canAffordDelta(delta)) {
//            return; // reject move (over wire budget)
//        }
//
//        // accept move
//        sys.setLocation(new Point(m.x(), m.y()));
//        if (delta != 0) sm.applyWireDelta(delta);
//        sm.recomputeUsedWireLength();
//    }
//
//    private void useAbility(UseAbilityCmd u) {
//        // Abilities are endpoint-based to locate the line
//        Line line = lineByEndpoints(u.fromSystemId(), u.fromOutputIndex(), u.toSystemId(), u.toInputIndex());
//        if (line == null) return;
//
//        switch (u.ability()) {
//            case BRING_BACK_TO_CENTER -> {
//                if (!sm.spendTotalCoins(COST_CENTER_20S)) return;
//                Point at = pt(u.at());
//                line.addChangeCenter(at); // 20s handled by Line.tickDownEffects + SystemManager.update
//            }
//            case ZERO_ACCEL -> {
//                if (!sm.spendTotalCoins(COST_ZEROACC_20S)) return;
//                Point at = pt(u.at());
//                line.addZeroAccelPoint(at);
//            }
//            default -> { /* add more as needed */ }
//        }
//    }
//
//    // ===== snapshot for clients (server → client) =====
//
//    public NetSnapshotDTO toSnapshot(String roomId, RoomState state, String sideTag) {
//        var info = new MatchInfoDTO(
//                roomId, levelId, state, tick, timeLeftMs,
//                /*scoreA*/ score, /*scoreB*/ 0, // room will fill the opponent score in its own snapshot
//                sideTag
//        );
//        // If you already have a mapper to StateDTO, use it here:
//        var stateDto = mapper.Mapper.toState(sm); // <-- replace with your actual mapper
//        var ui = Map.<String, Object>of(
//                "wireUsed", sm.getWireUsedPx(),
//                "wireBudget", (int) sm.getWireBudgetPx()
//        );
//        return new NetSnapshotDTO(info, stateDto, ui);
//    }
//
//    // ===== helpers =====
//
//    private System sysById(int id) {
//        for (System s : sm.getAllSystems()) if (s.getId() == id) return s;
//        return null;
//    }
//
//    private Line lineByEndpoints(int sA, int outIdx, int sB, int inIdx) {
//        System a = sysById(sA), b = sysById(sB);
//        if (a == null || b == null) return null;
//
//        List<OutputPort> outs = a.getOutputPorts();
//        List<InputPort>  ins  = b.getInputPorts();
//        if (!inRange(outIdx, outs) || !inRange(inIdx, ins)) return null;
//
//        OutputPort op = outs.get(outIdx);
//        Line line = op.getLine();
//        if (line == null) return null;
//        // ensure it connects to the specific input index on B
//        return (line.getEnd() == ins.get(inIdx)) ? line : null;
//    }
//
//    private static boolean inRange(int idx, List<?> list) {
//        return idx >= 0 && idx < list.size();
//    }
//    private static Point pt(PointDTO p) { return new Point(p.x(), p.y()); }
//
//    // getters
//    public String levelId()   { return levelId; }
//    public long   tick()      { return tick; }
//    public long   timeLeftMs(){ return timeLeftMs; }
//    public int    score()     { return score; }
//}
// src/main/java/server/LevelSession.java
package server;

import common.dto.NetSnapshotDTO;
import common.dto.MatchInfoDTO;
import common.dto.RoomState;
import common.dto.PointDTO;
import common.dto.cmd.*;
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

/** One player's authoritative match state backed by your SystemManager. */
public final class LevelSession {

    // --- public meta (room will read these) ---
    private final String levelId;
    private long   tick          = 0;
    private long   timeLeftMs    = 60_000;   // set by room
    private int    score         = 0;        // use sm.coinCount if you prefer

    // --- core model ---
    public final SystemManager sm;           // your real model

    // --- inbound intents from reader threads ---
    private final ConcurrentLinkedQueue<ClientCommand> pending = new ConcurrentLinkedQueue<>();

    // --- “controllable systems” for each side (filled by Room) ---
    private final List<Integer> controllableA = new ArrayList<>();
    private final List<Integer> controllableB = new ArrayList<>();

    // Simple inventories (tweak starting counts as you like)
    private final Arsenal arsA = new Arsenal(3, 3, 3);
    private final Arsenal arsB = new Arsenal(3, 3, 3);

    // Timed effects
    private final List<Effect> effects = new ArrayList<>();

    // --- costs (match your UI) ---
    private static final int COST_MOVE_SYSTEM   = 15;
    private static final int COST_CENTER_20S    = 10;
    private static final int COST_ZEROACC_20S   = 20;

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

    public LevelSession(String levelId, SystemManager sm, long durationMs) {
        this.levelId    = levelId;
        this.sm         = sm;
        this.timeLeftMs = durationMs;
    }

    // ===== API for Room to define controllables =====
    public void setControllables(List<Integer> aSide, List<Integer> bSide) {
        controllableA.clear(); controllableA.addAll(aSide);
        controllableB.clear(); controllableB.addAll(bSide);
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
                    case ReadyCmd rdy    -> {/* hook in room if you track ready here */}
                    case LaunchCmd l     -> sm.launchPackets();
                    default -> { /* ignore unknown */ }
                }
            } catch (Exception ex) {
                // keep server alive; log if you have a logger
                java.lang.System.out.println("[LevelSession] cmd failed: " + ex);
            }
        }

        // 2) advance authoritative simulation
        sm.update(dtMs / 1000f);
        tick++;
        timeLeftMs = Math.max(0, timeLeftMs - dtMs);

        // 3) advance effects
        long now = java.lang.System.currentTimeMillis();
        for (var it = effects.iterator(); it.hasNext(); ) {
            if (it.next().tick(now, dtMs)) it.remove();
        }

        // 4) scoring source (choose one):
        // Option A: use model coins
        score = sm.coinCount;
        // Option B: compute from delivered packets, etc. if you have that hook
    }

    // ===== authoritative mutations =====

    private void addLine(AddLineCmd c) {
        System sysA = sysById(c.fromSystemId());
        System sysB = sysById(c.toSystemId());
        if (sysA == null || sysB == null) return;

        List<OutputPort> outs = sysA.getOutputPorts();
        List<InputPort>  ins  = sysB.getInputPorts();
        if (!inRange(c.fromOutputIndex(), outs) || !inRange(c.toInputIndex(), ins)) return;

        OutputPort op = outs.get(c.fromOutputIndex());
        InputPort  ip = ins.get(c.toInputIndex());

        // endpoints must be free
        if (op.getLine() != null || ip.getLine() != null) return;

        // type match (same check your UI does)
        if (ip.getType() != op.getType()) return;

        // budget check (straight length preview)
        if (!sm.canCreateWire(op, ip)) return;

        Line line = new Line(op, ip);
        op.setLine(line);
        ip.setLine(line);
        sm.addLine(line);               // updates used length
        sm.recomputeUsedWireLength();
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
        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(),
                c.toSystemId(),   c.toInputIndex());
        if (line == null) return;

        // don’t rely on exceptions — check capacity
        if (line.getBendPoints().size() >= 3) return;

        int before = line.lengthPx();

        // add (addBendPoint already sorts + invalidates cache)
        BendPoint added = line.addBendPoint(pt(c.footA()), pt(c.middle()), pt(c.footB()));

        int after = line.lengthPx(); // will recompute because cache was invalidated

        // commit wire-length change, or revert the bend
        if (!sm.tryCommitLineGeometryChange(line, before, after)) {
            line.removeBendPoint(added); // removeBendPoint invalidates cache again
            // optional: log/notify rejection
        }
    }

    private void moveBend(MoveBendCmd c) {
        Line line = lineByEndpoints(c.fromSystemId(), c.fromOutputIndex(), c.toSystemId(), c.toInputIndex());
        if (line == null) return;
        var bends = line.getBendPoints();
        if (c.bendIndex() < 0 || c.bendIndex() >= bends.size()) return;

        var bend   = bends.get(c.bendIndex());
        Point prev = new Point(bend.getMiddle());
        int before = line.lengthPx();

        bend.setMiddle(pt(c.newMiddle()));
        line.invalidateLengthCache();
        int after = line.lengthPx();

        if (!sm.tryCommitLineGeometryChange(line, before, after)) {
            // revert
            bend.setMiddle(prev);
            line.invalidateLengthCache();
        }
    }

    private void moveSystem(MoveSystemCmd m) {
        System sys = sysById(m.systemId());
        if (sys == null) return;

        // compute delta wire length like your ConnectionController
        Point oldTopLeft = sys.getLocation();
        int dx = m.x() - oldTopLeft.x;
        int dy = m.y() - oldTopLeft.y;

        int incidentBefore = 0;
        int incidentAfter  = 0;

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
            return; // reject move (over wire budget)
        }

        // accept move
        sys.setLocation(new Point(m.x(), m.y()));
        if (delta != 0) sm.applyWireDelta(delta);
        sm.recomputeUsedWireLength();
    }

    private void useAbility(UseAbilityCmd u) {
        // Abilities are endpoint-based to locate the line
        Line line = lineByEndpoints(u.fromSystemId(), u.fromOutputIndex(), u.toSystemId(), u.toInputIndex());
        if (line == null) return;

        long now = java.lang.System.currentTimeMillis();

        // Determine which side is acting based on the FROM system
        boolean sideA = controllableA.contains(u.fromSystemId());
        var myArs  = sideA ? arsA : arsB;
        var oppArs = sideA ? arsB : arsA;
        var oppCtr = sideA ? controllableB : controllableA;

        // per-system cooldown
        if (myArs.onCooldown(u.fromSystemId(), now)) return;

        switch (u.ability()) {
            case BRING_BACK_TO_CENTER -> {
                if (!sm.spendTotalCoins(COST_CENTER_20S)) return;
                Point at = pt(u.at());
                line.addChangeCenter(at); // 20s handled by Line.tickDownEffects + SystemManager.update
            }
            case ZERO_ACCEL -> {
                if (!sm.spendTotalCoins(COST_ZEROACC_20S)) return;
                Point at = pt(u.at());
                line.addZeroAccelPoint(at);
            }
            case WRATH_OF_PENIA -> {
                if (!myArs.take(common.dto.AbilityType.WRATH_OF_PENIA)) return;
                myArs.arm(u.fromSystemId(), now);
                effects.add(new PeriodicInjector(oppCtr, now));
            }
            case WRATH_OF_AERGIA -> {
                if (!myArs.take(common.dto.AbilityType.WRATH_OF_AERGIA)) return;
                myArs.arm(u.fromSystemId(), now);
                effects.add(new Aergia(oppArs, now));
            }
            case SPEED_BOOST -> {
                if (!myArs.take(common.dto.AbilityType.SPEED_BOOST)) return;
                myArs.arm(u.fromSystemId(), now);
                effects.add(new SpeedBoost(now));
            }
            default -> { /* add more as needed */ }
        }
    }

    // ===== effect implementations =====

    /** Injects a small packet into random opponent-controllable systems every 2s for 10s. */
    private final class PeriodicInjector implements Effect {
        private final List<Integer> targets;
        private long nextMs, endMs;
        PeriodicInjector(List<Integer> targets, long now) {
            this.targets = targets;
            this.nextMs = now; this.endMs = now + PENIA_TOTAL_MS;
        }
        @Override public boolean tick(long now, int dt) {
            if (now >= endMs) return true;
            if (now >= nextMs && !targets.isEmpty()) {
                int idx = ThreadLocalRandom.current().nextInt(targets.size());
                int sysId = targets.get(idx);
                // pick any simple packet type available in your model
                injectAtSystem(sysId, new model.packets.SquarePacket());
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
        Aergia(Arsenal opp, long now) {
            this.opp = opp; this.end = now + AERGIA_MS;
        }
        @Override public boolean tick(long now, int dt) {
            // add about 1% of dt to all active cooldowns
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

    /** Placeholder speed boost for 10s (hook into SystemManager to actually scale speeds). */
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

    // NOP hooks for now; wire these into SystemManager if you add a speed scale there.
    private void onSpeedBoostStart() {
        sm.startPacketSpeedBoost(BOOST_FACTOR);
    }
    private void onSpeedBoostEnd() {
        sm.endPacketSpeedBoost(BOOST_FACTOR);
    }
    // ===== snapshot for clients (server → client) =====

//    public NetSnapshotDTO toSnapshot(String roomId, RoomState state, String sideTag) {
//        var info = new MatchInfoDTO(
//                roomId, levelId, state, tick, timeLeftMs,
//                /*scoreA*/ score, /*scoreB*/ 0, // room will fill the opponent score in its own snapshot
//                sideTag
//        );
//        // If you already have a mapper to StateDTO, use it here:
//        var stateDto = mapper.Mapper.toState(sm); // <-- replace with your actual mapper
//        var ui = Map.<String, Object>of(
//                "wireUsed",   sm.getWireUsedPx(),
//                "wireBudget", (int) sm.getWireBudgetPx(),
//                // surface ability UI (ammo + per-system cooldowns) for both sides
//                "ammoA",      new java.util.HashMap<>(arsA.ammo),
//                "ammoB",      new java.util.HashMap<>(arsB.ammo),
//                "cooldownsA", new java.util.HashMap<>(arsA.cooldownUntil),
//                "cooldownsB", new java.util.HashMap<>(arsB.cooldownUntil)
//        );
//        return new NetSnapshotDTO(info, stateDto, ui);
//    }

    public NetSnapshotDTO toSnapshot(String roomId, RoomState state, String sideTag) {
        var info = new MatchInfoDTO(roomId, levelId, state, tick, timeLeftMs, score, 0, sideTag);

        var stateDto = mapper.Mapper.toState(sm);

        var ui = java.util.Map.<String,Object>of(
                "wireUsed",   sm.getWireUsedPx(),
                "wireBudget", (int) sm.getWireBudgetPx()
        );

        return new NetSnapshotDTO(info, stateDto, ui); // match your NetSnapshotDTO ctor
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
        // ensure it connects to the specific input index on B
        return (line.getEnd() == ins.get(inIdx)) ? line : null;
    }

    private static boolean inRange(int idx, List<?> list) {
        return idx >= 0 && idx < list.size();
    }
    private static Point pt(PointDTO p) { return new Point(p.x(), p.y()); }

    private void injectAtSystem(int systemId, model.Packet p) {
        model.System s = sysById(systemId);
        if (s == null) return;
        // place near system center; sendPacket() will pick it up
        java.awt.Point c = new java.awt.Point(s.getLocation().x + 1, s.getLocation().y + 1);
        p.setPoint(c);
        s.getPackets().add(p);
        sm.addPacket(p);
    }

    public String levelId()    { return levelId; }
    public long   tick()       { return tick; }
    public long   timeLeftMs() { return timeLeftMs; }
    public int    score()      { return score; }
}
