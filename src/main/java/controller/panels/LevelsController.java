// src/main/java/controller/LevelsController.java
package controller.panels;

import controller.LocalGameController;
import controller.OnlineGameController;
import model.LevelsManager;
import model.SystemManager;
import view.LevelsView;

import javax.swing.*;
import java.awt.*;

public class LevelsController {

    private final LevelsView view;
    private final CardLayout cardLayout;
    private final JPanel cards;
    private final LevelsManager levelsManager;

    private LocalGameController offlineGame = null;
    private OnlineGameController onlineGame  = null;

    public LevelsController(LevelsView view, JPanel cards, LevelsManager levelsManager) {
        this.view = view;
        this.cards = cards;
        this.levelsManager = levelsManager;

        LayoutManager lm = cards.getLayout();
        if (!(lm instanceof CardLayout)) {
            throw new IllegalArgumentException("cards must use CardLayout");
        }
        this.cardLayout = (CardLayout) lm;

        initListeners();
    }

    private void initListeners() {
        JButton[] btns = view.getLevelButtons(); // your view already returns an array
        for (int i = 0; i < btns.length; i++) {
            final int idx = i;
            btns[i].addActionListener(e -> startLevelOffline(idx));
        }
    }

    /** ---- OFFLINE ---- */
    public void startLevelOffline(int idx) {
        stopActive();

        SystemManager sm = levelsManager.getLevelManager(idx);
        offlineGame = new LocalGameController(sm, cards);
    }

    /** ---- ONLINE ---- call this from your future multiplayer flow */
    public void startLevelOnline(int idx, client.GameClient netClient) {
        stopActive();

        // a lightweight mirror SystemManager used only for hit-tests/IDs on the client
        // seed/name can come from your level config; it's not simulated locally
        String levelName = levelsManager.getLevelConfigs().get(idx).levelName();
        SystemManager mirror = new SystemManager(null, levelName);

//        onlineGame = new OnlineGameController(mirror, cards, netClient);
    }

    private void stopActive() {
        if (offlineGame != null) { offlineGame.stop(); offlineGame = null; }
        if (onlineGame  != null) { onlineGame.stop();  onlineGame  = null; }
    }

    public void refreshLocks() {
        boolean[] passed = levelsManager.passedArray();   // your helper
        SwingUtilities.invokeLater(() -> view.setLocks(passed));
    }
}
