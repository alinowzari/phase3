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

public class AntiTrojanSystem extends System {
    private static final float DETECTION_RADIUS = 50f;
    private static final float COOLDOWN_TIME = 2.0f;
    private float cooldownRemaining = 0f;
    public static float dt=1f / 60f;

    public AntiTrojanSystem(Point location, List<InputPort> inputPorts, List<OutputPort> outputPorts, SystemManager systemManager, int id) {
        super(location, inputPorts, outputPorts, systemManager, id);
    }
    public void receivePacket(Packet packet) {
        packet.getLine().removeMovingPacket();
        packet.setLine(null);
        if(packet instanceof BigPacket big){
            handleBigPacketArrival(big);
        }
        addPacket(packet);
        packet.isNotMoving();
        addingCoin(packet);
    }

    public void cleanTrojan(float dt) {
        // tick down cooldown
        cooldownRemaining = Math.max(0f, cooldownRemaining - dt);
        if (cooldownRemaining > 0f) return;

        for (Packet p : packets) {
            if (p.hasTrojan() && p.getPoint() != null
                    && location.distance(p.getPoint()) <= DETECTION_RADIUS) {
                p.isNotTrojan();
                cooldownRemaining = COOLDOWN_TIME;
                break;  // only one clean per cooldown
            }
        }
    }

    @Override
    public void sendPacket() {
        // pure routing: try incompatible ports first, then compatible
        if (packets.isEmpty()) return;

        Packet head = packets.get(0);

        ArrayList<OutputPort> incompatible = new ArrayList<>();
        ArrayList<OutputPort> compatible   = new ArrayList<>();

        for (OutputPort op : outputPorts) {
            if (isCompatible(head, op)) compatible.add(op);
            else                         incompatible.add(op);
        }

        OutputPort chosen = firstFreePort(incompatible);
        if (chosen == null) chosen = firstFreePort(compatible);

        if (chosen != null) {
            chosen.movePacketThrow(head);
            packets.remove(head);
        }
    }
}
