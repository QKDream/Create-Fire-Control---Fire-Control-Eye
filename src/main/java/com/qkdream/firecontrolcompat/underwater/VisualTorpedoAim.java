package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.SpyglassMarkRay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where 面包学's visually guided round is told to swim.
 *
 * <p>The mod's own walk is what answers: the world's own sight line stops at the
 * blocks the world holds, and a Sable hull is not one of them, so a round aimed
 * at a ship was really aimed at whatever lay beyond her - the sea bed, most
 * often, which is where the run then went. {@link SpyglassMarkRay} walks the
 * hulls as well, so the line lands on the ship and the run is laid on her.</p>
 *
 * <p>World terrain still wins when it is in the way: a hull behind a hill is not
 * a target the round could reach anyway.</p>
 */
public final class VisualTorpedoAim {

    /** How much closer the world has to be than a hull for the world to win. */
    private static final double TERRAIN_MARGIN = 1.5D;

    /** Whose eyes the sight line was read from, taken just before the walk. */
    private static Entity gunner;

    private VisualTorpedoAim() {
    }

    /** Remembers the eyes the sight line belongs to. */
    public static void remember(Entity owner) {
        gunner = owner;
    }

    /** The sight line as the round should read it: through the world's blocks and the hulls alike. */
    public static BlockHitResult aim(Level level, ClipContext context) {
        if (level == null || context == null) {
            return BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO);
        }
        BlockHitResult plain = level.clip(context);
        Vec3 eye = context.getFrom();
        Vec3 span = context.getTo().subtract(eye);
        double reach = span.length();
        if (reach < 1.0E-6D) {
            return plain;
        }
        Vec3 point = hullAlong(level, eye, span, reach);
        if (point == null) {
            return plain;
        }
        if (point.subtract(eye).length() > plain.getLocation().subtract(eye).length() + TERRAIN_MARGIN) {
            return plain;
        }
        return new BlockHitResult(point, Direction.UP, BlockPos.containing(point), false);
    }

    /** Where the line lands on somebody's hull, or null when it lands on none. */
    private static Vec3 hullAlong(Level level, Vec3 eye, Vec3 span, double reach) {
        try {
            Vec3 feet = gunner == null ? eye : gunner.position();
            SpyglassMarkRay.Result found = SpyglassMarkRay.find(level, eye, span, feet, reach);
            return found == null || found.hit() == null ? null : found.hit().point();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
