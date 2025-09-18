
package view;

import common.StateDTO;
import common.LineDTO;
import common.PointDTO;
import model.SystemManager;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

import view.render.*;

/**
 * Thin orchestrator panel. Delegates drawing and picking to small classes.
 * Public API matches the old GamePanel to avoid controller churn.
 */
public class GamePanel extends JPanel {

    // === Public constants kept for compatibility ===
    public static final int W  = 90;
    public static final int H  = 70;
    public static final int PS = 12;

    // === Model / DTO state ===
    private volatile StateDTO snapshot;
    private final SystemManager model;
    private volatile Map<String, Object> uiData;

    // === Preview + handles (kept as simple fields; drawn by PreviewOverlay) ===
    private Point previewA, previewB;
    private Point hMid, hA, hB;

    // === Renderers ===
    private final HudOverlay      hud;
    private final SystemRenderer  systems;
    private final WireRenderer    wires;
    private final PacketRenderer  packets;
    private final PreviewOverlay  preview;

    public GamePanel() { this(null); }

    public GamePanel(SystemManager model) {
        this.model = model;

        setBackground(Color.WHITE);
        setLayout(null);
        setDoubleBuffered(true);
        setPreferredSize(new Dimension(960, 540));

        // Compose the view in small units
        this.hud     = new HudOverlay(this);
        this.systems = new SystemRenderer();
        this.wires   = new WireRenderer();
        this.packets = new PacketRenderer();
        this.preview = new PreviewOverlay();

        // HUD adds labels to this panel
        hud.installOn(this);
    }

    /* ===== Public API (unchanged) ===== */
    public void showPreview(Point a, Point b) { previewA = a; previewB = b; repaint(); }
    public void hidePreview()                 { previewA = previewB = null; repaint(); }

    public void setHandles(Point mid, Point a, Point b) { this.hMid = mid; this.hA = a; this.hB = b; repaint(); }
    public void clearHandles()                          { hMid = hA = hB = null; repaint(); }

    public void setSnapshot(StateDTO s) { this.snapshot = s; repaint(); }
    public StateDTO getSnapshot()       { return snapshot; }

    /** API kept for compatibility; HUD now auto-computes from uiData/model. */
    public void setWireHud(Integer used, Integer cap) { /* no-op by design */ }

    public void setUiData(Map<String, Object> ui) { this.uiData = ui; repaint(); }

    // ===== Online picking helpers (delegated to Pickers) =====
    public record PortPick(int systemId, int portIndex, int x, int y) {}
    public LineDTO pickLineAt(Point p, int tolPx) {
        return Pickers.pickLineAt(snapshot, p, tolPx);
    }

    // FIXED: return correct ids + coords
    public PortPick pickOutputAt(Point p) {
        var hit = Pickers.pickPortAt(snapshot, p, /*input=*/false);
        return (hit == null) ? null : new PortPick(hit.systemId(), hit.portIndex(), hit.x(), hit.y());
    }
    public PortPick pickInputAt(Point p) {
        var hit = Pickers.pickPortAt(snapshot, p, /*input=*/true);
        return (hit == null) ? null : new PortPick(hit.systemId(), hit.portIndex(), hit.x(), hit.y());
    }


    /* ===== Paint orchestration ===== */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // ✅ make geometry consistent for OFFLINE logic
        if (model != null) {
            view.render.PortCenterSync.sync(model);
        }

        hud.paint(g2, model, snapshot, uiData, getWidth());
        systems.paint(g2, model, snapshot);
        wires.paint(g2, model, snapshot, uiData);
        preview.paint(g2, previewA, previewB, hMid, hA, hB);
        packets.paint(g2, model, snapshot);
    }

}
