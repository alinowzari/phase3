//package view;
//
//import model.System;
//import model.ports.InputPort;
//import model.ports.OutputPort;
//import model.Type;
//
//import javax.swing.*;
//import java.awt.*;
//import java.awt.geom.CubicCurve2D;
//import java.util.ArrayList;
//import java.util.List;
//
///**
// * Base for all system boxes: draws a colored rounded rectangle with a label,
// * and paints its input/output ports directly on the border.
// */
//public abstract class SystemView extends JComponent {
//    public static final int WIDTH      = 90;
//    public static final int HEIGHT     = 70;
//    public static final int PORT_SIZE  = 12;
//
//    protected final System system;
//
//    public SystemView(System system) {
//        this.system = system;
//        setLayout(null);
//        setPreferredSize(new Dimension(WIDTH, HEIGHT));
//        setSize(WIDTH, HEIGHT);
//    }
//
//    /** Subclasses pick their fill-color (e.g. thistle, light green, etc.) */
//    protected abstract Color getBackgroundColor();
//
//    /** Subclasses pick their label (e.g. "Merger", "VPN", etc.) */
//    protected abstract String getLabelText();
//
//    @Override
//    protected void paintComponent(Graphics g) {
//        super.paintComponent(g);
//        Graphics2D g2 = (Graphics2D) g;
//        g2.setRenderingHint(
//                RenderingHints.KEY_ANTIALIASING,
//                RenderingHints.VALUE_ANTIALIAS_ON
//        );
//
//        // 1) Draw system box
//        g2.setColor(getBackgroundColor());
//        g2.fillRoundRect(0, 0, WIDTH, HEIGHT, 16, 16);
//        g2.setColor(Color.BLACK);
//        g2.setStroke(new BasicStroke(2));
//        g2.drawRoundRect(0, 0, WIDTH, HEIGHT, 16, 16);
//
//        // 2) Draw centered label
//        String label = getLabelText();
//        FontMetrics fm = g2.getFontMetrics();
//        int tx = (WIDTH - fm.stringWidth(label)) / 2;
//        int ty = (HEIGHT + fm.getAscent()) / 2 - 4;
//        g2.drawString(label, tx, ty);
//
//        // 3) Draw input ports down the left edge
//        List<InputPort> inPorts = system.getInputPorts();
//        for (int i = 0; i < inPorts.size(); i++) {
//            int y = (i + 1) * HEIGHT / (inPorts.size() + 1) - PORT_SIZE/2;
//            drawPortShape(g2, inPorts.get(i).getType(), -PORT_SIZE/2, y);
//        }
//
//        // 4) Draw output ports down the right edge
//        List<OutputPort> outPorts = system.getOutputPorts();
//        for (int i = 0; i < outPorts.size(); i++) {
//            int y = (i + 1) * HEIGHT / (outPorts.size() + 1) - PORT_SIZE/2;
//            drawPortShape(g2, outPorts.get(i).getType(), WIDTH - PORT_SIZE/2, y);
//        }
//    }
//
//    /** Helper: draw one of the three port shapes at (x,y). */
//    private void drawPortShape(Graphics2D g2, Type type, int x, int y) {
//        switch (type) {
//            case SQUARE -> {
//                g2.setColor(Color.BLUE);
//                g2.fillRect(x, y, PORT_SIZE, PORT_SIZE);
//            }
//            case TRIANGLE -> {
//                g2.setColor(Color.GREEN.darker());
//                int[] xs = { x + PORT_SIZE/2, x, x + PORT_SIZE };
//                int[] ys = { y, y + PORT_SIZE, y + PORT_SIZE };
//                g2.fillPolygon(xs, ys, 3);
//            }
//            case INFINITY -> {
//                g2.setColor(Color.MAGENTA);
//                g2.setStroke(new BasicStroke(2));
//                // draw two loops of ∞
//                g2.draw(new CubicCurve2D.Float(
//                        x, y + PORT_SIZE/2,
//                        x + PORT_SIZE/4, y,
//                        x + PORT_SIZE*3/4, y,
//                        x + PORT_SIZE, y + PORT_SIZE/2
//                ));
//                g2.draw(new CubicCurve2D.Float(
//                        x, y + PORT_SIZE/2,
//                        x + PORT_SIZE/4, y + PORT_SIZE,
//                        x + PORT_SIZE*3/4, y + PORT_SIZE,
//                        x + PORT_SIZE, y + PORT_SIZE/2
//                ));
//            }
//            default -> {
//                // fallback: small circle
//                g2.setColor(Color.DARK_GRAY);
//                g2.fillOval(x, y, PORT_SIZE, PORT_SIZE);
//            }
//        }
//    }
//}
package view;

