// src/main/java/app/Main.java
package app;

import controller.panels.LevelsController;
import controller.panels.MenuController;
import model.LevelsManager;
import view.LevelsView;
import view.MenuView;

import javax.swing.*;
import java.awt.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // ----- Frame -----
            JFrame frame = new JFrame("My Game");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);

            // ----- Models & Views -----
            LevelsManager levelsManager = new LevelsManager();
            MenuView  menuView   = new MenuView();
            LevelsView levelsView = new LevelsView(levelsManager.getLevelConfigs());
            JPanel settingsView  = new JPanel(); // stub
            JPanel shopView      = new JPanel(); // stub

            // ----- Card container -----
            JPanel cards = new JPanel(new CardLayout());
            cards.add(menuView,   "MENU");
            cards.add(levelsView, "LEVELS");
            cards.add(settingsView, "SETTINGS");
            cards.add(shopView,     "SHOP");

            // ----- Controllers -----
            // Create LevelsController first so it can be captured by the lambdas below.
            LevelsController levelsController = new LevelsController(levelsView, cards, levelsManager);

            // Simple callbacks for MenuController; all progress logic stays outside MenuController.
            Runnable onNewGame = () -> {
                LevelsManager.gameStatus.resetNewGame(levelsManager.getLevelConfigs());
                LevelsManager.gameStatus.save();
                levelsController.refreshLocks();
            };

            Runnable onContinue = () -> {
                int idx = levelsManager.firstUnpassedIndex(); // helper from LevelsManager
                if (idx >= 0) {
                    levelsController.startLevelOffline(idx);
                }
            };

            MenuController menuController = new MenuController(menuView, cards, onNewGame, onContinue);

            // ----- Show UI -----
            frame.setContentPane(cards);
            ((CardLayout) cards.getLayout()).show(cards, "MENU");
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
