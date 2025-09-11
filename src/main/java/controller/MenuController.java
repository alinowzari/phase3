package controller;

import view.MenuView;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class MenuController {
    private static final String CARD_LEVELS   = "LEVELS";
    private static final String CARD_SETTINGS = "SETTINGS";
    private static final String CARD_SHOP     = "SHOP";

    private final MenuView view;
    private final JPanel cards;
    private final CardLayout cardLayout;

    private final Runnable onNewGame;   // may be null
    private final Runnable onContinue;  // may be null

    public MenuController(MenuView view, JPanel cards,
                          Runnable onNewGame,
                          Runnable onContinue) {
        this.view = Objects.requireNonNull(view, "view");
        this.cards = Objects.requireNonNull(cards, "cards");
        LayoutManager lm = cards.getLayout();
        if (!(lm instanceof CardLayout cl)) {
            throw new IllegalArgumentException("cards must use CardLayout");
        }
        this.cardLayout = cl;
        this.onNewGame = onNewGame;
        this.onContinue = onContinue;
        initListeners();
    }

    public MenuController(MenuView view, JPanel cards) {
        this(view, cards, null, null);
    }

    private void initListeners() {
        view.getNewGameButton().addActionListener(e -> {
            if (onNewGame != null) onNewGame.run();
            cardLayout.show(cards, CARD_LEVELS);
        });
        view.getContinueButton().addActionListener(e -> {
            if (onContinue != null) onContinue.run();
            cardLayout.show(cards, CARD_LEVELS);
        });
        view.getLevelsButton().addActionListener(e -> cardLayout.show(cards, CARD_LEVELS));
        view.getSettingsButton().addActionListener(e -> cardLayout.show(cards, CARD_SETTINGS));
        view.getShopButton().addActionListener(e -> cardLayout.show(cards, CARD_SHOP));
    }
}
