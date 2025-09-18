// MatchInfoDTO.java
package common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchInfoDTO(
        String  roomId,
        String  levelId,
        RoomState state,
        long    tick,
        long    timeLeftMs,
        int     scoreA,
        int     scoreB,
        String  side   // "A" or "B"
) {}
