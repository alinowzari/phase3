// src/main/java/client/ClientApp.java
package client;

import common.NetSnapshotDTO;
import common.cmd.ClientCommand;
import common.cmd.LaunchCmd;
import model.LevelsManager;
import model.SystemManager;
import view.GamePanel;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple online launcher:
 *  1) Connect (host/port)
 *  2) Pick level (pulled from your LevelsManager)
 *  3) Queue & play (ONLINE controller; server is the authority)
 *
 * Run two copies of this app; both pick the same level -> server matches them.
 */
public final class ClientApp {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientApp().start(args));
    }

    // --- UI state ---
    private JFrame frame;
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);

    private JTextField hostField;
    private JTextField portField;
    private JComboBox<String> levelBox;
    private JButton connectBtn, queueBtn, backBtn, launchBtn;

    // --- Networking ---
    private GameClient client;
    private final AtomicReference<String> chosenLevel = new AtomicReference<>("default");

    // --- View/model (mirror only; server drives snapshots) ---
    private SystemManager mirror; // loaded locally to draw systems/ports
    private GamePanel gamePanel;

    private void start(String[] args) {
        // Defaults from args if present
        String host  = args.length > 0 ? args[0] : "127.0.0.1";
        int    port  = args.length > 1 ? safePort(args[1], 5555) : 5555;

        // Build UI
        buildConnectCard(host, port);
        buildQueueCard();
        // The GAME card is created lazily on START

        frame = new JFrame("Phase3 – Online");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(1000, 720);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(root);
        frame.setVisible(true);
    }

    /* ========================= Cards ========================= */

    private void buildConnectCard(String defHost, int defPort) {
        JPanel p = new JPanel(null);

        JLabel t = new JLabel("Connect to Server");
        t.setFont(t.getFont().deriveFont(Font.BOLD, 18f));
        t.setBounds(20, 20, 300, 28);
        p.add(t);

        JLabel lh = new JLabel("Host:");
        lh.setBounds(20, 70, 60, 24);
        p.add(lh);

        hostField = new JTextField(defHost);
        hostField.setBounds(80, 70, 160, 24);
        p.add(hostField);

        JLabel lp = new JLabel("Port:");
        lp.setBounds(260, 70, 60, 24);
        p.add(lp);

        portField = new JTextField(String.valueOf(defPort));
        portField.setBounds(310, 70, 80, 24);
        p.add(portField);

        connectBtn = new JButton("Connect");
        connectBtn.setBounds(420, 70, 120, 24);
        p.add(connectBtn);

        JTextArea log = new JTextArea();
        log.setEditable(false);
        JScrollPane sp = new JScrollPane(log);
        sp.setBounds(20, 120, 520, 140);
        p.add(sp);

// inside buildConnectCard(...) – the Connect button handler
        connectBtn.addActionListener(e -> {
            String host = hostField.getText().trim();
            int port = safePort(portField.getText().trim(), 5555);

            client = new GameClient(host, port);

            // log/errors to textarea
            client.setLogHandler(msg -> SwingUtilities.invokeLater(() -> {
                log.append("[LOG] " + msg + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            }));
            client.setErrorHandler(msg -> SwingUtilities.invokeLater(() -> {
                log.append("[ERR] " + msg + "\n");
                log.setCaretPosition(log.getDocument().getLength());
                JOptionPane.showMessageDialog(frame, msg, "Network Error", JOptionPane.ERROR_MESSAGE);
            }));

            // IMPORTANT: On START, hand off to OnlineGameController
            client.setStartHandler(side -> SwingUtilities.invokeLater(() -> {
                String levelName = chosenLevel.get();
                SystemManager mirror = levelToSystemManager(levelName);
                if (mirror == null) mirror = levelToSystemManager("default");
                if (mirror == null) {
                    JOptionPane.showMessageDialog(frame,
                            "Could not load level layout locally.\n" +
                                    "You can still connect, but boxes/ports won’t render.",
                            "Level Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                // OnlineGameController will add the panel to 'root' and show it
                new controller.OnlineGameController(mirror, root, client, levelName);
            }));

            try {
                client.connect();         // non-blocking
                cards.show(root, "QUEUE");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Connect failed: " + ex.getMessage(),
                        "Network Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        root.add(p, "CONNECT");
        cards.show(root, "CONNECT");
    }

    private void buildQueueCard() {
        JPanel p = new JPanel(null);

        JLabel t = new JLabel("Pick Level & Join Queue");
        t.setFont(t.getFont().deriveFont(Font.BOLD, 18f));
        t.setBounds(20, 20, 360, 28);
        p.add(t);

        JLabel ll = new JLabel("Level:");
        ll.setBounds(20, 70, 60, 24);
        p.add(ll);

        // Try to list levels from your shared LevelsManager (client side)
        String[] levels = listLevelNames();
        if (levels == null || levels.length == 0) {
            levels = new String[] { "level 1" };
        }
        levelBox = new JComboBox<>(levels);
        levelBox.setBounds(80, 70, 220, 24);
        p.add(levelBox);

        queueBtn = new JButton("Join Queue");
        queueBtn.setBounds(320, 70, 120, 24);
        p.add(queueBtn);

        backBtn = new JButton("Back");
        backBtn.setBounds(20, 120, 80, 24);
        p.add(backBtn);

        queueBtn.addActionListener(e -> {
            String level = Objects.toString(levelBox.getSelectedItem(), "default");
            chosenLevel.set(level);
            if (client == null) {
                JOptionPane.showMessageDialog(frame, "Not connected.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            client.setDesiredLevel(level); // matchmaker will use this
            JOptionPane.showMessageDialog(frame,
                    "Queued for level: " + level + "\nWaiting for opponent...",
                    "Matchmaking", JOptionPane.INFORMATION_MESSAGE);
        });

        backBtn.addActionListener(e -> {
            if (client != null) {
                try { client.close(); } catch (Exception ignore) {}
                client = null;
            }
            cards.show(root, "CONNECT");
        });

        root.add(p, "QUEUE");
    }


    /* ========================= Helpers ========================= */

    private static int safePort(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception ignore) { return def; }
    }

    /**
     * Try to enumerate levels via your shared LevelsManager.
     * Falls back to a couple of generic names if it can’t.
     */
    /**
     * Build the same SystemManager layout the server uses, for drawing boxes/ports.
     * This is *view-only*; the authoritative dynamics come from server snapshots.
     */
    private static SystemManager levelToSystemManager(String levelName) {
        try {
            LevelsManager lm = new LevelsManager();
            SystemManager sm = lm.getLevelManager(levelName);
            if (sm != null) return sm;

            // fallback to first level name if available
            String first = lm.getLevelName(0);
            if (first != null) {
                sm = lm.getLevelManager(first);
                if (sm != null) return sm;
            }
        } catch (Throwable ignore) { }
        return null;
    }
    private static String[] listLevelNames() {
        try {
            LevelsManager lm = new LevelsManager();
            // getLevelConfigs() is a List (not a Map)
            java.util.List<?> cfgs = lm.getLevelConfigs();
            java.util.List<String> names = new java.util.ArrayList<>();

            if (cfgs != null && !cfgs.isEmpty()) {
                for (int i = 0; i < cfgs.size(); i++) {
                    try {
                        String name = lm.getLevelName(i);
                        if (name == null || name.isBlank()) break;
                        names.add(name);
                    } catch (Throwable ignore) {
                        break;
                    }
                }
            } else {
                // Fallback: probe first few indexes defensively
                for (int i = 0; i < 16; i++) {
                    try {
                        String name = lm.getLevelName(i);
                        if (name == null || name.isBlank()) break;
                        names.add(name);
                    } catch (Throwable ignore) {
                        break;
                    }
                }
            }

            if (!names.isEmpty()) return names.toArray(String[]::new);
        } catch (Throwable ignore) { /* swallow and fallback below */ }

        // Safe defaults if LevelsManager can't enumerate
        return new String[] { "default", "level 1" };
    }

}
