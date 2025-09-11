package common.dto;

import java.util.List;

public record StateDTO(
        int tick,
        List<PacketDTO> packets,
        List<LineDTO> lines
) {}