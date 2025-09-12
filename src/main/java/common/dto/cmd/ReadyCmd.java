// src/main/java/common/dto/cmd/ReadyCmd.java
package common.dto.cmd;

import common.dto.cmd.marker.BuildPhaseCmd;

public record ReadyCmd(long seq) implements BuildPhaseCmd {}
