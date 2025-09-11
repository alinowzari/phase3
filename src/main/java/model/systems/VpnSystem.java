package model.systems;


import model.Line;
import model.Packet;
import model.SystemManager;
import model.packets.*;
import model.ports.*;
import model.System;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;




public class VpnSystem extends System {
    public VpnSystem(Point location, List<InputPort> inputPorts, List<OutputPort> outputPorts, SystemManager systemManager, int id) {
        super(location, inputPorts, outputPorts, systemManager, id);
    }
    public void receivePacket(Packet packet) {
        packet.getLine().removeMovingPacket();
        packet.setLine(null);
        if(packet instanceof BigPacket big){
            handleBigPacketArrival(big);
        }
        if (packet instanceof ProtectedPacket<?> pp) {
            SecretPacket2<?> secret = pp.changePacket();
            secret.setSystemId(id);
            systemManager.removePacket(packet);
            systemManager.addPacket(secret);
            addPacket(secret);
            secret.isNotMoving();
            return;
        }

        /* 2) Wrap ordinary messenger packets → Protected */
        else if (packet instanceof MessengerTag) {
            // cast to intersection type so generics accept it
            ProtectedPacket<?> prot = new ProtectedPacket<>((Packet & MessengerTag) packet);
            systemManager.removePacket(packet);
            systemManager.addPacket(prot);
            prot.setSystemId(id);
            addPacket(prot);
            prot.isNotMoving();
        }
        else{
            addPacket(packet);
            packet.isNotMoving();
        }
        addingCoin(packet);
    }
    public void sendPacket() {

        /* 1 ── nothing to do if we have no packets */
        if (packets.isEmpty()) return;

        Packet packet = packets.get(0);                 // FIFO policy
        if(packet.getDoneMovement())return;
        /* 2 ── partition output ports into compatible / incompatible */
        ArrayList<OutputPort> compatible   = new ArrayList<>();
        ArrayList<OutputPort> incompatible = new ArrayList<>();

        for (OutputPort op : outputPorts) {
            if (isCompatible(packet, op))
                compatible.add(op);
            else
                incompatible.add(op);
        }

        /* 3 ── look for a free line, first among compatible ports */
        OutputPort chosen = firstFreePort(compatible);
        if (chosen == null)                      // fall back to non-compatible
            chosen = firstFreePort(incompatible);

        /* 4 ── if we found one, inject the packet onto the line */
        if (chosen != null) {
            chosen.movePacketThrow(packet);
            packets.remove(packet);
        }
        /* else: every line is busy → leave packet queued */
    }
    @Override
    public void handleBigPacketArrival(BigPacket bigPacket) {
        for(Packet packet : packets) {
            systemManager.removePacket(packet);
        }
        packets.clear();
        packets.add(bigPacket);
        bigPacketCount++;
        if(bigPacketCount==3) {
            systemManager.handleVpnDestruction(id);
            systemManager.removeSystem(this);
        }
    }
    public int getId(){return id;}
}
