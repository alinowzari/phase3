// src/main/java/common/dto/cmd/AddWireCmd.java
package common.dto.cmd;

public record AddLineCmd(long seq,int fromSystemId,int fromOutputIndex,int toSystemId,int toInputIndex) implements ClientCommand {}