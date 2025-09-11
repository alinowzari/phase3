package model.packets;

import model.Packet;
import model.SystemManager;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static model.Type.OTHERS;

public final class SecretPacket2<P extends Packet & MessengerTag> extends Packet {

    private static final float BASE_SPEED = 2.0f;
    private static final float SAFE_GAP   = 25f;

    private final ProtectedPacket<P> inner;
    private int systemId;

    private List<Point> path;
    private List<Float> segLen;
    private int   segIdx  = 0;
    private float sInSeg  = 0f;
    private int   dir     = +1;



    public SecretPacket2(ProtectedPacket<P> inner) {
        this.inner = inner;
        this.type  = OTHERS;
        this.size  = 6;
        this.speed = BASE_SPEED;
        this.acceleration = 0f;
    }

    public ProtectedPacket<P> unwrap() { return inner; }
    public void wrongPort(model.Port p) { /* inert */ }
    public void setSystemId(int id) { systemId = id; }
    public int  getSystemId()       { return systemId; }

    @Override
    public void advance(float dt) {
        if (line == null) return;
        if (path == null) initPath();
        if (path.size() < 2) return;

        /* protection-zone scan around current visual point */
        double avgX = 0, avgY = 0; int hits = 0;
        SystemManager mgr = line.getStart().getParentSystem().getSystemManager();
        Point my = (point != null ? point : basePoint);

        for (Packet other : mgr.allPackets) {
            if (other == this) continue;
            Point op = other.getPoint();
            if (op == null) continue;
            if (my.distance(op) < SAFE_GAP) { avgX += op.x; avgY += op.y; hits++; }
        }

        dir = +1;
        if (hits > 0) {
            avgX /= hits; avgY /= hits;
            Point a = path.get(segIdx), b = path.get(segIdx + 1);
            double tx = b.x - a.x, ty = b.y - a.y;
            double len = Math.hypot(tx, ty);
            if (len > 0) { tx/=len; ty/=len; }
            double vx = avgX - my.x, vy = avgY - my.y;
            double dot = tx*vx + ty*vy;
            dir = (Math.abs(dot) < 1e-6) ? 0 : (dot > 0 ? -1 : +1);
        }

        float remaining = dir * BASE_SPEED * dt;

        while (remaining != 0f && segIdx >= 0 && segIdx < segLen.size()) {
            if (remaining > 0f) {
                float segRemain = segLen.get(segIdx) - sInSeg;
                if (remaining < segRemain) { sInSeg += remaining; remaining = 0f; }
                else { remaining -= segRemain; segIdx++; sInSeg = 0f; }
            } else {
                float stepBack = Math.min(-remaining, sInSeg);
                sInSeg -= stepBack; remaining += stepBack;
                if (sInSeg == 0f && segIdx > 0) { segIdx--; sInSeg = segLen.get(segIdx); }
            }
        }

        if (segIdx >= segLen.size()) { line.getEnd().getParentSystem().receivePacket(this); isMoving=false; setLine(null); return; }
        if (segIdx < 0)              { line.getStart().getParentSystem().receivePacket(this); isMoving=false; setLine(null); return; }

        // base → impact
        updateBasePoint();
        this.point = composeImpact(basePoint, dt);
    }

    private void initPath() {
        path   = line.getPath(6);
        segLen = new ArrayList<>(Math.max(0, path.size() - 1));
        for (int i = 0; i < path.size() - 1; i++)
            segLen.add((float) path.get(i).distance(path.get(i + 1)));
        segIdx = 0; sInSeg = 0f;
        basePoint = point = path.isEmpty() ? null : path.get(0);
        isMoving  = true;
    }

    private void updateBasePoint() {
        float len = segLen.get(segIdx);
        float t   = (len == 0f) ? 0f : (sInSeg / len);
        basePoint = lerp(path.get(segIdx), path.get(segIdx + 1), t);
    }

    @Override protected void resetPath() {
        path = null; segLen = null; segIdx = 0; sInSeg = 0f; basePoint = null;
    }

    @Override public void resetCenterDrift() {
        if (basePoint != null) point = basePoint;
        impactDX = impactDY = 0f; impactVX = impactVY = 0f;
    }

    @Override
    public List<Point> hitMapLocal() {
        int r = 8;
        ArrayList<Point> pts = new ArrayList<>(7);
        int topY    = -r/2;
        int bottomY =  3*r/2;
        pts.add(new Point(-r, topY));
        pts.add(new Point( r, topY));
        pts.add(new Point( r, bottomY));
        pts.add(new Point(-r, bottomY));
        int arcY = topY - r/2;
        pts.add(new Point(-r, topY));
        pts.add(new Point( 0, arcY));
        pts.add(new Point( r, topY));
        return pts;
    }
}
