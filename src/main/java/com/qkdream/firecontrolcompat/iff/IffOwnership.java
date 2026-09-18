package com.qkdream.firecontrolcompat.iff;

import com.qkdream.firecontrolcompat.PhysicsGroups;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Remembers which Sable structure a round of ordnance came from, so ammunition
 * keeps the friend-or-foe marking of the platform that released it even after
 * it has flown out of the structure.
 */
public final class IffOwnership {

    private static final long TTL_TICKS = 1200L;
    /** Short cache for "no structure owns this round", so open-air munitions skip the hull scan. */
    private static final long MISS_TTL_TICKS = 40L;
    private static final int SWEEP_THRESHOLD = 512;
    private static final Map<UUID, Entry> OWNERS = new ConcurrentHashMap<>();

    private IffOwnership() {
    }

    /**
     * Called when a launcher on a Sable structure releases a projectile: the
     * projectile's own block position stops being inside the structure the
     * moment it clears the hull, so the carrier has to be pinned at launch.
     */
    public static void remember(Entity entity, UUID subLevelId) {
        if (entity == null || subLevelId == null) {
            return;
        }
        try {
            long now = entity.level().getGameTime();
            OWNERS.put(entity.getUUID(), new Entry(subLevelId, now + TTL_TICKS));
        } catch (Throwable ignored) {
        }
    }

    public static UUID subLevelOf(ServerLevel level, Entity entity) {
        if (entity == null) {
            return null;
        }
        UUID id = entity.getUUID();
        long now = level.getGameTime();
        Entry entry = OWNERS.get(id);
        if (entry != null && entry.expiresAt() >= now) {
            return entry.subLevel();
        }
        UUID subLevel = resolve(level, entity);
        OWNERS.put(id, new Entry(subLevel, now + (subLevel == null ? MISS_TTL_TICKS : TTL_TICKS)));
        if (OWNERS.size() > SWEEP_THRESHOLD) {
            OWNERS.entrySet().removeIf(candidate -> candidate.getValue().expiresAt() < now);
        }
        return subLevel;
    }

    private static UUID resolve(ServerLevel level, Entity entity) {
        UUID host = containingStructure(level, entity);
        if (host != null) {
            return host;
        }
        UUID own = IffTransponderBlockEntity.containingSubLevelId(entity.level(), entity.blockPosition());
        return own != null ? own : IffTransponderBlockEntity.containingSubLevelId(level, entity.blockPosition());
    }

    /**
     * The structure the round is currently sitting inside, which is the platform
     * that launched it or the sub-structure it was released from. Ordnance
     * clears the hull within a few ticks, so the first contact after release
     * pins the owner for the rest of the flight. Transponder-less hulls are
     * still reported, which leaves their ordnance unlabelled as intended.
     */
    private static UUID containingStructure(ServerLevel level, Entity entity) {
        try {
            long gameTime = level.getGameTime();
            UUID fallback = null;
            for (UUID host : PhysicsGroups.bodiesContaining(level, entity.position(), 0.0)) {
                if (IffTransponderBlockEntity.presenceInSubLevel(gameTime, level, host).present()) {
                    return host;
                }
                if (fallback == null) {
                    fallback = host;
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }
    private record Entry(UUID subLevel, long expiresAt) {
    }
}