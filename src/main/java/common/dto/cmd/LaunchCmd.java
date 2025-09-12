// src/main/java/common/dto/cmd/LaunchCmd.java
package common.dto.cmd;

import common.dto.cmd.marker.BuildPhaseCmd;

public record LaunchCmd(long seq) implements BuildPhaseCmd {}
