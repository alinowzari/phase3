package common.dto;
import java.util.List;

public record LineDTO(
        int fromSystemId,
        int fromOutputIndex,
        int toSystemId,
        int toInputIndex,
        List<PointDTO> path
) {}
