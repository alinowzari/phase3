package mapper;

import common.dto.*;
import model.Line;
import model.Packet;
import model.SystemManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

public final class Mapper {
    private Mapper() {}

    public static StateDTO toState(SystemManager sim) {
        // packets
        var packets = new ArrayList<PacketDTO>(sim.allPackets.size());
        for (Packet p : sim.allPackets) {
            Point pos = p.getPoint();
            int x = (pos != null) ? pos.x : 0;
            int y = (pos != null) ? pos.y : 0;
            String type = (p.getType() != null) ? p.getType().name() : "UNKNOWN";
            packets.add(new PacketDTO(p.getId(), type, x, y, p.hasTrojan(), p.getSize()));
        }

        // lines
        var lines = new ArrayList<LineDTO>(sim.allLines.size());
        for (int i = 0; i < sim.allLines.size(); i++) {
            Line l = sim.allLines.get(i);
            List<PointDTO> path = l.getPath(6).stream()
                    .map(pt -> new PointDTO(pt.x, pt.y))
                    .toList();
            lines.add(new LineDTO(i, path));
        }

        return new StateDTO((int) sim.ctx().tick, packets, lines);
    }
}
