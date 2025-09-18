// view/render/Pickers.java
package view.render;

import common.LineDTO;
import common.PointDTO;
import common.StateDTO;

import java.awt.*;
import java.util.List;

public final class Pickers {

    private static final int SYS_W = 90, SYS_H = 70;

    private Pickers() {}

    public static LineDTO pickLineAt(StateDTO s, Point p, int tolPx) {
        if (s == null || s.lines() == null) return null;
        for (LineDTO ld : s.lines()) {
            List<PointDTO> path = ld.path();
            if (path == null) continue;
            for (int i = 0; i < path.size() - 1; i++) {
                PointDTO a = path.get(i), b = path.get(i + 1);
                if (ptToSegDist(p, a, b) <= tolPx) return ld;
            }
        }
        return null;
    }

    // NEW: include ids and screen coords
    public record PortHit(int systemId, int portIndex, int x, int y) {}

    public static PortHit pickPortAt(StateDTO s, Point p, boolean input) {
        if (s == null || s.systems() == null) return null;
        for (var sd : s.systems()) {
            int x0 = sd.x(), y0 = sd.y();
            int n  = input ? sd.inputs() : sd.outputs();
            for (int i = 0; i < n; i++) {
                int cx = x0 + (input ? 0 : SYS_W);
                int cy = y0 + (i + 1) * SYS_H / (n + 1);
                if (p.distance(cx, cy) <= 8) return new PortHit(sd.id(), i, cx, cy);
            }
        }
        return null;
    }

    private static double ptToSegDist(Point p, PointDTO a, PointDTO b) {
        double vx = b.x() - a.x(), vy = b.y() - a.y();
        double wx = p.x - a.x(),   wy = p.y - a.y();
        double len2 = vx*vx + vy*vy;
        double t = (len2 == 0) ? 0 : (vx*wx + vy*wy) / len2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.x() + t*vx - p.x, dy = a.y() + t*vy - p.y;
        return Math.hypot(dx, dy);
    }
}
