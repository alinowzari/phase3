// mapper/Mapper.java
package mapper;

import common.dto.*;
import model.*;
import model.System;
import model.packets.*;
import model.ports.InputPort;
import model.ports.OutputPort;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public final class Mapper {
    private Mapper() {}

    /* ------------ public API ------------ */
    public static StateDTO toState(SystemManager sm) {
        var packets = toPacketDTOs(sm.allPackets);
        var lines   = toLineDTOs(sm.allLines);
        var systems = toSystemDTOs(sm.getAllSystems()); // if StateDTO now includes systems
        return new StateDTO((int) sm.ctx().tick, packets, lines, systems);
    }

    public static List<PacketDTO> toPacketDTOs(List<Packet> ps) {
        var out = new ArrayList<PacketDTO>(ps.size());
        for (Packet p : ps) {
            Point pt = p.getPoint();
            int x = (pt != null) ? pt.x : 0;
            int y = (pt != null) ? pt.y : 0;
            out.add(new PacketDTO(
                    p.getId(),
                    packetType(p),     // enum PacketType
                    x, y,
                    p.hasTrojan(),
                    p.getSize()
            ));
        }
        return out;
    }

    public static List<LineDTO> toLineDTOs(List<Line> ls) {
        var out = new ArrayList<LineDTO>(ls.size());
        for (Line l : ls) {
            OutputPort op = l.getStart();
            InputPort  ip = l.getEnd();

            var fromSys  = op.getParentSystem();
            var toSys    = ip.getParentSystem();
            int fromId   = fromSys.getId();
            int toId     = toSys.getId();
            int outIdx   = fromSys.getOutputPorts().indexOf(op);
            int inIdx    = toSys.getInputPorts().indexOf(ip);

            List<PointDTO> path = l.getPath(6).stream()
                    .map(p -> new PointDTO(p.x, p.y))
                    .toList();

            out.add(new LineDTO(fromId, outIdx, toId, inIdx, path));
        }
        return out;
    }

    public static List<SystemDTO> toSystemDTOs(List<System> systems) {
        var out = new ArrayList<SystemDTO>(systems.size());
        for (System s : systems) {
            var loc = s.getLocation();
            out.add(new SystemDTO(
                    s.getId(),
                    loc.x, loc.y,
                    systemKind(s),     // enum SystemType if you adopted it
                    s.countPackets(),
                    s.countInputPorts(),
                    s.countOutputPorts(),
                    toPortTypes(s.getInputPorts()),
                    toPortTypes(s.getOutputPorts())
            ));
        }
        return out;
    }

    /* ------------ helpers ------------ */
    private static PacketType packetType(Packet p) {
        if (p instanceof SquarePacket)         return PacketType.SQUARE;
        if (p instanceof TrianglePacket)       return PacketType.TRIANGLE;
        if (p instanceof InfinityPacket)       return PacketType.INFINITY;
        if (p instanceof BitPacket)            return PacketType.BIT;
        if (p instanceof BigPacket) {
            // map however you defined your enum
            return ((BigPacket) p).getSize() <= 8 ? PacketType.BIG1 : PacketType.BIG2;
        }
        if (p instanceof ProtectedPacket<?>)   return PacketType.PROTECTED;
        if (p instanceof SecretPacket1)        return PacketType.SECRET1;
        if (p instanceof SecretPacket2<?>)     return PacketType.SECRET2;
        return PacketType.UNKNOWN;
    }

    private static SystemType systemKind(System s) {
        if (s instanceof model.systems.NormalSystem)       return SystemType.NORMAL;
        if (s instanceof model.systems.ReferenceSystem)    return SystemType.REFERENCE;
        if (s instanceof model.systems.SpySystem)          return SystemType.SPY;
        if (s instanceof model.systems.VpnSystem)          return SystemType.VPN;
        if (s instanceof model.systems.AntiTrojanSystem)   return SystemType.ANTI_TROJAN;
        if (s instanceof model.systems.DestroyerSystem)    return SystemType.DESTROYER;
        if (s instanceof model.systems.DistributionSystem) return SystemType.DISTRIBUTION;
        if (s instanceof model.systems.MergerSystem)       return SystemType.MERGER;
        return SystemType.UNKNOWN;
    }
    public static List<PortType> toPortTypes(List<? extends Port> ps) {
        List<PortType> out = new ArrayList<>(ps.size());
        for (Port p : ps) {
            out.add(mapPortType(p.getType())); // direct map; no if/else needed
        }
        return out;
    }
    public static PortType mapPortType(Type p) {
        switch (p) {
            case INFINITY:
                return PortType.INFINITY;
            case TRIANGLE:
                return PortType.TRIANGLE;
            case SQUARE:
                return PortType.SQUARE;

                default: return PortType.SQUARE;
        }
    }
}
