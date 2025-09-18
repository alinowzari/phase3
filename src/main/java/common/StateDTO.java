// StateDTO.java  (only change: tick -> long, plus ignoreUnknown)
package common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StateDTO(
        long tick,
        List<PacketDTO> packets,
        List<LineDTO>   lines,
        List<SystemDTO> systems
) {}