import model.Port;
import model.Type;
import model.systems.*;
import model.System;
import model.ports.InputPort;
import model.ports.OutputPort;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.CubicCurve2D;
import java.util.List;

public class SystemView extends JComponent {
    public static final int W = 90, H = 70, PS = 12;          // port size

    private final System sys;

    public SystemView(System s) {
        this.sys = s;
        setSize(W, H);
        setOpaque(false);          // we'll be placed by GamePanel with null-layout
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        /* 1 ▸ box */
        g2.setColor(bgColor(sys));
        g2.fillRoundRect(0, 0, W, H, 16, 16);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(0, 0, W, H, 16, 16);

        /* 2 ▸ label */
        String label = sys.getClass().getSimpleName().replace("System","");
        FontMetrics fm = g2.getFontMetrics();
        int tx = (W - fm.stringWidth(label)) / 2;
        int ty = (H + fm.getAscent()) / 2 - 4;
        g2.drawString(label, tx, ty);

        /* 3 ▸ ports (from model!) */
        drawPorts(g2, sys.getInputPorts(), true);
        drawPorts(g2, sys.getOutputPorts(), false);
    }

    private void drawPorts(Graphics2D g2,
                           List<? extends Port> ports,
                           boolean inputs)
    {
        int n = ports.size();
        for (int i = 0; i < n; i++) {
            Type t = ports.get(i).getType();
            int relX = inputs ? -PS/2 : W - PS/2;
            int relY = (i + 1) * H / (n + 1) - PS/2;
            paintShape(g2, t, relX, relY);
        }
    }

    private static void paintShape(Graphics2D g2, Type t, int x, int y) {
        switch (t) {
            case SQUARE -> {
                g2.setColor(Color.BLUE);
                g2.fillRect(x, y, PS, PS);
            }
            case TRIANGLE -> {
                g2.setColor(Color.GREEN.darker());
                int[] xs = {x + PS/2, x, x + PS};
                int[] ys = {y, y + PS, y + PS};
                g2.fillPolygon(xs, ys, 3);
            }
            case INFINITY -> {
                g2.setColor(Color.MAGENTA);
                g2.setStroke(new BasicStroke(2));
                g2.draw(new CubicCurve2D.Float(x,          y + PS/2,
                        x + PS/4f,  y,
                        x + 3*PS/4f,y,
                        x + PS,     y + PS/2));
                g2.draw(new CubicCurve2D.Float(x,          y + PS/2,
                        x + PS/4f,  y + PS,
                        x + 3*PS/4f,y + PS,
                        x + PS,     y + PS/2));
            }
        }
    }

    private static Color bgColor(System s) {
        return switch (s) {
            case ReferenceSystem    ignore -> new Color(100,149,237);
            case NormalSystem       ignore -> new Color(144,238,144);
            case SpySystem          ignore -> Color.LIGHT_GRAY;
            case VpnSystem          ignore -> new Color(255,228,181);
            case AntiTrojanSystem   ignore -> new Color(255,182,193);
            case DestroyerSystem    ignore -> new Color(240,128,128);
            case DistributionSystem ignore -> new Color(255,250,205);
            case MergerSystem       ignore -> new Color(216,191,216);
            default                        -> Color.GRAY;
        };
    }
}
