
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


    private java.util.List<java.util.Map<String,Object>> hudMy  = java.util.List.of();
    private java.util.List<java.util.Map<String,Object>> hudOpp = java.util.List.of();
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
    // GamePanel.java
    public Integer pickSystemIdAt(Point p) {
        var hit = Pickers.pickSystemAt(snapshot, p, W, H);
        return (hit == null) ? null : hit.systemId();
    }



    // GamePanel.java (add these methods)
    public void setSnapshotReplace(common.StateDTO s) {
        // your view already draws from a single StateDTO; replacing is enough
        setSnapshot(s); // alias
    }

    /** Provide two HUD polylines collections: mine (solid) and opponent (ghosted). */
    public void setHudLines(java.util.List<java.util.Map<String,Object>> my,
                            java.util.List<java.util.Map<String,Object>> opp) {
        this.hudMy  = (my  == null ? java.util.List.of() : my);
        this.hudOpp = (opp == null ? java.util.List.of() : opp);

        // also copy into uiData for renderers that pull from the map
        java.util.Map<String,Object> m =
                (this.uiData == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(this.uiData);
        m.put("hudLinesMy",  this.hudMy);
        m.put("hudLinesOpp", this.hudOpp);
        this.uiData = m;
        repaint();
    }

    /** Push the pair we want to display into the HUD. */
    public void setWireBudgets(java.lang.Integer usedMine, java.lang.Integer capMine,
                               java.lang.Integer usedOpp,  java.lang.Integer capOpp) {
        // HudOverlay reads "wireUsed"/"wireBudget" — set those for the *current* player
        java.util.Map<String,Object> m =
                (this.uiData == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(this.uiData);
        if (usedMine != null) m.put("wireUsed", usedMine);
        if (capMine  != null) m.put("wireBudget", capMine);

        // keep the full set too (handy if you add a dual HUD later)
        if (usedMine != null) m.put("wireUsedMine", usedMine);
        if (capMine  != null) m.put("wireBudgetMine", capMine);
        if (usedOpp  != null) m.put("wireUsedOpp",  usedOpp);
        if (capOpp   != null) m.put("wireBudgetOpp", capOpp);

        this.uiData = m;
        repaint();
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
