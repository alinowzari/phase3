package view;

import config.GameConfig;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Displays a button for each level.
 */
// view/LevelsView.java
public final class LevelsView extends JPanel {
    private final java.util.List<JButton> levelButtons = new java.util.ArrayList<>();

    public LevelsView(java.util.List<config.GameConfig> levels) {
        setLayout(new java.awt.GridLayout(0, 3, 8, 8));
        for (int i = 0; i < levels.size(); i++) {
            var cfg = levels.get(i);
            JButton b = new JButton(cfg.levelName());
            // optional but handy:
            b.putClientProperty("levelIndex", i);
            levelButtons.add(b);
            add(b);
        }
    }

    /** Toggle locks based on passed[]; level i is enabled if i==0 or passed[i-1]. */
    public void setLocks(boolean[] passed) {
        for (int i = 0; i < levelButtons.size(); i++) {
            boolean enabled = (i == 0) || (i - 1 < passed.length && passed[i - 1]);
            JButton btn = levelButtons.get(i);
            btn.setEnabled(enabled);
            btn.setToolTipText(enabled ? null : "Locked: finish previous level");
        }
        revalidate();
        repaint();
    }

    // If you need external access:
    public JButton[] getLevelButtons() {
        return levelButtons.toArray(new JButton[0]);
    }
}

