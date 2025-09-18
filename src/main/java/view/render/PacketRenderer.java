package view.render;

import common.PacketDTO;
import common.PacketType;
import common.StateDTO;
import model.System;
import model.SystemManager;
import model.Packet;
import model.packets.*;

import java.awt.*;
import java.util.List;

/** Draws travelling and queued packets (model or DTO). */
public final class PacketRenderer {

    private static final int PACKET_R = 8;
    private static final int BIG_CLUSTER_R = 16;

    public void paint(Graphics2D g2, SystemManager model, StateDTO snapshot) {
        if (snapshot != null && snapshot.packets() != null) {
            for (PacketDTO pd : snapshot.packets()) drawDTO(g2, pd);
            return;
        }
        if (model != null) {
            // Optional offline fallback
            for (System sys : model.getAllSystems()) {
                for (Packet p : sys.getPackets()) {
                    if (p.getPoint() != null) drawModel(g2, p);
                }
            }
        }
    }

    /* ===== Model packet ===== */
    private void drawModel(Graphics2D g2, Packet p) {
        if (p.getPoint() == null) return;
        int cx = p.getPoint().x, cy = p.getPoint().y;
        int r = PACKET_R;

        if (p instanceof InfinityPacket) {
            g2.setColor(Color.MAGENTA);
            DrawUtil.drawInfinity(g2, cx, cy, r);
        } else if (p instanceof SquarePacket) {
            g2.setColor(Color.BLUE);
            g2.fillRect(cx - r, cy - r, 2 * r, 2 * r);
        } else if (p instanceof TrianglePacket) {
            g2.setColor(Color.ORANGE);
            int[] xs = {cx, cx - r, cx + r};
            int[] ys = {cy - r, cy + r, cy + r};
            g2.fillPolygon(xs, ys, 3);
        } else if (p instanceof BigPacket big) {
            drawCircleCluster(g2, cx, cy, big.getSize(), BIG_CLUSTER_R, DrawUtil.colorForId(big.getColorId()));
        } else if (p instanceof BitPacket bit) {
            drawCircle(g2, cx, cy, r, DrawUtil.colorForId(bit.getColorId()), String.valueOf(bit.getFragmentIdx()));
        } else if (p instanceof ProtectedPacket<?>) {
            DrawUtil.drawShield(g2, cx, cy, r);
        } else if (p instanceof SecretPacket1) {
            DrawUtil.drawHexagon(g2, cx, cy, r, new Color(0xFF9800));
        } else if (p instanceof SecretPacket2<?>) {
            DrawUtil.drawPadlock(g2, cx, cy, r, new Color(0x3F51B5));
        } else {
            g2.setColor(Color.GRAY);
            g2.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        }
    }

    /* ===== DTO packet (minimal; extend as needed) ===== */
    private void drawDTO(Graphics2D g2, PacketDTO pd) {
        int cx = pd.x(), cy = pd.y();
        int r = PACKET_R;
        PacketType t = pd.type();

        switch (t) {
            case INFINITY -> {
                g2.setColor(Color.MAGENTA);
                DrawUtil.drawInfinity(g2, cx, cy, r);
            }
            case SQUARE -> {
                g2.setColor(Color.BLUE);
                g2.fillRect(cx - r, cy - r, 2 * r, 2 * r);
            }
            case TRIANGLE -> {
                g2.setColor(Color.ORANGE);
                int[] xs = {cx, cx - r, cx + r};
                int[] ys = {cy - r, cy + r, cy + r};
                g2.fillPolygon(xs, ys, 3);
            }
            case BIG1, BIG2 -> drawCircleCluster(g2, cx, cy, /*count*/6, BIG_CLUSTER_R, Color.DARK_GRAY);
            case PROTECTED -> DrawUtil.drawShield(g2, cx, cy, r);
            case SECRET1   -> DrawUtil.drawHexagon(g2, cx, cy, r, new Color(0xFF9800));
            case SECRET2   -> DrawUtil.drawPadlock(g2, cx, cy, r, new Color(0x3F51B5));
            case BIT, UNKNOWN -> {
                g2.setColor(Color.GRAY);
                g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
            }
        }
    }

    /* ==== shared mini helpers (cluster & small circle with annotation) ==== */
    static void drawQueuedPackets(Graphics2D g2, System sys, int startX, int baseY) {
        List<model.Packet> queue = new java.util.ArrayList<>(sys.getPackets());
        int gap = 8, size = 6, x = startX;
        for (model.Packet p : queue) {
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
                drawCircleCluster(g2, x, baseY, big.getSize(), 2*size, DrawUtil.colorForId(big.getColorId()));
            } else if (p instanceof BitPacket bit) {
                drawCircle(g2, x, baseY, size, DrawUtil.colorForId(bit.getColorId()), String.valueOf(bit.getFragmentIdx()));
            } else if (p instanceof ProtectedPacket<?>) {
                DrawUtil.drawShield(g2, x, baseY, size);
            } else if (p instanceof SecretPacket1) {
                DrawUtil.drawHexagon(g2, x, baseY, size, new Color(0xFF9800));
            } else if (p instanceof SecretPacket2<?>) {
                DrawUtil.drawPadlock(g2, x, baseY, size, new Color(0x3F51B5));
            } else {
                g2.setColor(Color.GRAY);
                g2.fillOval(x - size, baseY - size, 2*size, 2*size);
            }
            x += 2*size + gap;
        }
    }

    private static void drawCircleCluster(Graphics2D g2, int cx, int cy, int count, int clusterR, Color fill) {
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

    private static void drawCircle(Graphics2D g2, int cx, int cy, int r, Color fill, String annotation) {
        g2.setColor(fill);
        g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        if (annotation != null) {
            g2.setColor(Color.WHITE);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 8f));
            int w = g2.getFontMetrics().stringWidth(annotation);
            g2.drawString(annotation, cx - w / 2, cy + 3);
        }
    }
}
