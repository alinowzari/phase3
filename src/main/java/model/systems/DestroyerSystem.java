package model.systems;

import model.Line;
import model.Packet;
import model.SystemManager;
import model.packets.BigPacket;
import model.ports.*;
import model.System;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class DestroyerSystem extends System {
    private final Random rng;
    private static final float TROJAN_PROBABILITY = 0.1f;

    public DestroyerSystem(Point location, List<InputPort> inputPorts, List<OutputPort> outputPorts, SystemManager systemManager, int id) {
        super(location, inputPorts, outputPorts, systemManager, id);
        this.rng = systemManager.getRng();
    }
//    @Override
    public void receivePacket(Packet packet) {
        packet.getLine().removeMovingPacket();
        packet.setLine(null);
        if(packet instanceof BigPacket big){
            handleBigPacketArrival(big);
        }
        packet.incNoise();
        if(packet.getSize()<=0){
            systemManager.removePacket(packet);
            return;
        }

        // 2) sometimes trojan it
        if (rng.nextFloat() < TROJAN_PROBABILITY) {
            packet.isTrojan();
        }

        addPacket(packet);
        packet.isNotMoving();
        addingCoin(packet);
    }
    @Override
    public void sendPacket() {
        if (packets.isEmpty()) return;

        Packet packet = packets.get(0);  // FIFO

        // 1) partition ports: incompatible first
        ArrayList<OutputPort> incompatible = new ArrayList<>();
        ArrayList<OutputPort> compatible   = new ArrayList<>();

        for (OutputPort op : outputPorts) {
            if (isCompatible(packet, op)) compatible.add(op);
            else                            incompatible.add(op);
        }

        // 2) pick a free incompatible port, else compatible
        OutputPort chosen = firstFreePort(incompatible);
        if (chosen == null) {
            chosen = firstFreePort(compatible);
        }

        // 3) if it’s a “wrong” port, let the packet record that
        if (chosen != null) {
            if (incompatible.contains(chosen)) {
                packet.wrongPort(chosen);
            }
            chosen.movePacketThrow(packet);
            packets.remove(packet);
        }
        // else: leave it queued until some port frees up
    }
}
