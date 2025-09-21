package view.render;

import common.StateDTO;
import model.SystemManager;
import model.ports.outputs.SquareOutput;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/** Labels + wire-usage badges. */
public final class HudOverlay {

    private final JLabel statusLabel = new JLabel("Ready: —");
    private final JLabel coinLabel   = new JLabel("Coins: —");
    private final JLabel totalLabel  = new JLabel("Total: —");

    private final JComponent parent;

    public HudOverlay(JComponent parent) {
        this.parent = parent;
        statusLabel.setBounds(10, 10, 140, 20);
        coinLabel.setBounds(10, 30, 140, 20);
        totalLabel.setBounds(10, 50, 140, 20);
    }

    public void installOn(JComponent p) {
        p.add(statusLabel);
        p.add(coinLabel);
        p.add(totalLabel);
    }
    public void paint(Graphics2D g2, SystemManager model, StateDTO snapshot, Map<String, Object> uiData, int panelWidth) {
        final boolean online = (snapshot != null && uiData != null);

        final String side = online && uiData.get("side") != null ? String.valueOf(uiData.get("side")) : "A";
        final boolean isA  = "A".equalsIgnoreCase(side);

        if (online) {
            var ready = getValue(uiData, isA ? "readyA" : "readyB", false);
            var coins = getValue(uiData, isA ? "coinsA" : "coinsB", 0);
            Integer total = getIntOrNull(uiData, isA ? "totalA" : "totalB");
            if (total == null) total = getIntOrNull(uiData, "totalCoins");

            statusLabel.setText("Ready: " + ready);
            coinLabel.setText("Coins: " + coins);
            totalLabel.setText(total != null ? "Total: " + total : "Total: —");
        } else if (model != null) {
            statusLabel.setText("Ready: " + model.isReady());
            coinLabel.setText("Coins: " + model.coinCount);
            totalLabel.setText("Total: " + model.getTotalCoins());
        } else {
            statusLabel.setText("Ready: —");
            coinLabel.setText("Coins: —");
            totalLabel.setText("Total: —");
        }

        int w = totalLabel.getPreferredSize().width;
        totalLabel.setBounds(panelWidth - w - 10, 10, w, 20);

        int usedPx = 0, capPx = 0;
        if (online) {
            Integer u = getIntOrNull(uiData, isA ? "wireUsedA"   : "wireUsedB");
            Integer c = getIntOrNull(uiData, isA ? "wireBudgetA" : "wireBudgetB");
            if (u == null || c == null) { u = getIntOrNull(uiData, "wireUsed"); c = getIntOrNull(uiData, "wireBudget"); }
            usedPx = (u != null ? u : 0);
            capPx  = (c != null ? c : 0);
        } else if (model != null) {
            usedPx = model.getWireUsedPx();
            capPx  = (int) model.getWireBudgetPx();
        }
        drawWireHud(g2, usedPx, capPx, panelWidth);
    }

    private static Object getValue(Map<String,Object> m, String k, Object def) {
        if(!m.containsKey(k)) {
            System.out.println("Missing key: " + k);
        }
        return m.get(k);
    }
    private static Integer getIntOrNull(Map<String,Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) try { return Integer.parseInt(s.trim()); } catch (Exception ignore) {}
        return null;
    }

    private static void drawWireHud(Graphics2D g2, int usedPx, int capPx, int panelWidth) {
        String usedTxt = "Used: " + usedPx + " px";
        String capTxt  = "Max:  " + capPx  + " px";

        FontMetrics fm = g2.getFontMetrics();
        int boxW  = Math.max(fm.stringWidth(usedTxt), fm.stringWidth(capTxt)) + 16;
        int boxH  = 18;
        int x = panelWidth - boxW - 10;
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

        DrawUtil.badge(g2, x, usedY, boxW, boxH, usedTxt, usedBg);
        DrawUtil.badge(g2, x, capY,  boxW, boxH, capTxt,  capBg);
    }
}