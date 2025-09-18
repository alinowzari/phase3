package server.ops;

import common.AbilityType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public final class Arsenal {
    public static final int COOLDOWN_MS = 10_000;

    // simple per-ability ammo
    public final EnumMap<AbilityType,Integer> ammo = new EnumMap<>(AbilityType.class);

    // per-controlled-system cooldown (systemId -> epoch ms)
    public final Map<Integer, Long> cooldownUntil = new HashMap<>();

    public Arsenal(int penia, int aergia, int boost) {
        ammo.put(AbilityType.WRATH_OF_PENIA,  penia);
        ammo.put(AbilityType.WRATH_OF_AERGIA, aergia);
        ammo.put(AbilityType.SPEED_BOOST,     boost);
    }

    public boolean take(AbilityType a) {
        int left = ammo.getOrDefault(a, 0);
        if (left <= 0) return false;
        ammo.put(a, left - 1);
        return true;
    }

    public boolean onCooldown(int systemId, long nowMs) {
        return cooldownUntil.getOrDefault(systemId, 0L) > nowMs;
    }

    public void arm(int systemId, long nowMs) {
        cooldownUntil.put(systemId, nowMs + COOLDOWN_MS);
    }
}
