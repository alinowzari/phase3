package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
@JsonTypeName("moveBend")
public record MoveBendCmd(long seq,
                          int fromSystemId, int fromOutputIndex,
                          int toSystemId, int toInputIndex,
                          int bendIndex,
                          common.dto.PointDTO newMiddle) implements BuildPhaseCmd {}
