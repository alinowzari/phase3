package controller;

import common.dto.NetSnapshotDTO;
import common.dto.cmd.ClientCommand;
import common.dto.cmd.LaunchCmd;
import common.dto.cmd.ReadyCmd;
import model.SystemManager;
import view.GamePanel;

import javax.swing.*;
import java.awt.CardLayout;
import java.util.concurrent.atomic.AtomicLong;

public final class OnlineGameController {

    private final GamePanel panel;
    private final SystemManager clientViewModel; // view-only (no local sim)
    private final client.GameClient client;

    private final AtomicLong seq = new AtomicLong();
    private final JButton launchBtn = new JButton("Launch");

    /**
     * @param levelName the level user chose in your menu (e.g., "Level 1")
     */
    public OnlineGameController(SystemManager mirror, JPanel cards, client.GameClient client, String levelName) {
        this.clientViewModel = mirror;
        this.client = client;

        // 1) Create/show game panel
        panel = new GamePanel(clientViewModel);
        cards.add(panel, "GAME");
        ((CardLayout) cards.getLayout()).show(cards, "GAME");

        // 2) Launch button (enabled after START)
        launchBtn.setEnabled(false);
        launchBtn.setBounds(150, 10, 100, 25);
        panel.add(launchBtn);
        launchBtn.addActionListener(e ->
                this.client.send(new LaunchCmd(seq.incrementAndGet()))
        );

        // 3) INPUT: UI -> Server (no local mutation in online mode)
        new ConnectionController(
                /* online = */ true,
                /* sender = */ (ClientCommand cmd) -> this.client.send(cmd),
                /* cmds   = */ null,                 // offline path unused
                /* model  = */ clientViewModel,      // hit-tests/ids only
                /* canvas = */ panel
        );

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

        // 5) START signal: enable launch
        this.client.setStartHandler(side ->
                SwingUtilities.invokeLater(() -> launchBtn.setEnabled(true))
        );

        // (optional) user-friendly error popups
        this.client.setErrorHandler(err ->
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(panel, err, "Network Error", JOptionPane.ERROR_MESSAGE)
                )
        );

        // 6) Join level, then connect (matches your ClientApp usage)
        this.client.setDesiredLevel(levelName);
        try {
            this.client.connect();

            // OPTIONAL: auto mark ready after connecting (remove if your UX has a Ready button)
            this.client.send(new ReadyCmd(seq.incrementAndGet()));

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(panel, "Connect failed: " + ex.getMessage(),
                    "Network Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void stop() {
        // No local sim to stop. If you add client.close(), call it here.
    }
}
