package view.render;

import common.LineDTO;
import common.PointDTO;
import common.StateDTO;
import model.Line;
import model.SystemManager;

import java.awt.*;
import java.util.List;
import java.util.Map;

/** Draws wires from HUD → DTO → model fallback. */
public final class WireRenderer {

    public void paint(Graphics2D g2, SystemManager model, StateDTO snapshot, Map<String, Object> uiData) {
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g2.setColor(Color.BLACK);

        // 1) Prefer HUD polylines if provided
        if (drawFromHud(g2, uiData)) return;

        // 2) Next: DTO lines with explicit path
        if (snapshot != null && snapshot.lines() != null) {
            for (LineDTO ld : snapshot.lines()) drawDtoPath(g2, ld.path());
            return;
        }

        // 3) Fallback: model geometry
        if (model != null) {
            for (Line l : model.getAllLines()) drawModelPath(g2, l.getPath(6));
        }
    }

    private static boolean drawFromHud(Graphics2D g2, Map<String, Object> uiData) {
        if (uiData == null) return false;
        Object hud = uiData.get("hudLines");
        if (!(hud instanceof List<?> list)) return false;

        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> m)) continue;
            Object poly = m.get("polyline");
            if (!(poly instanceof List<?> pts)) continue;

            Integer lastX = null, lastY = null;
            for (Object pObj : pts) {
                int x, y;
                if (pObj instanceof PointDTO pd) {
                    x = pd.x(); y = pd.y();
                } else if (pObj instanceof Map<?, ?> pm) {
                    Object xo = pm.get("x"), yo = pm.get("y");
                    if (!(xo instanceof Number) || !(yo instanceof Number)) continue;
                    x = ((Number) xo).intValue();
                    y = ((Number) yo).intValue();
                } else {
                    continue;
                }
                if (lastX != null) g2.drawLine(lastX, lastY, x, y);
                lastX = x; lastY = y;
            }
        }
        return true;
    }

    private static void drawDtoPath(Graphics2D g2, List<PointDTO> path) {
        if (path == null || path.size() < 2) return;
        for (int i = 0; i < path.size() - 1; i++) {
            PointDTO a = path.get(i), b = path.get(i + 1);
            g2.drawLine(a.x(), a.y(), b.x(), b.y());
        }
    }

    private static void drawModelPath(Graphics2D g2, List<Point> pts) {
        if (pts == null || pts.size() < 2) return;
        for (int i = 0; i < pts.size() - 1; i++) {
            Point a = pts.get(i), b = pts.get(i + 1);
            g2.drawLine(a.x, a.y, b.x, b.y);
        }
    }
}
