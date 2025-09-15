package model.packets;

import model.Packet;
import model.Port;
import model.Type;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class BigPacket1 extends BigPacket {

    private static final float BASE_SPEED  = 2f;
    private static final float BEND_BOOST  = 1.2f;
    private static final float MAX_SPEED   = 5f;
    private static final double BEND_EPS   = Math.cos(Math.toRadians(10));

    private final int originalSize = 8;
    private final int colorId;

    private List<Point> path;
    private List<Float> segLen;
    private int   segIdx = 0;
    private float sInSeg = 0f;



    public BigPacket1(int colorId) {
        this.colorId = colorId;
        size         = originalSize;
        type         = Type.BIG1;
        speed        = BASE_SPEED;
        acceleration = 0f;
    }

    public int getOriginalSize() { return originalSize; }
    public int getColorId()      { return colorId; }

    @Override public void wrongPort(Port p) { /* ok everywhere */ }

    @Override
    public void advance(float dt) {
        if (line == null) return;
        if (path == null) initPath();

        // 1) bend boost
        acceleration = 0f;
        if (segIdx > 0 && segIdx < segLen.size() - 1) {
            Point a = path.get(segIdx - 1), b = path.get(segIdx), c = path.get(segIdx + 1);
            double ux = b.x - a.x, uy = b.y - a.y;
            double vx = c.x - b.x, vy = c.y - b.y;
            double u = Math.hypot(ux, uy), v = Math.hypot(vx, vy);
            if (u > 0 && v > 0) {
                double cos = (ux*vx + uy*vy) / (u*v);
                if (cos < BEND_EPS) acceleration = BEND_BOOST;
            }
        }

        // 2) physics + march
        speed = Math.min(speed + acceleration * dt, MAX_SPEED);
        float remaining = speed * dt;

        while (remaining > 0f && segIdx < segLen.size()) {
            float segRemain = segLen.get(segIdx) - sInSeg;
            if (remaining < segRemain) { sInSeg += remaining; remaining = 0f; }
            else { remaining -= segRemain; segIdx++; sInSeg = 0f; }
        }

        // 3) arrived
        if (segIdx >= segLen.size()) {
            isMoving = false;
            line.getEnd().getParentSystem().receivePacket(this);
            return;
        }

        // 4) base → impact
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

    public ArrayList<BitPacket> split() {
        ArrayList<BitPacket> list = new ArrayList<>();
        for (int i = 0; i < originalSize; i++) list.add(new BitPacket(this, i));
        return list;
    }

    @Override
    public List<Point> hitMapLocal() {
        int clusterR = 16, n = 8;
        ArrayList<Point> pts = new ArrayList<>(n);
        double step = 2 * Math.PI / n;
        for (int i = 0; i < n; i++) {
            double a = i * step;
            pts.add(new Point(
                    (int)Math.round(clusterR * Math.cos(a)),
                    (int)Math.round(clusterR * Math.sin(a))
            ));
        }
        return pts;
    }
}
