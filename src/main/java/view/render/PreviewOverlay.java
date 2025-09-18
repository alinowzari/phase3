package view.render;

import java.awt.*;

/** Draws dashed rubber-band wire + bend handles. Stateless. */
public final class PreviewOverlay {

    public void paint(Graphics2D g2, Point previewA, Point previewB, Point hMid, Point hA, Point hB) {
        // Rubber-band
        if (previewA != null && previewB != null) {
            Stroke old = g2.getStroke();
            Color  oc  = g2.getColor();
            g2.setColor(new Color(0, 0, 0, 128));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0));
            g2.drawLine(previewA.x, previewA.y, previewB.x, previewB.y);
            g2.setStroke(old);
            g2.setColor(oc);
        }
        // Bend handles
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
}
