// src/main/java/view/GamePanel.java
package view;

import common.dto.StateDTO;
import model.*;
import model.System;
import model.packets.*;
import model.ports.InputPort;
import model.ports.OutputPort;
import model.systems.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.CubicCurve2D;
import java.util.List;



/**
 * Pure canvas. On each repaint it:
 *   • loops over model.getAllLines()  → draws every wire
 *   • loops over model.getAllSystems()→ draws every box & its ports
 * The controller mutates the model and then simply calls repaint().
 */
public class GamePanel extends JPanel {

    /* ---------- constants shared with System box drawing ---------- */
    private static final int SYS_W = 90, SYS_H = 70, PORT = 12, RND = 16;
    private static final int PACKET_R = 8;
    private static final int BIG_CLUSTER_R = 16;
    private volatile StateDTO snapshot;
    private final SystemManager model;
    private final JLabel statusLabel = new JLabel("Ready: false");
    private final JLabel coinLabel = new JLabel("Coins: 0");
    private final JLabel totalLabel = new JLabel("Total: 0");
    /* dashed rubber-band preview during drag */
    private Point previewA, previewB;
    private Point hMid, hA, hB;

    public GamePanel(SystemManager model) {
        this.model = model;
        setBackground(Color.WHITE);
        setLayout(null);
        statusLabel.setBounds(10, 10, 120, 20);
        coinLabel.setBounds(10, 30, 120, 20);
        totalLabel.setBounds(10, 50, 120, 20);
        add(totalLabel);
        add(statusLabel);
        add(coinLabel);
    }

    /* ========== preview helpers called by controller ========== */
    public void showPreview(Point a, Point b) {
        previewA = a;
        previewB = b;
        repaint();
    }

    public void hidePreview() {
        previewA = previewB = null;
        repaint();
    }

    //    public SystemManager getModel() { return model; }
    public void setHandles(Point mid, Point a, Point b) {
        this.hMid = mid;
        this.hA = a;
        this.hB = b;
        repaint();
    }

