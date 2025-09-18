package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.PointDTO;
import common.cmd.marker.BuildPhaseCmd;
@JsonTypeName("addBend")
public record AddBendCmd(long seq,
                         int fromSystemId, int fromOutputIndex,
                         int toSystemId, int toInputIndex,
                         PointDTO footA,
                         PointDTO middle,
                         PointDTO footB) implements  BuildPhaseCmd {}