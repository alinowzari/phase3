// src/main/java/controller/LevelsController.java
package controller;

import model.LevelsManager;
import model.SystemManager;
import view.LevelsView;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;

/**
 * Handles clicks on the level buttons:
 *   • gets the corresponding SystemManager from LevelsManager
 *   • creates a GameController (which builds GamePanel internally)
 *   • switches the CardLayout to the "GAME" card
 *
 * One GameController is kept alive at a time; starting another level will
 * stop the previous game loop.
 */
public class LevelsController {

    private final LevelsView    view;
    private final CardLayout    cardLayout;
    private final JPanel        cards;
    private final LevelsManager levelsManager;

    /* keep reference so we can stop it when player picks a new level */
    private GameController activeGame = null;

    public LevelsController(LevelsView view,
                            JPanel cards,
                            LevelsManager levelsManager)
    {
        this.view          = view;
        this.cards         = cards;
        this.levelsManager = levelsManager;

        if (!(cards.getLayout() instanceof CardLayout cl)) {
            throw new IllegalArgumentException("cards must use CardLayout");
        }
        this.cardLayout = cl;

        initListeners();
    }

    /* ------------------------------------------------------------- */
    private void initListeners() {
        JButton[] btns = view.getLevelButtons();

        for (int idx = 0; idx < btns.length; idx++) {
            final int levelIndex = idx;
            btns[idx].addActionListener(e -> startLevel(levelIndex));
        }
    }

    /* ------------------------------------------------------------- */
    public void startLevel(int idx) {
        // 1 ▸ stop any running game
        if (activeGame != null) {
            activeGame.stop();
            activeGame = null;
        }

        // 2 ▸ fetch the pre-built SystemManager for this level
        SystemManager sm = levelsManager.getLevelManager(idx);

        // 3 ▸ spin up a new GameController (adds "GAME" card & shows it)
        activeGame = new GameController(sm, cards);
    }
    public void refreshLocks() {
        // passed[i] == true means "level i is passed"
        boolean[] passed = levelsManager.passedArray();   // we added this helper earlier
        SwingUtilities.invokeLater(() -> view.setLocks(passed));
    }
}
