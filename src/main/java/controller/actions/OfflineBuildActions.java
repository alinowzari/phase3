// src/main/java/controller/actions/OfflineBuildActions.java
package controller.actions;

import common.AbilityType;
import common.PointDTO;
import model.BendPoint;
import model.Line;
import model.SystemManager;
import model.ports.InputPort;
import model.ports.OutputPort;

public final class OfflineBuildActions implements BuildActions {
    private final SystemManager sm;

    public OfflineBuildActions(SystemManager sm) { this.sm = sm; }

    @Override public OpResult tryAddLine(int fs, int fo, int ts, int ti) {
        var sysA = sm.getSystemById(fs);
        var sysB = sm.getSystemById(ts);
        if (sysA == null || sysB == null) return OpResult.INVALID;
        OutputPort out = sysA.getOutputPorts().get(fo);
        InputPort  in  = sysB.getInputPorts().get(ti);
        if (!sm.canCreateWire(out, in)) return OpResult.OVER_BUDGET;
        Line w = new Line(out, in);
        out.setLine(w); in.setLine(w);
        sm.addLine(w);
        return OpResult.OK;
    }

    @Override public OpResult tryRemoveLine(int fs, int fo, int ts, int ti) {
        var sysA = sm.getSystemById(fs); var sysB = sm.getSystemById(ts);
        if (sysA == null || sysB == null) return OpResult.INVALID;
        OutputPort out = sysA.getOutputPorts().get(fo);
        InputPort  in  = sysB.getInputPorts().get(ti);
        Line l = (out != null ? out.getLine() : null);
        if (l == null || l.getEnd() != in) return OpResult.INVALID;
        out.setLine(null); in.setLine(null);
        sm.removeLine(l);
        return OpResult.OK;
    }

    @Override public OpResult tryAddBend(int fs, int fo, int ts, int ti, PointDTO a, PointDTO m, PointDTO b) {
        Line l = line(fs, fo, ts, ti);
        if (l == null) return OpResult.INVALID;
        int before = l.lengthPx();
        BendPoint added;
        try { added = l.addBendPoint(new java.awt.Point(a.x(), a.y()),
                new java.awt.Point(m.x(), m.y()),
                new java.awt.Point(b.x(), b.y())); }
        catch (Exception ex) { return OpResult.INVALID; }
        l.invalidateLengthCache();
        int delta = l.lengthPx() - before;
        if (delta > 0 && !sm.canAffordDelta(delta)) {
            l.removeBendPoint(added); l.invalidateLengthCache();
            return OpResult.OVER_BUDGET;
        }
        if (delta != 0) sm.applyWireDelta(delta);
        return OpResult.OK;
    }

    @Override public OpResult tryMoveBend(int fs, int fo, int ts, int ti, int idx, PointDTO newM) {
        Line l = line(fs, fo, ts, ti);
        if (l == null) return OpResult.INVALID;
        if (idx < 0 || idx >= l.getBendPoints().size()) return OpResult.INVALID;
        var bp = l.getBendPoints().get(idx);
        var old = new java.awt.Point(bp.getMiddle());
        int before = l.lengthPx();
        bp.setMiddle(new java.awt.Point(newM.x(), newM.y()));
        l.invalidateLengthCache();
        int delta = l.lengthPx() - before;
        if (delta > 0 && !sm.canAffordDelta(delta)) {
            bp.setMiddle(old); l.invalidateLengthCache();
            return OpResult.OVER_BUDGET;
        }
        if (delta != 0) sm.applyWireDelta(delta);
        return OpResult.OK;
    }

    @Override public OpResult tryMoveSystem(int id, int x, int y) {
        var sys = sm.getSystemById(id); if (sys == null) return OpResult.INVALID;
        var old = sys.getLocation();
        sys.setLocation(new java.awt.Point(x, y));
        int delta = sm.incidentLengthDeltaForMove(sys, x - old.x, y - old.y);
        if (delta > 0 && !sm.canAffordDelta(delta)) {
            sys.setLocation(old); return OpResult.OVER_BUDGET;
        }
        if (delta != 0) sm.applyWireDelta(delta);
        return OpResult.OK;
    }

    @Override public OpResult useAbility(AbilityType a, int fs, int fo, int ts, int ti, PointDTO at) {
        // local effects if you have them; otherwise reject (abilities are usually server-authoritative)
        return OpResult.REJECTED;
    }

    private Line line(int fs, int fo, int ts, int ti) {
        var sa = sm.getSystemById(fs); var sb = sm.getSystemById(ts);
        if (sa == null || sb == null) return null;
        var out = sa.getOutputPorts().get(fo);
        var in  = sb.getInputPorts().get(ti);
        var l = out != null ? out.getLine() : null;
        return (l != null && l.getEnd() == in) ? l : null;
    }
}
