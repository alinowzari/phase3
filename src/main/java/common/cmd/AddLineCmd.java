package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.cmd.marker.BuildPhaseCmd;

@JsonTypeName("addLine")
public record AddLineCmd(
        long seq,
        int fromSystemId,
        int fromOutputIndex,
        int toSystemId,
        int toInputIndex
) implements   BuildPhaseCmd{}