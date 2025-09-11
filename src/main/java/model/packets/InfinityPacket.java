package model.packets;

import model.Packet;
import model.Port;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static model.Type.INFINITY;

public class InfinityPacket extends Packet implements MessengerTag {

    private List<Point> path;
    private List<Float> segLen;
    private int   segIdx  = 0;
    private float sInSeg  = 0f;
    private final float maxSpeed = 5f;



    public InfinityPacket() {
        size         = 1;
        speed        = 3f;
        acceleration = 0.3f;
        type         = INFINITY;
    }

    @Override public void wrongPort(Port p) {
        if (p.getType() != INFINITY) acceleration = -0.1f;
    }

    @Override
    public void advance(float dt) {
        if (line == null) { isMoving = false; return; }
        if (path == null) initialisePath();
        if (path.size() < 2) return;

        // 1) physics (can go negative)
        speed += acceleration * dt;
        speed = Math.max(-maxSpeed, Math.min(maxSpeed, speed));
        float remaining = speed * dt;

        // 2) march both directions
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

        // 3) off either end → deliver
        if (segIdx >= segLen.size()) {
            line.getEnd().getParentSystem().receivePacket(this);
            return;
        }
        if (segIdx < 0) {
            line.getStart().getParentSystem().receivePacket(this);
            return;
        }

        // 4) base → impact
        updateBasePoint();
        this.point = composeImpact(basePoint, dt);
    }

    private void initialisePath() {
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

    @Override public int collisionRadius() { return 12; }

    @Override public void resetCenterDrift() {
        if (basePoint != null) point = basePoint;
        impactDX = impactDY = 0f; impactVX = impactVY = 0f;
    }

    @Override
    public List<Point> hitMapLocal() {
        int r = 8, d = r;
        ArrayList<Point> pts = new ArrayList<>(16);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            int ox = (int)Math.round(r * Math.cos(a));
            int oy = (int)Math.round(r * Math.sin(a));
            pts.add(new Point(-d + ox, oy));
            pts.add(new Point( d + ox, oy));
        }
        return pts;
    }
}
