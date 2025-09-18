// common/dto/SystemDTO.java
package common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;


@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemDTO(
        int id, int x, int y ,
        SystemType type,
        int countPackets,
        int inputs,         // count of input ports
        int outputs,
        List<PortType> inputTypes,   // NEW
        List<PortType> outputTypes,
        List<PacketType> queuePreview
) {}
