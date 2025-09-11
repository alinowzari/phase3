package common.dto;

import java.util.List;

public record LineDTO(
        int id,
        List<PointDTO> path
) {}