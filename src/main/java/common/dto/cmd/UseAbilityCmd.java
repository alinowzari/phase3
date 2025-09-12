// src/main/java/common/dto/cmd/UseAbilityCmd.java
package common.dto.cmd;

import common.dto.AbilityType;
import common.dto.cmd.marker.ActivePhaseCmd;

public record UseAbilityCmd(long seq,
                            AbilityType ability,
                            int fromSystemId, int fromOutputIndex,
                            int toSystemId,   int toInputIndex,
                            common.dto.PointDTO at) implements ActivePhaseCmd {}