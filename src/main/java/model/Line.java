package model;

import model.ports.InputPort;
import model.ports.OutputPort;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Wire between systems with optional quadratic Bézier bends.
 *  – Provides sampled path for movement/collision/render.
 *  – Temp effects are frame-based (no System.nanoTime() in the model).
 */
public class Line {

    // ----- endpoints -----
    private OutputPort start;
    private InputPort  end;

    // ----- occupancy -----
    private boolean isOccupied;
    private Packet  movingPacket;

    // ----- geometry (bends) -----
    private final ArrayList<BendPoint> bendPoints = new ArrayList<>(3);

    // ----- temp effects (frame-based) -----
    private static final int DURATION_FRAMES = 20 * 60; // ~20s @ 60 fps

    public static final class TimedPoint {
        public final Point p;
        public int framesLeft;
        public TimedPoint(Point p, int framesLeft) { this.p = p; this.framesLeft = framesLeft; }
    }
    public final ArrayList<TimedPoint> accelerationZero = new ArrayList<>();
    public final ArrayList<TimedPoint> getBackToCenter  = new ArrayList<>();

    // ----- cached lengths -----
    private double totalLenCache = -1; // recompute when bends/ports move

    public Line(OutputPort start, InputPort end) {
        this.start = start;
        this.end   = end;
        this.isOccupied = false;
        this.movingPacket = null;
    }

    // endpoints / occupancy
    public OutputPort getStart() { return start; }
    public InputPort  getEnd()   { return end;   }
    public boolean isOccupied()  { return isOccupied; }
    public Packet getMovingPacket() { return movingPacket; }

    public void setMovingPacket(Packet p) {
        this.movingPacket = p;
        this.isOccupied = (p != null);
        if (p != null) p.isMoving = true;
    }
    public void removeMovingPacket() {
        this.movingPacket = null;
        this.isOccupied = false;
    }

    // bends
    public List<BendPoint> getBendPoints() { return bendPoints; }

    public BendPoint addBendPoint(Point footA, Point middle, Point footB) {
        if (bendPoints.size() >= 3) throw new IllegalStateException("max 3 bends");
        if (projectionT(footA) > projectionT(footB)) { Point t = footA; footA = footB; footB = t; }

        BendPoint bp = new BendPoint(footA, middle, footB);
        bendPoints.add(bp);
        bendPoints.sort(Comparator.comparingDouble(b -> projectionT(b.getMiddle())));
        invalidateLengthCache();
        return bp;
    }
    public void removeBendPoint(BendPoint bendPoint) {
        bendPoints.remove(bendPoint);
        invalidateLengthCache();
    }
    public BendPoint getLastBend() { return bendPoints.isEmpty() ? null : bendPoints.get(bendPoints.size() - 1); }

    // sampled path
    public ArrayList<Point> getPath(int smoothness) {
        ArrayList<Point> path = new ArrayList<>();
        Point current = start.getCenter();
        path.add(current);
        smoothness = 6;
        ArrayList<BendPoint> ordered = new ArrayList<>(bendPoints);
        ordered.sort((b1, b2) -> Double.compare(projectionT(b1.getMiddle()), projectionT(b2.getMiddle())));
        for (BendPoint bp : ordered) {
            if (!current.equals(bp.getStart())) path.add(bp.getStart());
            if (smoothness > 0) path.addAll(bp.sampleCurve(smoothness));
            path.add(bp.getEnd());
            current = bp.getEnd();
        }
        if (!current.equals(end.getCenter())) path.add(end.getCenter());
        return path;
    }
    private double projectionT(Point p) {
        Point O = start.getCenter(), S = end.getCenter();
        double vx = S.x - O.x, vy = S.y - O.y;
        double wx = p.x - O.x, wy = p.y - O.y;
        double L2 = vx*vx + vy*vy;
        return L2 == 0 ? 0 : (vx*wx + vy*wy) / Math.sqrt(L2);
    }

    // temp effects (frame-based)
    public void addZeroAccelPoint(Point click) {
        Point at = closestPointOnPath(click);
        accelerationZero.add(new TimedPoint(at, DURATION_FRAMES));
    }
    public void addChangeCenter(Point click) {
        Point at = closestPointOnPath(click);
        getBackToCenter.add(new TimedPoint(at, DURATION_FRAMES));
    }
    /** Call once per sim tick. */
    public void tickDownEffects() {
        for (int i = accelerationZero.size()-1; i >= 0; i--)
            if (--accelerationZero.get(i).framesLeft <= 0) accelerationZero.remove(i);
        for (int i = getBackToCenter.size()-1; i >= 0; i--)
            if (--getBackToCenter.get(i).framesLeft <= 0) getBackToCenter.remove(i);
    }
    public boolean nearZeroAccel(Point base, float tolPx) {
        if (base == null) return false;
        for (TimedPoint z : accelerationZero) if (base.distance(z.p) <= tolPx) return true;
        return false;
    }
    public boolean nearRecenter(Point base, float tolPx) {
        if (base == null) return false;
        for (TimedPoint g : getBackToCenter) if (base.distance(g.p) <= tolPx) return true;
        return false;
    }

    // hit-testing & distance
    public boolean hit(Point p, double tol) {
        List<Point> pts = getPath(0);
        for (int i = 0; i < pts.size()-1; i++)
            if (ptToSegmentDist(p, pts.get(i), pts.get(i+1)) <= tol) return true;
        return false;
    }
    private static double ptToSegmentDist(Point p, Point a, Point b) {
        double vx = b.x - a.x, vy = b.y - a.y;
        double wx = p.x - a.x, wy = p.y - a.y;
        double len2 = vx*vx + vy*vy;
        double t = (len2==0) ? 0 : (vx*wx + vy*wy)/len2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.x + t*vx - p.x, dy = a.y + t*vy - p.y;
        return Math.hypot(dx, dy);
    }

