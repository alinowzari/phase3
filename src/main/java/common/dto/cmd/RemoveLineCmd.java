// src/main/java/common/dto/cmd/RemoveWireCmd.java
package common.dto.cmd;

import common.dto.cmd.marker.BuildPhaseCmd;

public record RemoveLineCmd(long seq, int fromSystemId, int fromOutputIndex, int toSystemId, int toInputIndex) implements BuildPhaseCmd {}