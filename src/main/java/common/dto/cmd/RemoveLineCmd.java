// src/main/java/common/dto/cmd/RemoveWireCmd.java
package common.dto.cmd;

public record RemoveLineCmd(long seq,int fromSystemId,int fromOutputIndex,int toSystemId,int toInputIndex) implements ClientCommand {}