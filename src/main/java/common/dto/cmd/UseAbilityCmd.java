// src/main/java/common/dto/cmd/UseAbilityCmd.java
package common.dto.cmd;

import common.dto.AbilityType;

public record UseAbilityCmd(long seq,
                            AbilityType ability,
                            int fromSystemId, int fromOutputIndex,
                            int toSystemId,   int toInputIndex,
                            common.dto.PointDTO at) implements ClientCommand {}