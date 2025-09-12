package common.dto.cmd;

import common.dto.cmd.marker.BuildPhaseCmd;

public record MoveBendCmd(long seq,
                          int fromSystemId, int fromOutputIndex,
                          int toSystemId, int toInputIndex,
                          int bendIndex,
                          common.dto.PointDTO newMiddle) implements BuildPhaseCmd {}
