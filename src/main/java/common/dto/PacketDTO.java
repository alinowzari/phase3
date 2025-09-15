package common.dto;

public record PacketDTO(
        int id,
        PacketType type,
        int x, int y,
        boolean trojan,
        int size
) {}
