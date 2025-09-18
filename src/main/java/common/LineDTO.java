package common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LineDTO(
        int fromSystemId,
        int fromOutputIndex,
        int toSystemId,
        int toInputIndex,
        List<PointDTO> path,
        List<BendDTO> bends
) {}
