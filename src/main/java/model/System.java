package model;

import model.packets.*;
import model.ports.InputPort;
import model.ports.OutputPort;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public abstract class System {
    protected ArrayList<Packet> packets;
    protected List<InputPort> inputPorts;
    protected List<OutputPort> outputPorts;
    protected Point location;
    protected SystemManager systemManager;
    protected int id;
    protected int bigPacketCount;

    public System(Point location, List<InputPort> inputPorts, List<OutputPort> outputPorts,
                  SystemManager systemManager, int id) {
        packets = new ArrayList<>();
        this.location = location;
        this.inputPorts = inputPorts;
        this.outputPorts = outputPorts;
        this.systemManager = systemManager;
        this.id = id;
    }

    public void handleBigPacketArrival(BigPacket bigPacket) {
        // remove all existing packets
        List<Packet> copy = new ArrayList<>(packets);
        for (Packet packet : copy) systemManager.removePacket(packet);

        // we keep the counter only (your previous behavior)
        packets.clear();
        bigPacketCount++;

        if (bigPacketCount == 3) {
            systemManager.removeSystem(this);
            systemManager.removePacket(bigPacket);
        }
    }

    public void addPacket(Packet packet) { packets.add(packet); }
    public void removePacket(Packet packet) {
        int id = packet.getId();
        for (Packet p : new ArrayList<>(packets)) {
            if (p.getId() == id) { packets.remove(p); break; }
        }
    }

    public List<OutputPort> getOutputPorts() { return outputPorts; }
    public List<InputPort>  getInputPorts()  { return inputPorts;  }
    public int countPackets() { return packets.size(); }
    public ArrayList<Packet> getPackets() { return packets; }

    public abstract void sendPacket();
    public abstract void receivePacket(Packet packet);

    public boolean isCompatible(Packet p, OutputPort op) {
        if (p instanceof ProtectedPacket || p instanceof SecretPacket1 || p instanceof SecretPacket2) return true;
        return op.getType() == p.getType();
    }

    public OutputPort firstFreePort(ArrayList<OutputPort> ports) {
        for (OutputPort op : ports) {
            Line l = op.getLine();
            if (l != null && !l.isOccupied()) return op;
        }
        return null;
    }

    public Point getLocation() { return location; }
    public void setLocation(Point p) { this.location = p; }

    // Java 17 friendly (no pattern switch)
    public void addingCoin(Packet packet) {
        if (packet instanceof SquarePacket) {
            systemManager.addCoin(2);
        } else if (packet instanceof TrianglePacket) {
            systemManager.addCoin(3);
        } else if (packet instanceof InfinityPacket) {
            systemManager.addCoin(1);
        } else if (packet instanceof SecretPacket2<?>) {
            systemManager.addCoin(4);
        } else if (packet instanceof SecretPacket1) {
            systemManager.addCoin(3);
        } else if (packet instanceof ProtectedPacket<?>) {
            systemManager.addCoin(5);
        } else if (packet instanceof BigPacket) {
            systemManager.addCoin(packet.getSize()); // use Packet API, not field access
        } else {
            systemManager.addCoin(1);
        }
    }

    public SystemManager getSystemManager() { return systemManager; }
    public int getId() { return id; }
    public int countOutputPorts() { return outputPorts.size(); }
    public int countInputPorts() { return inputPorts.size(); }
}
