// src/main/java/common/dto/cmd/MoveSystemCmd.java
package common.dto.cmd;

public record MoveSystemCmd(
        long seq,
        int  systemId,
        int  x, int y
) implements ClientCommand {}
