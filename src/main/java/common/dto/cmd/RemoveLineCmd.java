// src/main/java/common/dto/cmd/RemoveWireCmd.java
package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
@JsonTypeName("removeLine")
public record RemoveLineCmd(
        long seq,
        int fromSystemId,
        int fromOutputIndex,
        int toSystemId,
        int toInputIndex
) implements  BuildPhaseCmd{}