    public void clearHandles() {
        hMid = hA = hB = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        var s = snapshot;
        if(s == null) {return;}
        List<System> systems = new java.util.ArrayList<>(model.getAllSystems());
        List<Line> lines   = new java.util.ArrayList<>(model.allLines);
        List<Packet> packets = new java.util.ArrayList<>(model.allPackets);
        statusLabel.setText("Ready: " + model.isReady());
        coinLabel.setText("Coins: " + model.coinCount);
        String totalTxt = "Total: " + model.getTotalCoins();
        totalLabel.setText(totalTxt);
        int w = totalLabel.getPreferredSize().width;
        totalLabel.setBounds(getWidth() - w - 10, 10, w, 20);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int usedPx = model.getWireUsedPx();
        int capPx  = (int) model.getWireBudgetPx();

        String usedTxt = "Used: " + usedPx + " px";
        String capTxt  = "Max:  " + capPx  + " px";

        FontMetrics fmHUD = g2.getFontMetrics();
        int boxW = Math.max(fmHUD.stringWidth(usedTxt), fmHUD.stringWidth(capTxt)) + 16;
        int boxH = 18;
        int rightX = getWidth() - boxW - 10;
        int usedY  = 34;      // under the labels
        int capY   = usedY + boxH + 6;

// color-code the "Used" box vs budget
        Color usedBg;
        if (capPx <= 0) {
            usedBg = new Color(128,128,128,200);
        } else {
            double ratio = usedPx / (double) capPx;
            if (ratio <= 0.90)      usedBg = new Color(120,200,120,200);   // OK
            else if (ratio <= 1.00) usedBg = new Color(255,183,77,200);    // near limit
            else                    usedBg = new Color(229,115,115,220);   // over limit
        }
        Color capBg = new Color(45, 45, 45, 210);

        drawBadge(g2, rightX, usedY, boxW, boxH, usedTxt, usedBg);
        drawBadge(g2, rightX, capY,  boxW, boxH, capTxt,  capBg);
        /* 1 ▸ systems & ports FIRST (background) */
        for (var sys : systems) {
            drawSystem(g2, sys);
        }

        /* 2 ▸ wires ON TOP of the systems */
        if (model.allLines != null) {
            g2.setStroke(new BasicStroke(2));
            g2.setColor(Color.BLACK);
            for (Line l : lines) {
                List<Point> pts = l.getPath(6);
                for (int i = 0; i < pts.size() - 1; i++) {
                    Point a = pts.get(i), b = pts.get(i + 1);
                    g2.drawLine(a.x, a.y, b.x, b.y);
                }
            }
        }

        /* 3 ▸ dashed preview (still above wires) */
        if (previewA != null && previewB != null) {
            g2.setColor(new Color(0, 0, 0, 128));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    0, new float[]{6, 6}, 0));
            g2.drawLine(previewA.x, previewA.y, previewB.x, previewB.y);
        }

        /* 4 ▸ travelling packets – always foremost */
        for (var pkt : packets) {
            drawPacket(g2, pkt);
        }

        /* 5 ▸ bend handles */
        if (hMid != null) {
            g2.setColor(Color.YELLOW);
            g2.fillOval(hMid.x - 4, hMid.y - 4, 8, 8);
        }
        if (hA != null && hB != null) {
            g2.setColor(Color.RED);
            g2.fillOval(hA.x - 3, hA.y - 3, 6, 6);
            g2.fillOval(hB.x - 3, hB.y - 3, 6, 6);
        }
    }


    /* ---------- helpers ---------- */
    private void drawSystem(Graphics2D g2, System sys) {
        Point loc = sys.getLocation();
        int x0 = loc.x, y0 = loc.y;

        // box
        g2.setColor(colorFor(sys));
        g2.fillRoundRect(x0, y0, SYS_W, SYS_H, RND, RND);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(x0, y0, SYS_W, SYS_H, RND, RND);

        // label
        String label = sys.getClass().getSimpleName().replace("System", "");
        FontMetrics fm = g2.getFontMetrics();
        int tx = x0 + (SYS_W - fm.stringWidth(label)) / 2;
        int ty = y0 + (SYS_H + fm.getAscent()) / 2 - 4;
        g2.drawString(label, tx, ty);
        String qty = String.valueOf(sys.countPackets());
        int w = fm.stringWidth(qty);
        g2.setColor(Color.BLACK);
        g2.drawString(qty, x0 + SYS_W - w - 4, y0 + fm.getAscent());
        // ports
        paintPorts(g2, sys.getInputPorts(), x0, y0, true);
        paintPorts(g2, sys.getOutputPorts(), x0, y0, false);
        drawQueuedPackets(g2, sys, x0, y0 + SYS_H + 4);
    }

    /* ---------- draws one travelling packet ---------- */
    private void drawPacket(Graphics2D g2, Packet p) {
        if (p.getPoint() == null) return;

        int cx = p.getPoint().x, cy = p.getPoint().y;
        int r = PACKET_R;                    // fixed draw radius

        switch (p) {
            case InfinityPacket inf -> {
                g2.setColor(Color.MAGENTA);
                drawInfinity(g2, cx, cy, r);
            }
            case SquarePacket sq -> {
                g2.setColor(Color.BLUE);
                g2.fillRect(cx - r, cy - r, 2 * r, 2 * r);
            }
            case TrianglePacket tri -> {
                g2.setColor(Color.ORANGE);
                int[] xs = {cx, cx - r, cx + r};
                int[] ys = {cy - r, cy + r, cy + r};
                g2.fillPolygon(xs, ys, 3);
            }
            case BigPacket big      -> drawCircleCluster(g2, cx, cy, big.getSize(), BIG_CLUSTER_R, colorForId(big.getColorId()));
            case BitPacket bit -> {
                drawCircle(g2, cx, cy, r, colorForId(bit.getColorId()),
                        String.valueOf(bit.getFragmentIdx()));
            }
//            case ProtectedPacket<?> prot -> {
//                g2.setColor(new Color(0x5599FF));
//                g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
//                g2.setColor(Color.WHITE);
//                g2.setStroke(new BasicStroke(2));
//                g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
//            }
            case ProtectedPacket<?> prot -> drawShield(g2, cx, cy, r);
            case SecretPacket1 s1   -> drawHexagon(g2, cx, cy, r, new Color(0xFF9800));
            case SecretPacket2<?> s2   -> drawPadlock(g2, cx, cy, r, new Color(0x3F51B5));

//            case SecretPacket2<?> sec -> {
//                g2.setColor(Color.BLACK);
//                g2.fillRect(cx - r, cy - r, 2 * r, 2 * r);
//                g2.setColor(new Color(0x88FF88));
//                int pad = r / 2;
//                g2.fillRect(cx - r + pad, cy - r + pad,
//                        2 * (r - pad), 2 * (r - pad));
//            }
            default -> {
                g2.setColor(Color.GRAY);
                g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
            }
        }
    }

    private void drawCircle(Graphics2D g2, int cx, int cy, int r, Color fill, String annotation) {
        g2.setColor(fill);
        g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);

        if (annotation != null) {
            g2.setColor(Color.WHITE);
            g2.setFont(getFont().deriveFont(Font.BOLD, 8f));
            int w = g2.getFontMetrics().stringWidth(annotation);
            g2.drawString(annotation, cx - w / 2, cy + 3);
        }
    }

    private void drawInfinity(Graphics2D g2, int cx, int cy, int r) {
        int d = r;                     // horizontal radius of each loop
        int w = r / 2;                 // stroke thickness

        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // left loop
        g2.drawOval(cx - 2 * d, cy - d, 2 * d, 2 * d);
        // right loop
        g2.drawOval(cx, cy - d, 2 * d, 2 * d);

        g2.setStroke(old);             // restore
    }

    private void paintPorts(Graphics2D g2, List<? extends Port> ports, int x0, int y0, boolean inputs) {
        int n = ports.size();
        for (int i = 0; i < n; i++) {
            Port p = ports.get(i);

            int cx = x0 + (inputs ? 0 : SYS_W);
            int cy = y0 + (i + 1) * SYS_H / (n + 1);

            /* ✱ NEW: keep model in sync with where we actually draw ✱ */
            p.setCenter(new Point(cx, cy));

            drawShape(g2, p.getType(), cx, cy);
        }
    }
