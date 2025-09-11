package view;

import javax.swing.*;
import java.awt.*;

/**
 * The main menu view with three navigation buttons.
 */

import javax.swing.*;
import java.awt.*;

/**
 * The main menu view with three large navigation buttons.
 */
public class MenuView extends JPanel {
    private final JButton levelsButton;
    private final JButton settingsButton;
    private final JButton shopButton;
    private final JButton newGameButton = new JButton("New Game");
    private final JButton continueButton = new JButton("Continue");
    public MenuView() {
        // BorderLayout for main menu layout
        setLayout(new BorderLayout());
        setBackground(new Color(45, 45, 60));
        setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));

        // Title at top
        JLabel title = new JLabel("Main Menu", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 36));
        title.setForeground(Color.WHITE);
        add(title, BorderLayout.NORTH);

        // Center panel with 3 buttons
        JPanel center = new JPanel(new GridLayout(3, 1, 20, 20));
        center.setOpaque(false);

        levelsButton   = createMenuButton("Levels");
        settingsButton = createMenuButton("Settings");
        shopButton     = createMenuButton("Shop");

        center.add(levelsButton);
        center.add(settingsButton);
        center.add(shopButton);
        add(center, BorderLayout.CENTER);
    }

    private JButton createMenuButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Arial", Font.PLAIN, 24));
        btn.setPreferredSize(new Dimension(300, 80));
        btn.setBackground(new Color(0, 0, 0));
        btn.setForeground(Color.BLACK);
        btn.setFocusPainted(false);
        return btn;
    }

    public JButton getLevelsButton() {
        return levelsButton;
    }

    public JButton getSettingsButton() {
        return settingsButton;
    }

    public JButton getShopButton() {
        return shopButton;
    }
    public JButton getNewGameButton() { return newGameButton; }
    public JButton getContinueButton() { return continueButton;}
    }
