package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;
@JsonTypeName("addBend")
public record AddBendCmd(long seq,
                         int fromSystemId, int fromOutputIndex,
                         int toSystemId, int toInputIndex,
                         common.dto.PointDTO footA,
                         common.dto.PointDTO middle,
                         common.dto.PointDTO footB) implements  BuildPhaseCmd {}