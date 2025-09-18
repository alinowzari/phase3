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

        // 4) OUTPUT: Server -> UI (authoritative snapshots)
        this.client.setSnapshotHandler(snap ->
                SwingUtilities.invokeLater(() -> {
                    var ui = snap.ui();
                    if (ui != null) {
                        Integer used = (ui.get("wireUsed")   instanceof Number) ? ((Number) ui.get("wireUsed")).intValue()   : null;
                        Integer cap  = (ui.get("wireBudget") instanceof Number) ? ((Number) ui.get("wireBudget")).intValue() : null;
                        panel.setWireHud(used, cap);
                    }
                    panel.setSnapshot(snap.state());
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
            this.client.send(new ReadyCmd(client.nextSeq()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(panel, "Connect failed: " + ex.getMessage(),
                    "Network Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void stop() {
        // No local sim to stop here.
    }
}
