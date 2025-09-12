// controller/LocalGameCommands.java
package controller.commands;

import model.Line;
import model.SystemManager;
import model.ports.InputPort;
import model.ports.OutputPort;

public final class LocalGameCommand implements GameCommand {
    private final SystemManager sm;
    public LocalGameCommand(SystemManager sm) { this.sm = sm; }

    @Override public void launch() { sm.launchPackets(); }
    @Override public boolean spendCoins(int amount) { return sm.spendTotalCoins(amount); }

    @Override public boolean canCreateWire(OutputPort out, InputPort in) { return sm.canCreateWire(out, in); }
    @Override public boolean canAffordDelta(int deltaPx) { return sm.canAffordDelta(deltaPx); }
    @Override public void applyWireDelta(int deltaPx) { sm.applyWireDelta(deltaPx); }

    @Override public void addLine(Line line) { sm.addLine(line); }
    @Override public void removeLine(Line line) { sm.removeLine(line); }
}
