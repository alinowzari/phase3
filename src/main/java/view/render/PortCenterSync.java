package view.render;

import model.SystemManager;
import model.Port;

import java.awt.Point;

/** Keeps model port centers in sync with what we draw.  */
public final class PortCenterSync {
    private static final int SYS_W = 90, SYS_H = 70;

    private PortCenterSync() {}

    public static void sync(SystemManager sm) {
        if (sm == null) return;

        sm.getAllSystems().forEach(sys -> {
            int x0 = sys.getLocation().x;
            int y0 = sys.getLocation().y;

            int nIn = sys.getInputPorts().size();
            for (int i = 0; i < nIn; i++) {
                int cx = x0;
                int cy = y0 + (i + 1) * SYS_H / (nIn + 1);
                Port p = sys.getInputPorts().get(i);
                p.setCenter(new Point(cx, cy));
            }

            int nOut = sys.getOutputPorts().size();
            for (int i = 0; i < nOut; i++) {
                int cx = x0 + SYS_W;
                int cy = y0 + (i + 1) * SYS_H / (nOut + 1);
                Port p = sys.getOutputPorts().get(i);
                p.setCenter(new Point(cx, cy));
            }
        });
    }
}
