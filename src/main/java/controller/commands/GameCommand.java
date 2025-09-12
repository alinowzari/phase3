// controller/GameCommands.java
package controller.commands;

import model.Line;
import model.ports.InputPort;
import model.ports.OutputPort;

public interface GameCommand {
    void launch();
    boolean spendCoins(int amount);

    boolean canCreateWire(OutputPort out, InputPort in);
    boolean canAffordDelta(int deltaPx);
    void applyWireDelta(int deltaPx);

    void addLine(Line line);
    void removeLine(Line line);
}