private void drawQueuedPackets(Graphics2D g2, System sys, int startX, int baseY) {
    List<Packet> queue = new java.util.ArrayList<>(sys.getPackets());
    int gap  = 8;   // horizontal spacing
    int size = 6;   // mini‐icon “radius”
    int x    = startX;

    for (Packet p : queue) {
        if (p instanceof InfinityPacket) {
            g2.setColor(Color.MAGENTA);
            g2.drawOval(x - 2*size, baseY - size, 2*size, 2*size);
            g2.drawOval(x,          baseY - size, 2*size, 2*size);

        }
        else if (p instanceof SquarePacket) {
            g2.setColor(Color.BLUE);
            g2.fillRect(x - size, baseY - size, 2*size, 2*size);

        }
        else if (p instanceof TrianglePacket) {
            g2.setColor(Color.ORANGE);
            int[] xs = { x, x - size, x + size };
            int[] ys = { baseY - size, baseY + size, baseY + size };
            g2.fillPolygon(xs, ys, 3);

        }
        else if (p instanceof BigPacket big) {
            drawCircleCluster(
                    g2, x, baseY,
                    big.getSize(),    // number of dots
                    2*size,           // cluster radius
                    colorForId(big.getColorId())
            );

        }
        else if (p instanceof BitPacket bit) {
            drawCircle(
                    g2, x, baseY,
                    size,
                    colorForId(bit.getColorId()),
                    String.valueOf(bit.getFragmentIdx())
            );

        }
        else if (p instanceof ProtectedPacket<?>) {
            drawShield(g2, x, baseY, size);

        }
        else if (p instanceof SecretPacket1) {
            drawHexagon(g2, x, baseY, size, new Color(0xFF9800));

        }
        else if (p instanceof SecretPacket2<?>) {
            drawPadlock(g2, x, baseY, size, new Color(0x3F51B5));

        }
        else {
            g2.setColor(Color.GRAY);
            g2.fillOval(x - size, baseY - size, 2*size, 2*size);
        }

        x += 2*size + gap;
    }
}

    private void drawShape(Graphics2D g2, Type t, int cx, int cy) {
        switch (t) {
            case SQUARE -> {
                g2.setColor(Color.BLUE);
                g2.fillRect(cx - PORT / 2, cy - PORT / 2, PORT, PORT);
            }
            case TRIANGLE -> {
                g2.setColor(Color.GREEN.darker());
                int[] xs = {cx, cx - PORT / 2, cx + PORT / 2};
                int[] ys = {cy - PORT / 2, cy + PORT / 2, cy + PORT / 2};
                g2.fillPolygon(xs, ys, 3);
            }
            case INFINITY -> {
                g2.setColor(Color.MAGENTA);
                g2.setStroke(new BasicStroke(2));
                g2.draw(new CubicCurve2D.Float(cx - PORT / 2f, cy,
                        cx - PORT / 4f, cy - PORT / 2f,
                        cx + PORT / 4f, cy - PORT / 2f,
                        cx + PORT / 2f, cy));
                g2.draw(new CubicCurve2D.Float(cx - PORT / 2f, cy,
                        cx - PORT / 4f, cy + PORT / 2f,
                        cx + PORT / 4f, cy + PORT / 2f,
                        cx + PORT / 2f, cy));
            }
        }
    }

    private static Color colorForId(int id) {
        return switch (id % 6) {
            case 0 -> new Color(0xEF5350);   // red
            case 1 -> new Color(0x42A5F5);   // blue
            case 2 -> new Color(0x66BB6A);   // green
            case 3 -> new Color(0xFFB74D);   // orange
            case 4 -> new Color(0xAB47BC);   // purple
            default -> new Color(0x26A69A);  // teal
        };
    }

    private static Color colorFor(System s) {
        return switch (s) {
            case ReferenceSystem ignore -> new Color(100, 149, 237);
            case NormalSystem ignore -> new Color(144, 238, 144);
            case SpySystem ignore -> Color.LIGHT_GRAY;
            case VpnSystem ignore -> new Color(255, 228, 181);
            case AntiTrojanSystem ignore -> new Color(255, 182, 193);
            case DestroyerSystem ignore -> new Color(240, 128, 128);
            case DistributionSystem ignore -> new Color(255, 250, 205);
            case MergerSystem ignore -> new Color(216, 191, 216);
            default -> Color.GRAY;
        };
    }

    private void drawCircleCluster(Graphics2D g2, int cx, int cy, int count, int clusterR, Color fill) {
        int dotR = 4;
        double step = 2 * Math.PI / count;
        g2.setColor(fill);
        for (int i = 0; i < count; i++) {
            double ang = i * step;
            int dx = (int) Math.round(clusterR * Math.cos(ang));
            int dy = (int) Math.round(clusterR * Math.sin(ang));
            g2.fillOval(cx + dx - dotR, cy + dy - dotR, 2 * dotR, 2 * dotR);
        }
    }
    private void drawShield(Graphics2D g2, int cx, int cy, int r) {
        int w = r;
        int h = (int) (r * 1.4);
        int x0 = cx - w;
        int y0 = cy - r;

        Polygon s = new Polygon();
        // top
        s.addPoint(cx,        y0);
        // upper-right
        s.addPoint(cx + w,    y0 + h/3);
        // lower-right
        s.addPoint(cx + w,    y0 + 2*h/3);
        // bottom tip
        s.addPoint(cx,        y0 + h);
        // lower-left
        s.addPoint(cx - w,    y0 + 2*h/3);
        // upper-left
        s.addPoint(cx - w,    y0 + h/3);

        // fill
        g2.setColor(new Color(0x5599FF));
        g2.fillPolygon(s);

        // outline
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2));
        g2.drawPolygon(s);
    }

    private void drawHexagon(Graphics2D g2, int cx, int cy, int r, Color fill) {
        int side = r;
        Polygon hex = new Polygon();
        for (int i = 0; i < 6; i++) {
            double ang = Math.toRadians(60 * i - 30); // flat top
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
    private void drawPadlock(Graphics2D g2, int cx, int cy, int r, Color fill) {
        int bodyW = 2 * r, bodyH = 2 * r;
        int bodyX = cx - bodyW/2, bodyY = cy - r/2;
        int shackleR = r; // radius of shackle arc

        // body
        g2.setColor(fill);
        g2.fillRoundRect(bodyX, bodyY, bodyW, bodyH, 4, 4);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(bodyX, bodyY, bodyW, bodyH, 4, 4);

        // shackle
        g2.setColor(Color.WHITE);
        Arc2D.Double arc = new Arc2D.Double(cx - shackleR, bodyY - shackleR/2.0, 2*shackleR, shackleR, 0, 180, Arc2D.OPEN);
        g2.draw(arc);
    }
    private static void drawBadge(Graphics2D g2, int x, int y, int w, int h, String text, Color bg) {
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
    public void setSnapshot(common.dto.StateDTO s) { this.snapshot = s; repaint(); }
}
