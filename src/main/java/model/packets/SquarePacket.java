package model.packets;

import model.Packet;
import model.Port;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static model.Type.SQUARE;

public class SquarePacket extends Packet implements MessengerTag {

    private List<Point> path;
    private List<Float> segLen;
    private int   segIdx = 0;
    private float sInSeg = 0f;



    public SquarePacket() {
        size  = 2;
        speed = 3f;
        type  = SQUARE;
    }

    @Override public void wrongPort(Port p) {
        if (p.getType() != SQUARE) speed /= 2f;
    }

    @Override
    public void advance(float dt) {
        if (line == null) return;
        if (path == null) initialisePath();
        if (path.size() < 2) return;

        // 1) physics
        speed += acceleration * dt;
        float remaining = speed * dt;

        // 2) march
        while (remaining > 0f && segIdx < segLen.size()) {
            float segRemain = segLen.get(segIdx) - sInSeg;
            if (remaining < segRemain) { sInSeg += remaining; remaining = 0f; }
            else { remaining -= segRemain; segIdx++; sInSeg = 0f; }
        }

        // 3) arrival
        if (segIdx >= segLen.size()) {
            line.getEnd().getParentSystem().receivePacket(this);
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

    @Override public void resetCenterDrift() {
        if (basePoint != null) point = basePoint;
        impactDX = impactDY = 0f; impactVX = impactVY = 0f;
    }

    @Override public int collisionRadius() { return 12; }

    @Override
    public List<Point> hitMapLocal() {
        int r = 8;
        ArrayList<Point> pts = new ArrayList<>(4);
        pts.add(new Point(-r, -r));
        pts.add(new Point( r, -r));
        pts.add(new Point( r,  r));
        pts.add(new Point(-r,  r));
        return pts;
    }
}
