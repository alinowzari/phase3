// src/main/java/common/dto/cmd/ReadyCmd.java
package common.dto.cmd;

public record ReadyCmd(long seq) implements ClientCommand {}
