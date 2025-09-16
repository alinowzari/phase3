package view;

import common.dto.*;
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
import java.awt.geom.Path2D;
import java.util.List;

import static common.dto.PacketType.*;
import  model.*;

public class GamePanel extends JPanel {
    private volatile java.util.Map<String,Object> uiData;

    private static final int SYS_W = 90, SYS_H = 70, PORT = 12, RND = 16;
    private static final int PACKET_R = 8;
    private static final int BIG_CLUSTER_R = 16;

    private volatile StateDTO snapshot;
    private final SystemManager model;

    private final JLabel statusLabel = new JLabel("Ready: false");
    private final JLabel coinLabel   = new JLabel("Coins: 0");
    private final JLabel totalLabel  = new JLabel("Total: 0");

    /* dashed rubber-band preview during drag */
    private Point previewA, previewB;
    private Point hMid, hA, hB;

    public GamePanel() { this(null); }
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
        setDoubleBuffered(true);
        setPreferredSize(new Dimension(960, 540));
    }

    /* ===== preview & handles ===== */
    public void showPreview(Point a, Point b) { previewA = a; previewB = b; repaint(); }
    public void hidePreview() { previewA = previewB = null; repaint(); }
    public void setHandles(Point mid, Point a, Point b) { this.hMid = mid; this.hA = a; this.hB = b; repaint(); }
    public void clearHandles() { hMid = hA = hB = null; repaint(); }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        final StateDTO s = snapshot;

        // ---- HUD labels (always draw; never early-return) ----  // ★ FIX
        if (model != null) {
            statusLabel.setText("Ready: " + model.isReady());
            coinLabel.setText("Coins: " + model.coinCount);
            totalLabel.setText("Total: " + model.getTotalCoins());
        } else {
            statusLabel.setText((s != null) ? "Ready: online" : "Ready: —");
            coinLabel.setText("Coins: —");
            totalLabel.setText("Total: —");
        }
        int w = totalLabel.getPreferredSize().width;
        totalLabel.setBounds(getWidth() - w - 10, 10, w, 20);

        // ---- Wire HUD (top-right badges) ----
        int usedPx, capPx;
        if (uiData != null && uiData.containsKey("wireUsed") && uiData.containsKey("wireBudget")) {
            usedPx = ((Number) uiData.get("wireUsed")).intValue();
            capPx  = ((Number) uiData.get("wireBudget")).intValue();
        } else if (model != null) {
            usedPx = model.getWireUsedPx();
            capPx  = (int) model.getWireBudgetPx();
        } else {
            usedPx = 0; capPx = 0;
        }
        drawWireHud(g2, usedPx, capPx);

        // ---- 1) Systems & ports ----
        if (s != null && s.systems() != null && !s.systems().isEmpty()) {
            for (var sd : s.systems()) drawSystemDTO(g2, sd);
        } else if (model != null) {
            for (var sys : model.getAllSystems()) drawSystem(g2, sys);
        }

        // ---- 2) Wires ----
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g2.setColor(Color.BLACK);

        if (snapshot != null && snapshot.lines() != null && !snapshot.lines().isEmpty()) {
            // ONLINE → draw the path shipped in the DTO
            for (var ld : snapshot.lines()) {
                var path = ld.path();
                if (path == null || path.size() < 2) continue;
                for (int i = 0; i < path.size() - 1; i++) {
                    var a = path.get(i);
                    var b = path.get(i + 1);
                    g2.drawLine(a.x(), a.y(), b.x(), b.y());
                }
            }
        }
        else if (model != null && model.getAllLines() != null) {
            // OFFLINE → draw the model’s polyline
            for (var l : model.getAllLines()) {
                java.util.List<java.awt.Point> pts = l.getPath(6);
                if (pts == null || pts.size() < 2) continue;
                for (int i = 0; i < pts.size() - 1; i++) {
                    var a = pts.get(i);
                    var b = pts.get(i + 1);
                    g2.drawLine(a.x, a.y, b.x, b.y);
                }
            }
        }


        // ---- 3) Dashed rubber band preview (always draw if present) ---- // ★ FIX
        if (previewA != null && previewB != null) {
            g2.setColor(new Color(0, 0, 0, 128));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0));
            g2.drawLine(previewA.x, previewA.y, previewB.x, previewB.y);
        }

        // ---- 4) Travelling packets ----
        if (s != null && s.packets() != null) {
            for (var pd : s.packets()) {
                drawPacketDTO(g2, pd);
            }
        }
        else if (model != null) {
            // Optional offline fallback if you keep travelling packets in model-level lists
            for (var sys : model.getAllSystems()) {
                for (var p : sys.getPackets()) {
                    if (p.getPoint() != null) drawPacket(g2, p);
                }
            }
        }

        // ---- 5) Bend handles ----
        if (hMid != null) { g2.setColor(Color.YELLOW); g2.fillOval(hMid.x - 4, hMid.y - 4, 8, 8); }
        if (hA != null && hB != null) {
            g2.setColor(Color.RED);
            g2.fillOval(hA.x - 3, hA.y - 3, 6, 6);
            g2.fillOval(hB.x - 3, hB.y - 3, 6, 6);
        }
    }

    /* ---------- HUD helper ---------- */
    private static void drawWireHud(Graphics2D g2, int usedPx, int capPx) {
        String usedTxt = "Used: " + usedPx + " px";
        String capTxt  = "Max:  " + capPx  + " px";

        FontMetrics fmHUD = g2.getFontMetrics();
        int boxW  = Math.max(fmHUD.stringWidth(usedTxt), fmHUD.stringWidth(capTxt)) + 16;
        int boxH  = 18;
        int right = g2.getDeviceConfiguration().getBounds().width; // not ideal; reset below
        // We want right margin from current panel width; compute using clip
        Rectangle clip = g2.getClipBounds();
        int panelW = (clip != null ? clip.width : 800);
        int x = panelW - boxW - 10;
        int usedY = 34, capY = usedY + boxH + 6;

        Color usedBg;
        if (capPx <= 0) usedBg = new Color(128,128,128,200);
        else {
            double ratio = usedPx / (double) capPx;
            if      (ratio <= 0.90) usedBg = new Color(120,200,120,200);
            else if (ratio <= 1.00) usedBg = new Color(255,183,77,200);
            else                    usedBg = new Color(229,115,115,220);
        }
        Color capBg = new Color(45, 45, 45, 210);

        drawBadge(g2, x, usedY, boxW, boxH, usedTxt, usedBg);
        drawBadge(g2, x, capY,  boxW, boxH, capTxt,  capBg);
    }

    //lime relar
    private void drawSnapshotWires(Graphics2D g2) {
        final StateDTO s = snapshot;
        if (s == null || s.lines() == null) return;

        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(Color.BLACK);

        for (LineDTO ld : s.lines()) {
            var path = ld.path();
            if (path == null || path.size() < 2) continue;

            Path2D.Float poly = new Path2D.Float();
            poly.moveTo(path.get(0).x(), path.get(0).y());
            for (int i = 1; i < path.size(); i++) {
                poly.lineTo(path.get(i).x(), path.get(i).y());
            }
            g2.draw(poly);
        }

        g2.setStroke(old);
    }

    private void drawOnlineWires(Graphics2D g2) {
        g2.setStroke(new BasicStroke(2));
        g2.setColor(Color.BLACK);

        // 2.a) Prefer server-provided HUD polylines
        Object hud = (uiData != null) ? uiData.get("hudLines") : null;
        if (hud instanceof java.util.List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof java.util.Map<?,?> m)) continue;
                Object poly = m.get("polyline");
                if (!(poly instanceof java.util.List<?> pts)) continue;

                Integer lastX = null, lastY = null;
                for (Object pObj : pts) {
                    int x, y;
                    if (pObj instanceof PointDTO pd) {
                        x = pd.x(); y = pd.y();
                    } else if (pObj instanceof java.util.Map<?,?> pm) {
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
            return; // we drew from HUD; done.
        }

        // 2.b) Fallback: draw from StateDTO.lines().path()
        final common.dto.StateDTO s = snapshot;
        if (s == null || s.lines() == null) return;

        for (var ld : s.lines()) {
            var path = ld.path();
            if (path == null || path.size() < 2) continue;
            for (int i = 0; i < path.size() - 1; i++) {
                var a = path.get(i);
                var b = path.get(i + 1);
                g2.drawLine(a.x(), a.y(), b.x(), b.y());   // ← no elbow synthesis
            }
        }
    }

    /* ---------- existing helpers (mostly unchanged) ---------- */
    private void drawSystem(Graphics2D g2, System sys) {
        Point loc = sys.getLocation();
        int x0 = loc.x, y0 = loc.y;

        g2.setColor(colorFor(sys));
        g2.fillRoundRect(x0, y0, SYS_W, SYS_H, RND, RND);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(x0, y0, SYS_W, SYS_H, RND, RND);

        String label = sys.getClass().getSimpleName().replace("System", "");
        FontMetrics fm = g2.getFontMetrics();
        int tx = x0 + (SYS_W - fm.stringWidth(label)) / 2;
        int ty = y0 + (SYS_H + fm.getAscent()) / 2 - 4;
        g2.drawString(label, tx, ty);

        String qty = String.valueOf(sys.countPackets());
        int w = fm.stringWidth(qty);
        g2.setColor(Color.BLACK);
        g2.drawString(qty, x0 + SYS_W - w - 4, y0 + fm.getAscent());

        paintPorts(g2, sys.getInputPorts(), x0, y0, true);
        paintPorts(g2, sys.getOutputPorts(), x0, y0, false);
        drawQueuedPackets(g2, sys, x0, y0 + SYS_H + 4);
    }

    private void drawPacket(Graphics2D g2, Packet p) {
        if (p.getPoint() == null) return;
        int cx = p.getPoint().x, cy = p.getPoint().y;
        int r = PACKET_R;

        switch (p) {
            case InfinityPacket inf -> {
                g2.setColor(Color.MAGENTA);
                {
                    drawInfinity(g2, cx, cy, r);
                }
            }
            case SquarePacket sq   -> {
                g2.setColor(Color.BLUE);
                g2.fillRect(cx - r, cy - r, 2 * r, 2 * r);
            }
            case TrianglePacket tri-> {
                g2.setColor(Color.ORANGE);
                int[] xs = {cx, cx - r, cx + r};
                int[] ys = {cy - r, cy + r, cy + r};
                g2.fillPolygon(xs, ys, 3);
            }
            case BigPacket big      -> drawCircleCluster(g2, cx, cy, big.getSize(), BIG_CLUSTER_R, colorForId(big.getColorId()));
            case BitPacket bit      -> drawCircle(g2, cx, cy, r, colorForId(bit.getColorId()), String.valueOf(bit.getFragmentIdx()));
            case ProtectedPacket<?> prot -> drawShield(g2, cx, cy, r);
            case SecretPacket1 s1   -> drawHexagon(g2, cx, cy, r, new Color(0xFF9800));
            case SecretPacket2<?> s2-> drawPadlock(g2, cx, cy, r, new Color(0x3F51B5));
            default -> { g2.setColor(Color.GRAY); g2.drawOval(cx - r, cy - r, 2 * r, 2 * r); }
        }
    }

    private void paintPorts(Graphics2D g2, List<? extends Port> ports, int x0, int y0, boolean inputs) {
        int n = ports.size();
        for (int i = 0; i < n; i++) {
            Port p = ports.get(i);
            int cx = x0 + (inputs ? 0 : SYS_W);
            int cy = y0 + (i + 1) * SYS_H / (n + 1);
            p.setCenter(new Point(cx, cy)); // keep model hit-tests in sync
            drawShape(g2, p.getType(), cx, cy);
        }
    }

    private void drawQueuedPackets(Graphics2D g2, System sys, int startX, int baseY) {
        List<Packet> queue = new java.util.ArrayList<>(sys.getPackets());
        int gap = 8, size = 6, x = startX;
        for (Packet p : queue) {
            if (p instanceof InfinityPacket) {
                g2.setColor(Color.MAGENTA);
                g2.drawOval(x - 2*size, baseY - size, 2*size, 2*size);
                g2.drawOval(x,         baseY - size, 2*size, 2*size);
            } else if (p instanceof SquarePacket) {
                g2.setColor(Color.BLUE);
                g2.fillRect(x - size, baseY - size, 2*size, 2*size);
            } else if (p instanceof TrianglePacket) {
                g2.setColor(Color.ORANGE);
                int[] xs = { x, x - size, x + size };
                int[] ys = { baseY - size, baseY + size, baseY + size };
                g2.fillPolygon(xs, ys, 3);
            } else if (p instanceof BigPacket big) {
                drawCircleCluster(g2, x, baseY, big.getSize(), 2*size, colorForId(big.getColorId()));
            } else if (p instanceof BitPacket bit) {
                drawCircle(g2, x, baseY, size, colorForId(bit.getColorId()), String.valueOf(bit.getFragmentIdx()));
            } else if (p instanceof ProtectedPacket<?>) {
                drawShield(g2, x, baseY, size);
            } else if (p instanceof SecretPacket1) {
                drawHexagon(g2, x, baseY, size, new Color(0xFF9800));
            } else if (p instanceof SecretPacket2<?>) {
                drawPadlock(g2, x, baseY, size, new Color(0x3F51B5));
            } else {
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
            case 0 -> new Color(0xEF5350);
            case 1 -> new Color(0x42A5F5);
            case 2 -> new Color(0x66BB6A);
            case 3 -> new Color(0xFFB74D);
            case 4 -> new Color(0xAB47BC);
            default -> new Color(0x26A69A);
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
        double step = 2 * Math.PI / Math.max(1, count);
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

    private void drawHexagon(Graphics2D g2, int cx, int cy, int r, Color fill) {
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

    private void drawPadlock(Graphics2D g2, int cx, int cy, int r, Color fill) {
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

    // ===== Online picking & DTO draw (unchanged) =====

    public LineDTO pickLineAt(Point p, int tolPx) {
        var s = snapshot;
        if (s == null || s.lines() == null) return null;
        for (var ld : s.lines()) {
            var path = ld.path();
            for (int i = 0; i < path.size() - 1; i++) {
                var a = path.get(i);
                var b = path.get(i + 1);
                if (ptToSegDist(p, a, b) <= tolPx) return ld;
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
    public record PortPick(int systemId, int portIndex, int x, int y) {}
    public PortPick pickOutputAt(Point p) {
        if (snapshot == null || snapshot.systems() == null) return null;
        for (var sd : snapshot.systems()) {
            int x0 = sd.x(), y0 = sd.y();
            int n  = sd.outputs();
            for (int i = 0; i < n; i++) {
                int cx = x0 + SYS_W;
                int cy = y0 + (i + 1) * SYS_H / (n + 1);
                if (p.distance(cx, cy) <= 8) return new PortPick(sd.id(), i, cx, cy);
            }
        }
        return null;
    }
    public PortPick pickInputAt(Point p) {
        if (snapshot == null || snapshot.systems() == null) return null;
        for (var sd : snapshot.systems()) {
            int x0 = sd.x(), y0 = sd.y();
            int n  = sd.inputs();
            for (int i = 0; i < n; i++) {
                int cx = x0;
                int cy = y0 + (i + 1) * SYS_H / (n + 1);
                if (p.distance(cx, cy) <= 8) return new PortPick(sd.id(), i, cx, cy);
            }
        }
        return null;
    }

    private void drawSystemDTO(Graphics2D g2, common.dto.SystemDTO sd) {
        int x0 = sd.x(), y0 = sd.y();

        g2.setColor(colorForDTO(sd.type()));
        g2.fillRoundRect(x0, y0, SYS_W, SYS_H, RND, RND);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(x0, y0, SYS_W, SYS_H, RND, RND);

        String label = sd.type().name().replace("System", "");
        FontMetrics fm = g2.getFontMetrics();
        int tx = x0 + (SYS_W - fm.stringWidth(label)) / 2;
        int ty = y0 + (SYS_H + fm.getAscent()) / 2 - 4;
        g2.drawString(label, tx, ty);

        paintPortsDTO(g2, sd,  x0, y0, true);
        paintPortsDTO(g2, sd, x0, y0, false);
    }


    private void drawPacketDTO(Graphics2D g2, common.dto.PacketDTO packet) {

    }
    private void paintPortsDTO(Graphics2D g2, common.dto.SystemDTO sd, int x0, int y0, boolean inputs) {
        int n = inputs ? sd.inputs() : sd.outputs();
        java.util.List<PortType> types = inputs ? sd.inputTypes() : sd.outputTypes(); // expects these on DTO
        for (int i = 0; i < n; i++) {
            int cx = x0 + (inputs ? 0 : SYS_W);
            int cy = y0 + (i + 1) * SYS_H / (n + 1);
            model.Type shape = Type.SQUARE;
            if (types != null && i < types.size() && types.get(i) != null) {
                shape = mapPortType(types.get(i));
            }
            drawShape(g2, shape, cx, cy);
        }
    }
    private static model.Type mapPortType(PortType pt) {
        return switch (pt) {
            case SQUARE   -> Type.SQUARE;
            case TRIANGLE -> Type.TRIANGLE;
            case INFINITY -> Type.INFINITY;
            default       -> Type.SQUARE;
        };
    }
    private Color colorForDTO(common.dto.SystemType t) {
        return switch (t) {
            case REFERENCE   -> new Color(100, 149, 237);
            case NORMAL      -> new Color(144, 238, 144);
            case SPY         -> Color.LIGHT_GRAY;
            case VPN         -> new Color(255, 228, 181);
            case ANTI_TROJAN -> new Color(255, 182, 193);
            case DESTROYER   -> new Color(240, 128, 128);
            case DISTRIBUTION-> new Color(255, 250, 205);
            case MERGER      -> new Color(216, 191, 216);
            default          -> Color.GRAY;
        };
    }

    public void setSnapshot(StateDTO s) { this.snapshot = s; repaint(); }
    public StateDTO getSnapshot() { return snapshot; }
    public void setWireHud(Integer used, Integer cap) { /* kept for API compatibility */ }
    public void setUiData(java.util.Map<String,Object> ui) { this.uiData = ui; repaint(); }
}
