// src/main/java/common/dto/cmd/RemoveWireCmd.java
package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.cmd.marker.BuildPhaseCmd;
@JsonTypeName("removeLine")
public record RemoveLineCmd(
        long seq,
        int fromSystemId,
        int fromOutputIndex,
        int toSystemId,
        int toInputIndex
) implements  BuildPhaseCmd{}