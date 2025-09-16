package common.dto.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.dto.cmd.marker.ActivePhaseCmd;
import common.dto.cmd.marker.BuildPhaseCmd;

@JsonTypeName("addLine")
public record AddLineCmd(
        long seq,
        int fromSystemId,
        int fromOutputIndex,
        int toSystemId,
        int toInputIndex
) implements   BuildPhaseCmd{}