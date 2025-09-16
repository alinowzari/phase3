// src/main/java/common/dto/cmd/ReadyCmd.java
package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
@JsonTypeName("ready")
public record ReadyCmd(long seq) implements   BuildPhaseCmd {}
