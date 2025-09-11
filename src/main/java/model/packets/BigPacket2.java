package model.packets;

import model.Line;
import model.Packet;
import model.Port;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static model.Type.BIG;

/**
 * BigPacket2
 * - Moves along the wire like other packets (segIdx / sInSeg marching).
 * - Adds a sideways wiggle (perpendicular to the segment).
 * - Every STEP_INTERVAL pixels of arc-length, the packet permanently rises
 *   by RISE_STEP pixels in screen space (negative Y).
 */
public class BigPacket2 extends BigPacket {
    private final int originalSize = 10;
    private final int colorId;

    private List<Point> path;
    private List<Float> segLen;
    private int   segIdx = 0;
    private float sInSeg = 0f;

    private static final float WIGGLE_AMPL = 3f;
    private static final float WIGGLE_FREQ = 6f;
    private float wigglePhase = 0f;

    private static final float STEP_INTERVAL = 50f;
    private static final float RISE_STEP     = 4f;
    private float totalS = 0f, nextRiseAt = STEP_INTERVAL, verticalOffset = 0f;



    public BigPacket2(int colorId) {
        this.colorId = colorId;
        this.size    = originalSize;
        this.type    = BIG;
        this.speed   = 3f;
        this.acceleration = 0f;
    }

    public int getOriginalSize() { return originalSize; }
    public int getColorId()      { return colorId; }

    @Override public void wrongPort(Port p) { /* inert */ }

    @Override
    public void advance(float dt) {
        if (line == null) return;
        if (path == null) initialisePath();

        // 1) integrate along the wire + stairs
        float remaining = speed * dt;
        while (remaining > 0f && segIdx < segLen.size()) {
            float segRemain = segLen.get(segIdx) - sInSeg;

            if (remaining < segRemain) { sInSeg += remaining; totalS += remaining; remaining = 0f; }
            else { sInSeg = 0f; totalS += segRemain; remaining -= segRemain; segIdx++; }

            updateBasePoint();

            while (totalS >= nextRiseAt) { verticalOffset -= RISE_STEP; nextRiseAt += STEP_INTERVAL; }
        }

        // 2) arrived?
        if (segIdx >= segLen.size()) {
            isMoving = false;
            line.getEnd().getParentSystem().receivePacket(this);
            return;
        }

        // 3) decorate base (wiggle + vertical offset), then apply impact drift
        wigglePhase += WIGGLE_FREQ * dt;

        // compute wiggle vector (perp to segment)
        Point a = path.get(segIdx), b = path.get(segIdx + 1);
        double dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy);
        double nx = 0, ny = 0;
        if (len > 0) { dx/=len; dy/=len; nx = -dy; ny = dx; }

        double sway = WIGGLE_AMPL * Math.sin(wigglePhase);
        Point decoratedBase = new Point(
                (int)Math.round(basePoint.x + nx * sway),
                (int)Math.round(basePoint.y + ny * sway + verticalOffset)
        );

        this.point = composeImpact(decoratedBase, dt);
    }

    private void initialisePath() {
        path   = line.getPath(6);
        segLen = new ArrayList<>(Math.max(0, path.size() - 1));
        for (int i = 0; i < path.size() - 1; i++)
            segLen.add((float) path.get(i).distance(path.get(i + 1)));

        segIdx = 0; sInSeg = 0f;
        totalS = 0f; nextRiseAt = STEP_INTERVAL; verticalOffset = 0f;
        wigglePhase = 0f;

        basePoint = point = path.isEmpty() ? null : path.get(0);
        isMoving  = true;
    }

    private void updateBasePoint() {
        float len = segLen.get(segIdx);
        float t   = (len == 0f) ? 0f : (sInSeg / len);
        basePoint = lerp(path.get(segIdx), path.get(segIdx + 1), t);
    }

    @Override protected void resetPath() {
        path = null; segLen = null; segIdx = 0; sInSeg = 0f;
        basePoint = null;
        wigglePhase = 0f;
        totalS = 0f; nextRiseAt = STEP_INTERVAL; verticalOffset = 0f;
    }

    @Override
    public void resetCenterDrift() {
        if (basePoint != null) point = basePoint;
        impactDX = impactDY = 0f; impactVX = impactVY = 0f;
        verticalOffset = 0f; wigglePhase = 0f;
    }

    public ArrayList<BitPacket> split() {
        ArrayList<BitPacket> list = new ArrayList<>();
        for (int i = 0; i < originalSize; i++) list.add(new BitPacket(this, i));
        return list;
    }

    @Override
    public List<Point> hitMapLocal() {
        int clusterR = 16, n = 10;
        ArrayList<Point> pts = new ArrayList<>(n);
        double step = 2 * Math.PI / n;
        for (int i = 0; i < n; i++) {
            double a = i * step;
            pts.add(new Point(
                    (int) Math.round(clusterR * Math.cos(a)),
                    (int) Math.round(clusterR * Math.sin(a))
            ));
        }
        return pts;
    }
}
