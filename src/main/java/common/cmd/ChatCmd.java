package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.cmd.marker.AnyPhaseCmd;

@JsonTypeName("chat")
public record ChatCmd(long seq, String text) implements AnyPhaseCmd {}