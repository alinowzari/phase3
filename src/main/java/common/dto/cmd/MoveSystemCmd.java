// src/main/java/common/dto/cmd/MoveSystemCmd.java
package common.dto.cmd;

import common.dto.cmd.marker.BuildPhaseCmd;

public record MoveSystemCmd(
        long seq,
        int  systemId,
        int  x, int y
) implements BuildPhaseCmd {}
