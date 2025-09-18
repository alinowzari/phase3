package common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PacketDTO(
        int id,
        PacketType type,
        int x, int y,
        boolean trojan,
        int size
) {}
