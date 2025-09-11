package model.ports;

import model.Packet;
import model.Port;
import model.Type;
import java.awt.*;
import model.System;

public class OutputPort extends Port {
    public OutputPort(System system, Point location) {
        super(system, location);
    }
    public void movePacketThrow(Packet packet) {
        if (line == null || packet.getDoneMovement()) return;             // not wired

        packet.beginTraversal(line, getCenter());
        line.setMovingPacket(packet);
    }
}
