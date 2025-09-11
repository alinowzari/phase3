package common.dto.cmd;

public record MoveBendCmd(long seq,
                          int fromSystemId, int fromOutputIndex,
                          int toSystemId,   int toInputIndex,
                          int bendIndex,
                          common.dto.PointDTO newMiddle) implements ClientCommand {}
