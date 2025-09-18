// NetSnapshotDTO.java
package common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetSnapshotDTO(
        MatchInfoDTO info,
        StateDTO     state,
        Map<String, Object> ui
) {}
