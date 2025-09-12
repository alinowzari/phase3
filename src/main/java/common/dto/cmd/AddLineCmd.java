// src/main/java/common/dto/cmd/AddWireCmd.java
package common.dto.cmd;

import common.dto.cmd.marker.BuildPhaseCmd;

public record AddLineCmd(long seq, int fromSystemId, int fromOutputIndex, int toSystemId, int toInputIndex) implements BuildPhaseCmd {}