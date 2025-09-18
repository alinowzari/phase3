// src/main/java/common/dto/cmd/UseAbilityCmd.java
package common.cmd;

import com.fasterxml.jackson.annotation.JsonTypeName;
import common.AbilityType;
import common.PointDTO;
import common.cmd.marker.ActivePhaseCmd;
@JsonTypeName("useAbility")
public record UseAbilityCmd(long seq,
                            AbilityType ability,
                            int fromSystemId, int fromOutputIndex,
                            int toSystemId,   int toInputIndex,
                            PointDTO at) implements ActivePhaseCmd {}