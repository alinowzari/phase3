package view.render;

import common.PortType;
import common.StateDTO;
import common.SystemDTO;
import common.SystemType;
import model.SystemManager;
import model.System;
import model.Port;

import java.awt.*;
import java.util.List;

/** Draws systems + ports from either DTO snapshot or local model. */
public final class SystemRenderer {

    private static final int SYS_W = 90, SYS_H = 70, RND = 16, PORT = 12;

    public void paint(Graphics2D g2, SystemManager model, StateDTO snapshot) {
        if (snapshot != null && snapshot.systems() != null && !snapshot.systems().isEmpty()) {
            for (SystemDTO sd : snapshot.systems()) drawDTO(g2, sd);
            return;
        }
        if (model != null) {
            for (System sys : model.getAllSystems()) drawModel(g2, sys);
        }
    }

    /* ===== Model systems ===== */
    private void drawModel(Graphics2D g2, System sys) {
        Point loc = sys.getLocation();
        int x0 = loc.x, y0 = loc.y;

        g2.setColor(DrawUtil.colorFor(sys));
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

        paintPortsModel(g2, sys.getInputPorts(), x0, y0, true);
        paintPortsModel(g2, sys.getOutputPorts(), x0, y0, false);

        PacketRenderer.drawQueuedPackets(g2, sys, x0, y0 + SYS_H + 4);
    }

    private void paintPortsModel(Graphics2D g2, List<? extends Port> ports, int x0, int y0, boolean inputs) {
        int n = ports.size();
        for (int i = 0; i < n; i++) {
            Port p = ports.get(i);
            int cx = x0 + (inputs ? 0 : SYS_W);
            int cy = y0 + (i + 1) * SYS_H / (n + 1);
            p.setCenter(new Point(cx, cy));                 // keep hit-tests in sync
            DrawUtil.drawPortShape(g2, p.getType(), cx, cy, PORT);
        }
    }

    /* ===== DTO systems ===== */
    private void drawDTO(Graphics2D g2, SystemDTO sd) {
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

        paintPortsDTO(g2, sd, x0, y0, true);
        paintPortsDTO(g2, sd, x0, y0, false);
    }

    private void paintPortsDTO(Graphics2D g2, SystemDTO sd, int x0, int y0, boolean inputs) {
        int n = inputs ? sd.inputs() : sd.outputs();
        List<PortType> types = inputs ? sd.inputTypes() : sd.outputTypes();
        for (int i = 0; i < n; i++) {
            int cx = x0 + (inputs ? 0 : SYS_W);
            int cy = y0 + (i + 1) * SYS_H / (n + 1);
            model.Type shape = model.Type.SQUARE;
            if (types != null && i < types.size() && types.get(i) != null) {
                shape = mapPortType(types.get(i));
            }
            DrawUtil.drawPortShape(g2, shape, cx, cy, PORT);
        }
    }

    private static model.Type mapPortType(PortType pt) {
        return switch (pt) {
            case SQUARE   -> model.Type.SQUARE;
            case TRIANGLE -> model.Type.TRIANGLE;
            case INFINITY -> model.Type.INFINITY;
        };
    }

    private static Color colorForDTO(SystemType t) {
        return switch (t) {
            case REFERENCE    -> new Color(100, 149, 237);
            case NORMAL       -> new Color(144, 238, 144);
            case SPY          -> Color.LIGHT_GRAY;
            case VPN          -> new Color(255, 228, 181);
            case ANTI_TROJAN  -> new Color(255, 182, 193);
            case DESTROYER    -> new Color(240, 128, 128);
            case DISTRIBUTION -> new Color(255, 250, 205);
            case MERGER       -> new Color(216, 191, 216);
            default           -> Color.GRAY;
        };
    }
}
