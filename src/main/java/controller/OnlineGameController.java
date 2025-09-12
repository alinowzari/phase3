package controller;

import common.dto.NetSnapshotDTO;
import common.dto.cmd.ClientCommand;
import common.dto.cmd.LaunchCmd;
import model.SystemManager;
import view.GamePanel;

import javax.swing.*;
import java.awt.CardLayout;
import java.util.concurrent.atomic.AtomicLong;

public class OnlineGameController {

    private final GamePanel panel;
    private final SystemManager clientViewModel; // view-only (no local sim)
    private final client.GameClient client;

    private final AtomicLong seq = new AtomicLong();
    private final JButton launchBtn = new JButton("Launch");

    /**
     * @param levelName the level user chose in your menu (e.g., "Level 1")
     */
    public OnlineGameController(SystemManager mirror, JPanel cards,
                                client.GameClient client, String levelName) {
        this.clientViewModel = mirror;
        this.client = client;

        // Create/show game panel
        panel = new GamePanel(clientViewModel);
        cards.add(panel, "GAME");
        ((CardLayout) cards.getLayout()).show(cards, "GAME");

        // Disable gameplay controls until START
        launchBtn.setEnabled(false);
        launchBtn.setBounds(150, 10, 100, 25);
        panel.add(launchBtn);

        // Input controller in ONLINE mode:
        // it creates ClientCommand objects; we just forward them to the server.
        new ConnectionController(
                /* online = */ true,
                /* sender = */ (ClientCommand cmd) -> client.send(cmd),
                /* cmds   = */ null,
                /* model  = */ clientViewModel,
                /* canvas = */ panel
        );

        // Launch is just another command (sequence carried inside LaunchCmd)
        launchBtn.addActionListener(e ->
                client.send(new LaunchCmd(seq.incrementAndGet()))
        );

        // Render authoritative snapshots from server
        client.setSnapshotHandler((NetSnapshotDTO snap) ->
                SwingUtilities.invokeLater(() -> panel.setSnapshot(snap.state()))
        );

        // Enable controls when the server starts the match (side assigned)
        client.setStartHandler(side ->
                SwingUtilities.invokeLater(() -> launchBtn.setEnabled(true))
        );

        // Tell the client which level to join; it will send JOIN_QUEUE {level:...} right after HELLO_S
        client.setDesiredLevel(levelName);
        // If the socket isn’t open yet, connect now (host/port were provided to GameClient ctor)
        if (!client.isOpen()) {
            try { client.connect(); } catch (Exception ex) {
                JOptionPane.showMessageDialog(panel, "Connect failed: " + ex.getMessage(),
                        "Network Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void stop() {
        // No local sim to stop; client close handled elsewhere if you add it.
    }
}
