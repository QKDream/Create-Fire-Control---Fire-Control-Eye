package com.qkdream.firecontrolcompat.laser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Whether the line from a pod to the spot it is painting is clear.
 *
 * <p>An optical pod can look at a point it cannot actually reach with light: a
 * hill, a building or a smoke screen in between is not something the pod's own
 * aim point knows about. This walks that line, and the laser spot is only lit
 * when nothing stands in the way.</p>
 */
public final class LaserSeeker {

    /** Sampling step of the line-of-sight walk, in blocks. */
    private static final double SAMPLE_STEP = 0.5D;

    /** How far from either end the walk ignores blocks, so it never reads its own ends. */
    private static final double END_MARGIN = 0.75D;

    private LaserSeeker() {
    }

    /**
     * Whether a spot could actually be reached with light from that point: the
     * spot's own record is not consulted, only the world between the two.
     */
    public static boolean spotReachable(Level level, Vec3 from, LaserSpots.Spot spot) {
        return spot != null && !spot.blocked() && lineOfSight(level, from, spot.position());
    }

    /** Whether nothing solid stands between two points. */
    public static boolean lineOfSight(Level level, Vec3 from, Vec3 to) {
        if (level == null || from == null || to == null) {
            return true;
        }
        Vec3 span = to.subtract(from);
        double length = span.length();
        if (length < SAMPLE_STEP) {
            return true;
        }
        Vec3 direction = span.scale(1.0D / length);
        try {
            for (double travelled = END_MARGIN; travelled <= length - END_MARGIN; travelled += SAMPLE_STEP) {
                Vec3 sample = from.add(direction.scale(travelled));
                BlockPos pos = BlockPos.containing(sample);
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                if (!state.getCollisionShape(level, pos).isEmpty()) {
                    return false;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A level that cannot be read cannot prove the line is blocked.
            return true;
        }
        return true;
    }
}