    public float distanceAlong(Packet from, Packet to) {
        if (from.getLine() != this || to.getLine() != this) return Float.POSITIVE_INFINITY;
        float d = (to.getProgress() - from.getProgress()) * (float) totalLength();
        return Math.abs(d);
    }

    public Packet closestAhead (Packet me, float sInSeg, int segIdx){ return neighbour(me, sInSeg, segIdx, true);  }
    public Packet closestBehind(Packet me, float sInSeg, int segIdx){ return neighbour(me, sInSeg, segIdx, false); }

    private Packet neighbour(Packet me, float sInSeg, int segIdx, boolean fwd) {
        Packet best = null; float bestDist = Float.POSITIVE_INFINITY;
        List<Packet> all = start.getParentSystem().getSystemManager().allPackets;
        for (Packet p : all) {
            if (p == me || p.getLine() != this) continue;
            float dist = fwd ? distanceAlong(me, p) : distanceAlong(p, me);
            if (dist > 0 && dist < bestDist) { bestDist = dist; best = p; }
        }
        return best;
    }

    // lengths / cache
    private double totalLength() {
        if (totalLenCache < 0) {
            List<Point> pts = getPath(0);
            double sum = 0;
            for (int i = 0; i < pts.size() - 1; i++) sum += pts.get(i).distance(pts.get(i + 1));
            totalLenCache = sum;
        }
        return totalLenCache;
    }
    public void invalidateLengthCache() { totalLenCache = -1; }
    public int  lengthPx() { return lengthFromPts(getPath(6)); }

    public int lengthIfShiftStartBy(int dx, int dy) {
        List<Point> pts = new ArrayList<>(getPath(6));
        if (pts.isEmpty()) return 0;
        Point p0 = pts.get(0);
        pts.set(0, new Point(p0.x + dx, p0.y + dy));
        return lengthFromPts(pts);
    }
    public int lengthIfShiftEndBy(int dx, int dy) {
        List<Point> pts = new ArrayList<>(getPath(6));
        if (pts.isEmpty()) return 0;
        int last = pts.size() - 1;
        Point pn = pts.get(last);
        pts.set(last, new Point(pn.x + dx, pn.y + dy));
        return lengthFromPts(pts);
    }
    public static int straightLength(Point a, Point b) { return (int)Math.round(a.distance(b)); }
    private static int lengthFromPts(List<Point> pts) {
        if (pts == null || pts.size() < 2) return 0;
        double s = 0; for (int i = 0; i < pts.size()-1; i++) s += pts.get(i).distance(pts.get(i+1));
        return (int)Math.round(s);
    }

    public int  lengthPxAccurate() { return lengthPxAccurate(6); }
    public int  lengthPxAccurate(int samples) {
        if (samples < 1) samples = 1;
        ArrayList<BendPoint> ordered = new ArrayList<>(bendPoints);
        ordered.sort((b1,b2)->Double.compare(projectionT(b1.getMiddle()), projectionT(b2.getMiddle())));
        double sum = 0.0; Point current = start.getCenter();
        for (BendPoint bp : ordered) {
            if (!current.equals(bp.getStart())) sum += current.distance(bp.getStart());
            sum += quadBezierLen(bp.getStart(), bp.getMiddle(), bp.getEnd(), samples);
            current = bp.getEnd();
        }
        if (!current.equals(end.getCenter())) sum += current.distance(end.getCenter());
        return (int)Math.round(sum);
    }
    private static double quadBezierLen(Point p0, Point p1, Point p2, int samples) {
        double prevX = p0.x, prevY = p0.y, len = 0.0;
        for (int i = 1; i <= samples; i++) {
            double t = (double)i / samples, omt = 1.0 - t;
            double x = omt*omt*p0.x + 2*omt*t*p1.x + t*t*p2.x;
            double y = omt*omt*p0.y + 2*omt*t*p1.y + t*t*p2.y;
            len += Math.hypot(x - prevX, y - prevY);
            prevX = x; prevY = y;
        }
        return len;
    }

    // utilities for effects/tools
    public Point closestPointOnPath(Point click) {
        List<Point> pts = getPath(6);
        double bestD = Double.POSITIVE_INFINITY; Point best = pts.get(0);
        for (int i=0; i<pts.size()-1; i++) {
            Point a = pts.get(i), b = pts.get(i+1);
            Point proj = projectPointToSegment(click, a, b);
            double d = click.distance(proj);
            if (d < bestD) { bestD = d; best = proj; }
        }
        return best;
    }
    private static Point projectPointToSegment(Point p, Point a, Point b) {
        double dx = b.x - a.x, dy = b.y - a.y;
        if (dx == 0 && dy == 0) return new Point(a);
        double t = ((p.x - a.x)*dx + (p.y - a.y)*dy) / (dx*dx + dy*dy);
        t = Math.max(0, Math.min(1, t));
        return new Point((int)Math.round(a.x + t*dx), (int)Math.round(a.y + t*dy));
    }

    public double distanceToPath(Point p) {
        List<Point> pts = getPath(0);
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pts.size()-1; i++)
            best = Math.min(best, ptToSegmentDist(p, pts.get(i), pts.get(i+1)));
        return best;
    }
    public ArrayList<BendPoint> getBends() {return bendPoints;}
}
