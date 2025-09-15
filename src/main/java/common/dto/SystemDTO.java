// common/dto/SystemDTO.java
package common.dto;

import java.util.List;

public record SystemDTO(
        int id, int x, int y ,
        SystemType type,
        int countPackets,
        int inputs,         // count of input ports
        int outputs,
        List<PortType> inputTypes,   // NEW
        List<PortType> outputTypes
) {}
