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
import java.util.HashMap;

public class MergerSystem extends System{
    private final HashMap<Integer, ArrayList<BitPacket>> bins = new HashMap<>();
    public MergerSystem(Point location, List<InputPort> inputPorts, List<OutputPort> outputPorts, SystemManager systemManager, int id) {
        super(location, inputPorts, outputPorts, systemManager, id);

    }
    public void receivePacket(Packet packet) {
        packet.getLine().removeMovingPacket();
        packet.setLine(null);
        if(packet instanceof BigPacket big){
            handleBigPacketArrival(big);
        }
        if (packet instanceof BitPacket bit) {
            bins.computeIfAbsent(bit.getParentId(), k -> new ArrayList<>())
                    .add(bit);
            checkMerge(bit.getParentId());
            return;     // don’t queue – they’re consumed
        }

        packets.add(packet); // ordinary packet;
        addingCoin(packet);
    }
    // MergerSystem.java
// MergerSystem.java
    private void checkMerge(int parentId) {

        ArrayList<BitPacket> family = bins.get(parentId);
        if (family == null || family.isEmpty()) return;

        int expected = family.get(0).getParentLength();
        if (family.size() < expected) return;            // wait for more bits

        /* ----- all pieces present: remove fragments from model ----- */
        for (BitPacket bp : family){
            systemManager.removePacket(bp);
        }

        /* ----- reconstruct the correct concrete Big-packet ---------- */
        int colour = family.get(0).getColorId();
        Packet rebuilt;
        if (family.get(0).getParentLength()==8) {
            rebuilt = new BigPacket1(colour);
        }
        else  {
            rebuilt = new BigPacket2(colour);
        }

        systemManager.addPacket(rebuilt);   // global registry
        packets.add(rebuilt);               // queue locally for routing
        bins.remove(parentId);              // bin done
    }


    public void sendPacket() {

        /* 1 ── nothing to do if we have no packets */
        if (packets.isEmpty()) return;

        Packet packet = packets.get(0);                 // FIFO policy

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
}
