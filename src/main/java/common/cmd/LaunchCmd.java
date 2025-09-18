package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.cmd.marker.BuildPhaseCmd;
@JsonTypeName("launch")
public record LaunchCmd(long seq) implements   BuildPhaseCmd{}
