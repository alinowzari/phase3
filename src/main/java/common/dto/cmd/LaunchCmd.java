// src/main/java/common/dto/cmd/LaunchCmd.java
package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
@JsonTypeName("launch")
public record LaunchCmd(long seq) implements   BuildPhaseCmd{}
