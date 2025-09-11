package controller;

import model.SystemManager;
import view.GamePanel;

import javax.swing.*;
import java.awt.CardLayout;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sets up the in-level screen:
 *   • builds a GamePanel from the SystemManager
 *   • installs ConnectionController for wiring
 *   • runs a 60 Hz simulation on a background thread
 *   • repaints on the EDT
 */
public class GameController {

    private static final float FIXED_DT   = 1f / 60f;
    private static final float TIME_SCALE = 12f;  // 60 Hz sim tick

    private final GamePanel panel;
    private final JButton   launchBtn = new JButton("Launch packets");
    JButton saveBtn = new JButton("Save layout");
    private final SystemManager sm;

    private ScheduledExecutorService simExec;

    public GameController(SystemManager sm, JPanel cards) {
        this.sm = sm;

        /* 1 ▸ view */
        panel = new GamePanel(sm);
        cards.add(panel, "GAME");
        ((CardLayout) cards.getLayout()).show(cards, "GAME");

        /* 2 ▸ input */
        GameCommand lgc= new LocalGameCommand(sm);
        new ConnectionController(lgc, sm, panel);

        /* 3 ▸ launch button */
        launchBtn.setBounds(150, 10, 140, 25);
        launchBtn.addActionListener(e -> {
            sm.launchPackets();
            launchBtn.setEnabled(false); // one-shot
        });
        launchBtn.setEnabled(false);
        panel.add(launchBtn);
        saveBtn.setBounds(300, 10, 120, 25);
        saveBtn.addActionListener(e -> {
            var cfgMgr    = config.ConfigManager.getInstance();
            var curConfig = cfgMgr.getConfig();
            var snap      = model.Loader.LayoutIO.snapshotToConfig(curConfig, sm);
            model.Loader.LayoutIO.saveGameConfig(java.nio.file.Paths.get("gameConfig.json"), snap);
            JOptionPane.showMessageDialog(panel, "Saved to gameConfig.json");
        });
        panel.add(saveBtn);

        /* 4 ▸ start simulation (off the EDT) */
        startSimulation();
    }
    private void startSimulation() {
        simExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SimThread");
            t.setDaemon(true);
            return t;
        });
        simExec.scheduleAtFixedRate(() -> {
            try {
                sm.update(FIXED_DT * TIME_SCALE);
                var snap = mapper.Mapper.toState(sm);
                SwingUtilities.invokeLater(() -> panel.setSnapshot(snap));
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, 0, 16, TimeUnit.MILLISECONDS);// ~60 FPS cadence
    }

    /** Call when leaving the level */
    public void stop() {
        if (simExec != null) {
            simExec.shutdownNow();
            simExec = null;
        }
    }
}
