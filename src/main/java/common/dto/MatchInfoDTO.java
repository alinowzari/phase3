// src/main/java/common/dto/MatchInfoDTO.java
package common.dto;

/** Metadata for the current online match; sent each snapshot. */
public record MatchInfoDTO(
        String  roomId,
        String  levelId,
        RoomState state,
        long    tick,
        long    timeLeftMs,
        int     scoreA,
        int     scoreB,
        String  side   // "A" or "B" (who this client is)
) {}
