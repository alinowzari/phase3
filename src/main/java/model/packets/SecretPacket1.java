package model.packets;

import model.Packet;
import model.Port;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static model.Type.OTHERS;

public class SecretPacket1 extends Packet {

    private static final float FAST  = 2f;
    private static final float CRAWL = 1f;

    private List<Point> path;
    private List<Float> segLen;
    private int   segIdx = 0;
    private float sInSeg = 0f;



    public SecretPacket1() {
        size  = 4;
        type  = OTHERS;
        speed = FAST;
        acceleration = 0f;
    }

    @Override public void wrongPort(Port p) { /* inert */ }

    @Override
    public void advance(float dt) {
        if (line == null) return;

        if (path == null) {
            initPath();
            boolean targetBusy =
                    !line.getEnd().getParentSystem().getPackets().isEmpty();
            speed = targetBusy ? CRAWL : FAST;
        }
        if (path.size() < 2) return;

        // 1) walk
        float remaining = speed * dt;
        while (remaining > 0f && segIdx < segLen.size()) {
            float segRemain = segLen.get(segIdx) - sInSeg;
            if (remaining < segRemain) { sInSeg += remaining; remaining = 0f; }
            else { remaining -= segRemain; segIdx++; sInSeg = 0f; }
        }

        // 2) done?
        if (segIdx >= segLen.size()) {
            line.getEnd().getParentSystem().receivePacket(this);
            return;
        }

        // 3) base → impact
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
    }

    private void updateBasePoint() {
        float len = segLen.get(segIdx);
        float t   = (len == 0f) ? 0f : (sInSeg / len);
        basePoint = lerp(path.get(segIdx), path.get(segIdx + 1), t);
    }

    protected void resetPath() {
        path = null; segLen = null; segIdx = 0; sInSeg = 0f; basePoint = null;
    }

    @Override public void resetCenterDrift() {
        if (basePoint != null) point = basePoint;
        impactDX = impactDY = 0f; impactVX = impactVY = 0f;
    }

    @Override
    public List<Point> hitMapLocal() {
        int r = 8;
        ArrayList<Point> pts = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            double ang = Math.toRadians(60 * i - 30); // flat top
            pts.add(new Point((int)Math.round(r * Math.cos(ang)),
                    (int)Math.round(r * Math.sin(ang))));
        }
        return pts;
    }
}
