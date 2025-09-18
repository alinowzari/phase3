// src/main/java/common/dto/cmd/MoveSystemCmd.java
package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.cmd.marker.BuildPhaseCmd;
@JsonTypeName("moveSystem")
public record MoveSystemCmd(
        long seq,
        int  systemId,
        int  x, int y
) implements BuildPhaseCmd {}
