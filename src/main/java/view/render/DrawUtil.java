package view.render;

import model.System;
import model.systems.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.CubicCurve2D;

/** Shared, stateless drawing helpers. */
public final class DrawUtil {
    private DrawUtil() {}

    public static void drawPortShape(Graphics2D g2, model.Type t, int cx, int cy, int portSize) {
        switch (t) {
            case SQUARE -> {
                g2.setColor(Color.BLUE);
                g2.fillRect(cx - portSize / 2, cy - portSize / 2, portSize, portSize);
            }
            case TRIANGLE -> {
                g2.setColor(Color.GREEN.darker());
                int[] xs = {cx, cx - portSize / 2, cx + portSize / 2};
                int[] ys = {cy - portSize / 2, cy + portSize / 2, cy + portSize / 2};
                g2.fillPolygon(xs, ys, 3);
            }
            case INFINITY -> {
                g2.setColor(Color.MAGENTA);
                g2.setStroke(new BasicStroke(2));
                g2.draw(new CubicCurve2D.Float(cx - portSize / 2f, cy,
                        cx - portSize / 4f, cy - portSize / 2f,
                        cx + portSize / 4f, cy - portSize / 2f,
                        cx + portSize / 2f, cy));
                g2.draw(new CubicCurve2D.Float(cx - portSize / 2f, cy,
                        cx - portSize / 4f, cy + portSize / 2f,
                        cx + portSize / 4f, cy + portSize / 2f,
                        cx + portSize / 2f, cy));
            }
        }
    }

    public static Color colorFor(System s) {
        return switch (s) {
            case ReferenceSystem ignore   -> new Color(100, 149, 237);
            case NormalSystem ignore      -> new Color(144, 238, 144);
            case SpySystem ignore         -> Color.LIGHT_GRAY;
            case VpnSystem ignore         -> new Color(255, 228, 181);
            case AntiTrojanSystem ignore  -> new Color(255, 182, 193);
            case DestroyerSystem ignore   -> new Color(240, 128, 128);
            case DistributionSystem ignore-> new Color(255, 250, 205);
            case MergerSystem ignore      -> new Color(216, 191, 216);
            default -> Color.GRAY;
        };
    }

    public static Color colorForId(int id) {
        return switch (id % 6) {
            case 0 -> new Color(0xEF5350);
            case 1 -> new Color(0x42A5F5);
            case 2 -> new Color(0x66BB6A);
            case 3 -> new Color(0xFFB74D);
            case 4 -> new Color(0xAB47BC);
            default -> new Color(0x26A69A);
        };
    }

    public static void drawShield(Graphics2D g2, int cx, int cy, int r) {
        int w = r;
        int h = (int) (r * 1.4);
        int y0 = cy - r;

        Polygon s = new Polygon();
        s.addPoint(cx,        y0);
        s.addPoint(cx + w,    y0 + h/3);
        s.addPoint(cx + w,    y0 + 2*h/3);
        s.addPoint(cx,        y0 + h);
        s.addPoint(cx - w,    y0 + 2*h/3);
        s.addPoint(cx - w,    y0 + h/3);

        g2.setColor(new Color(0x5599FF));
        g2.fillPolygon(s);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2));
        g2.drawPolygon(s);
    }

    public static void drawHexagon(Graphics2D g2, int cx, int cy, int r, Color fill) {
        int side = r;
        Polygon hex = new Polygon();
        for (int i = 0; i < 6; i++) {
            double ang = Math.toRadians(60 * i - 30);
            int x = (int) Math.round(cx + side * Math.cos(ang));
            int y = (int) Math.round(cy + side * Math.sin(ang));
            hex.addPoint(x, y);
        }
        g2.setColor(fill);
        g2.fillPolygon(hex);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2));
        g2.drawPolygon(hex);
    }

    public static void drawPadlock(Graphics2D g2, int cx, int cy, int r, Color fill) {
        int bodyW = 2 * r, bodyH = 2 * r;
        int bodyX = cx - bodyW/2, bodyY = cy - r/2;
        int shackleR = r;

        g2.setColor(fill);
        g2.fillRoundRect(bodyX, bodyY, bodyW, bodyH, 4, 4);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(bodyX, bodyY, bodyW, bodyH, 4, 4);

        g2.setColor(Color.WHITE);
        Arc2D.Double arc = new Arc2D.Double(cx - shackleR, bodyY - shackleR/2.0, 2*shackleR, shackleR, 0, 180, Arc2D.OPEN);
        g2.draw(arc);
    }

    public static void drawInfinity(Graphics2D g2, int cx, int cy, int r) {
        int d = r;
        int w = r / 2;
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawOval(cx - 2 * d, cy - d, 2 * d, 2 * d);
        g2.drawOval(cx,         cy - d, 2 * d, 2 * d);
        g2.setStroke(old);
    }

    public static void badge(Graphics2D g2, int x, int y, int w, int h, String text, Color bg) {
        Color oldC = g2.getColor();
        Stroke oldS = g2.getStroke();

        g2.setColor(bg);
        g2.fillRoundRect(x, y, w, h, 10, 10);

        g2.setColor(new Color(0,0,0,40));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(x, y, w, h, 10, 10);

        g2.setColor(Color.WHITE);
        FontMetrics fm = g2.getFontMetrics();
        int tx = x + 8;
        int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2 - 1;
        g2.drawString(text, tx, ty);

        g2.setColor(oldC);
        g2.setStroke(oldS);
    }
}
