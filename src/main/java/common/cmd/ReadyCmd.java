// src/main/java/common/dto/cmd/ReadyCmd.java
package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.cmd.marker.BuildPhaseCmd;
@JsonTypeName("ready")
public record ReadyCmd(long seq) implements   BuildPhaseCmd {}
