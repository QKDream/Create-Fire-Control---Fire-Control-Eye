package com.qkdream.firecontrolcompat;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Lets the sculk network hear a ship move through water.
 *
 * <p>Sable pushes the water around a hull itself, but it never raises an event
 * for it, so the wake is read off the structure instead: every tick each
 * sub-level's bounding box centre is compared with where it was, and a hull
 * that is sitting in water and moving puts out a pulse. The same measurement
 * catches the two moments that matter even more than the wake - the hull
 * dropping into the water, and the hull slamming into something, which shows
 * up as the movement of a whole tick collapsing to a stop.</p>
 *
 * <p>Nothing is sent while the hull is beached, airborne or moored, and the
 * pulses are thinned out inside {@link SonicPulse}, so a ship under way sounds
 * exactly as busy as the water she is pushing.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class SableWaterSonic {

    /** One tick of a structure's motion, kept to spot a stop or a splashdown. */
    private static final class Motion {
        private Vec3 center;
        private double speed;
        private boolean inWater;
    }

    private static final Map<UUID, Motion> MOTION = new HashMap<>();

    /** Below this a hull is moored, not under way, and the wake is not worth hearing. */
    private static final double MIN_MOVE = 0.02;
    /** Losing this much of the previous tick's movement is an impact, not a slowdown. */
    private static final double COLLISION_RATIO = 0.4;

    private SableWaterSonic() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            try {
                sweep(level);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sweep(ServerLevel level) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        Level root = container.getLevel() != null ? container.getLevel() : level;
        Set<UUID> seen = new HashSet<>();
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            UUID id = subLevel.getUniqueId();
            if (id == null) {
                continue;
            }
            seen.add(id);
            Vec3 center = centerOf(subLevel);
            if (center == null) {
                continue;
            }
            Motion motion = MOTION.get(id);
            if (motion == null) {
                motion = new Motion();
                MOTION.put(id, motion);
            }
            double speed = motion.center == null ? 0.0 : center.distanceTo(motion.center);
            boolean inWater = touchingWater(root, subLevel, center);
            if (inWater) {
                boolean splashdown = !motion.inWater && speed > MIN_MOVE;
                boolean impact = motion.speed > 0.08 && speed < motion.speed * COLLISION_RATIO;
                if (speed > MIN_MOVE || splashdown || impact) {
                    SonicPulse.splash(level, center, id);
                }
            }
            motion.center = center;
            motion.speed = speed;
            motion.inWater = inWater;
        }
        MOTION.keySet().retainAll(seen);
    }

    /** The world position of the structure's bounding box centre. */
    private static Vec3 centerOf(SubLevel subLevel) {
        try {
            AABB box = subLevel.boundingBox().toMojang();
            return box.getCenter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether the hull's keel is in water, checked at the bottom and the middle. */
    private static boolean touchingWater(Level root, SubLevel subLevel, Vec3 center) {
        try {
            AABB box = subLevel.boundingBox().toMojang();
            return isWater(root, center.x, box.minY + 0.25, center.z)
                    || isWater(root, center.x, center.y, center.z);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWater(Level level, double x, double y, double z) {
        try {
            return level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
