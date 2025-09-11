package model.systems;

import model.*;
import model.System;
import model.packets.BigPacket;
import model.packets.ProtectedPacket;
import model.packets.SecretPacket1;
import model.packets.SecretPacket2;
import model.ports.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.Random;
import java.util.List;

public class SpySystem extends System {
    private final Random rng;

    public SpySystem(Point location, List<InputPort> inputPorts, List<OutputPort> outputPorts, SystemManager systemManager, int id) {
        super(location, inputPorts, outputPorts, systemManager, id);
        this.rng = systemManager.getRng();
    }

    public void receivePacket(Packet packet) {
        packet.getLine().removeMovingPacket();
        packet.setLine(null);
        if(packet instanceof BigPacket big){
            handleBigPacketArrival(big);
        }
        if (packet instanceof SecretPacket2 || packet instanceof SecretPacket1) {
            systemManager.removePacket(packet);
            packets.remove(packet);
            return;
        }
        else if(isSpecialShape(packet)) {
            SpySystem spy=systemManager.getAllSpySystems().get(rng.nextInt(systemManager.getAllSpySystems().size()));
            spy.addPacket(packet);
            packet.isNotMoving();
            addingCoin(packet);
            spy.sendPacket();
        }
        else {
            addPacket(packet);
            packet.isNotMoving();
            addingCoin(packet);
        }
    }
    public void sendPacket() {
        // identical routing logic to NormalSystem
        if (packets.isEmpty()) {
            return;
        }
        Packet packet = packets.get(0);

        ArrayList<OutputPort> compatible   = new ArrayList<>();
        ArrayList<OutputPort> incompatible = new ArrayList<>();

        for (OutputPort op : outputPorts) {
            if (isCompatible(packet, op)) compatible.add(op);
            else                         incompatible.add(op);
        }

        OutputPort chosen = firstFreePort(compatible);
        if (chosen == null) chosen = firstFreePort(incompatible);

        if (chosen != null) {
            chosen.movePacketThrow(packet);
            packets.remove(packet);
        }
    }

    private boolean isSpecialShape(Packet packet) {
        Type t = packet.getType();
        if(t.equals(Type.SQUARE) || t.equals(Type.TRIANGLE) || t.equals(Type.INFINITY)) {
            return true;
        }
        return false;
    }
}
