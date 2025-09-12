package common.dto.cmd;

import common.dto.cmd.marker.AnyPhaseCmd;

public record ChatCmd(long seq, String text) implements AnyPhaseCmd {}
