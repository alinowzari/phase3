package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.PointDTO;
import common.cmd.marker.BuildPhaseCmd;
@JsonTypeName("moveBend")
public record MoveBendCmd(long seq,
                          int fromSystemId, int fromOutputIndex,
                          int toSystemId, int toInputIndex,
                          int bendIndex,
                          PointDTO newMiddle) implements BuildPhaseCmd {}
