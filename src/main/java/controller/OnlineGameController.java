package controller;

import common.cmd.ClientCommand;
import common.cmd.LaunchCmd;
import common.cmd.ReadyCmd;
import controller.actions.OnlineBuildActions;
import controller.commands.CommandSender;
import model.SystemManager;
import view.GamePanel;

import javax.swing.*;
import java.awt.CardLayout;

public final class OnlineGameController {

    private final GamePanel panel;
    private final SystemManager clientViewModel; // view-only (no local sim)
    private final client.GameClient client;

    private final JButton launchBtn = new JButton("Launch");

    public OnlineGameController(SystemManager mirror, JPanel cards, client.GameClient client, String levelName) {
        this.clientViewModel = mirror;
        this.client = client;

        // 1) Create/show game panel
        panel = new GamePanel(clientViewModel);
        cards.add(panel, "GAME");
        ((CardLayout) cards.getLayout()).show(cards, "GAME");

        // 2) Launch button (enable now; this is created on/after START)
        launchBtn.setEnabled(true);
        launchBtn.setBounds(150, 10, 100, 25);
        panel.add(launchBtn);
        launchBtn.addActionListener(e ->
                this.client.send(new LaunchCmd(client.nextSeq()))
        );

        // Command sender adapter
        CommandSender sender = new CommandSender() {
            @Override public long nextSeq() { return client.nextSeq(); }
            @Override public void send(ClientCommand cmd) { client.send(cmd); }
        };

        // 3) INPUT: UI -> Server (no local mutation in online mode)
        var actions = new OnlineBuildActions(sender);
        new ConnectionController(/* online = */ true, /* actions */ actions, /* model */ clientViewModel, /* canvas */ panel);


//        this.client.setSnapshotHandler(snap ->
//                SwingUtilities.invokeLater(() -> {
//                    // Always push the new state tree
//                    panel.setSnapshotReplace(snap.state());
//
//                    var ui = snap.ui();
//                    if (ui != null) {
//                        final String my = this.client.getSide();                 // "A" or "B"
//                        final String opp = "A".equalsIgnoreCase(my) ? "B" : "A";
//
//                        // Wire budgets (mine & opponent)
//                        Integer usedMine = asInt(ui.get("wireUsed"   + my));
//                        Integer capMine  = asInt(ui.get("wireBudget" + my));
//                        Integer usedOpp  = asInt(ui.get("wireUsed"   + opp));
//                        Integer capOpp   = asInt(ui.get("wireBudget" + opp));
//                        panel.setWireBudgets(usedMine, capMine, usedOpp, capOpp);
//
//                        // HUD polylines (mine & opponent)
//                        @SuppressWarnings("unchecked")
//                        java.util.List<java.util.Map<String,Object>> myLines =
//                                (java.util.List<java.util.Map<String,Object>>) ui.get("hudLines" + my);
//                        @SuppressWarnings("unchecked")
//                        java.util.List<java.util.Map<String,Object>> oppLines =
//                                (java.util.List<java.util.Map<String,Object>>) ui.get("hudLines" + opp);
//                        panel.setHudLines(myLines, oppLines);
//                    }
//                })
//        );
        this.client.setSnapshotHandler(snap ->
                SwingUtilities.invokeLater(() -> {
                    panel.setSnapshotReplace(snap.state());
                    var ui = snap.ui();
                    if (ui != null) {
                        final String my  = this.client.getSide();
                        final String opp = "A".equalsIgnoreCase(my) ? "B" : "A";

                        // budgets (unchanged)
                        Integer usedMine = asInt(ui.get("wireUsed"   + my));
                        Integer capMine  = asInt(ui.get("wireBudget" + my));
                        Integer usedOpp  = asInt(ui.get("wireUsed"   + opp));
                        Integer capOpp   = asInt(ui.get("wireBudget" + opp));
                        panel.setWireBudgets(usedMine, capMine, usedOpp, capOpp);

                        // lines (unchanged)
                        @SuppressWarnings("unchecked") var myLines  =
                                (java.util.List<java.util.Map<String,Object>>) ui.get("hudLines" + my);
                        @SuppressWarnings("unchecked") var oppLines =
                                (java.util.List<java.util.Map<String,Object>>) ui.get("hudLines" + opp);
                        panel.setHudLines(myLines, oppLines);

                        // 🔽 NEW: capability gates
                        boolean canLaunch = Boolean.TRUE.equals(ui.get("canLaunch" + my));
//                        boolean canBuild  = Boolean.TRUE.equals(ui.get("canBuild"  + my));
                        launchBtn.setEnabled(canLaunch);

                        // (Optional) tell the ConnectionController about canBuild so it can ignore
                        // build gestures when false — or just let server drop them with a toast.
//                        panel.setCanBuild(canBuild); // add a simple setter if you want to gray UI
                    }
                })
        );



        // 5) (Optional) also enable Launch if a later START arrives (e.g., resume)
        this.client.setStartHandler(side ->
                SwingUtilities.invokeLater(() -> launchBtn.setEnabled(true))
        );

        // 6) Ensure the chosen level is queued; connect if needed
        this.client.setDesiredLevel(levelName);
        try {
            this.client.connect();
            // Optional “Ready” signal (remove if you don’t use it server-side)
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(panel, "Connect failed: " + ex.getMessage(),
                    "Network Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void stop() {
        // No local sim to stop here.
    }
    private static Integer asInt(Object o) {
        return (o instanceof Number) ? ((Number)o).intValue() : null;
    }
}
