package common.dto;

public record PacketDTO(
        int id,
        String type,
        int x, int y,
        boolean trojan,
        int size
) {}
