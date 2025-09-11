// src/main/java/common/dto/NetSnapshotDTO.java
package common.dto;

import java.util.Map;

/** What the server sends every tick: your existing StateDTO + match meta + optional UI. */
public record NetSnapshotDTO(
        MatchInfoDTO info,
        StateDTO     state,
        Map<String, Object> ui // cooldowns, ammo, flags; optional
) {}
