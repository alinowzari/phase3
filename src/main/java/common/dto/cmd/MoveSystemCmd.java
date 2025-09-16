// src/main/java/common/dto/cmd/MoveSystemCmd.java
package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
@JsonTypeName("moveSystem")
public record MoveSystemCmd(
        long seq,
        int  systemId,
        int  x, int y
) implements BuildPhaseCmd {}
