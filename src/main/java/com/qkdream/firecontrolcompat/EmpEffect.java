package com.qkdream.firecontrolcompat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * The blackout an EMP shell leaves behind.
 *
 * <p>A burst is remembered as a point plus the moment it expires. Anything
 * electronic that asks while a burst is live and sits inside its reach answers
 * "darkened" for ten seconds afterwards: the fire control radar stops sweeping
 * and 面包学's active protection stops intercepting.</p>
 *
 * <p>The reach is measured after the Sable projection, so a round that went off
 * against a structure's hull also darkens the devices mounted on that
 * structure, whose blocks are stored in the structure's own plot space.</p>
 *
 * <p>Callers do not always hold a {@link Level}: a machine ticked inside a
 * sub-level can be handed a plain {@link LevelAccessor}. The position half of
 * the test works either way, and the timer half falls back to wall-clock time
 * when the accessor cannot report a game time, so a burst never silently spares
 * a device just because the caller saw a different view of the world.</p>
 */
public final class EmpEffect {

    /** How far one burst reaches, in blocks. */
    public static final double RADIUS = 72.0;

    /** How long a burst keeps devices dark, in ticks (ten seconds). */
    public static final int DURATION_TICKS = 200;

    private static final double RADIUS_SQUARED = RADIUS * RADIUS;
    private static final long DURATION_NANOS = DURATION_TICKS * 50_000_000L;
    private static final List<Burst> BURSTS = new ArrayList<>();
    private static long lastReportGameTime = Long.MIN_VALUE;
    private static long lastReportNanos = Long.MIN_VALUE;

    private EmpEffect() {
    }

    /** Records a burst; called where the EMP shell detonates. */
    public static void burst(Level level, Vec3 position) {
        if (level == null || position == null || level.isClientSide()) {
            return;
        }
        try {
            Vec3 centre = SubLevelPositions.worldPosition(level, position);
            long now = level.getGameTime();
            long nowNanos = System.nanoTime();
            synchronized (BURSTS) {
                BURSTS.removeIf(burst -> burst.expired(now, nowNanos));
                BURSTS.add(new Burst(level.dimension(), centre, now + DURATION_TICKS, nowNanos + DURATION_NANOS));
            }
            FireControlCompat.LOGGER.info("[firecontrolcompat] EMP burst at {} {} {} reaches {} blocks for {} ticks",
                    (int) centre.x, (int) centre.y, (int) centre.z, (int) RADIUS, DURATION_TICKS);
        } catch (Throwable ignored) {
        }
    }

    /** True while a live burst covers the device sitting at the position. */
    public static boolean isDown(Level level, BlockPos pos) {
        return inside(level, pos);
    }

    /** The same question for a caller that only holds a level accessor. */
    public static boolean isDown(LevelAccessor accessor, BlockPos pos) {
        if (accessor instanceof Level level) {
            return inside(level, pos);
        }
        return pos != null && inside(null, pos);
    }

    private static boolean inside(Level level, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        try {
            long gameNow = level != null ? level.getGameTime() : Long.MIN_VALUE;
            long nanoNow = System.nanoTime();
            ResourceKey<Level> dimension = level != null ? level.dimension() : null;
            Vec3 stored = Vec3.atCenterOf(pos);
            Vec3 world = null;
            synchronized (BURSTS) {
                for (int index = BURSTS.size() - 1; index >= 0; index--) {
                    Burst burst = BURSTS.get(index);
                    if (burst.expired(gameNow, nanoNow)) {
                        BURSTS.remove(index);
                        continue;
                    }
                    if (dimension != null && !burst.dimension().equals(dimension)) {
                        continue;
                    }
                    if (!burst.covers(stored)) {
                        if (level == null) {
                            continue;
                        }
                        if (world == null) {
                            world = SubLevelPositions.worldPosition(level, stored);
                        }
                        if (!burst.covers(world)) {
                            continue;
                        }
                    }
                    note(gameNow, nanoNow, pos, burst);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** One line per second of suppression, so a test run shows what went dark. */
    private static void note(long gameNow, long nanoNow, BlockPos pos, Burst burst) {
        boolean due;
        if (gameNow != Long.MIN_VALUE) {
            due = lastReportGameTime == Long.MIN_VALUE || gameNow - lastReportGameTime >= 20L;
        } else {
            due = lastReportNanos == Long.MIN_VALUE || nanoNow - lastReportNanos >= 1_000_000_000L;
        }
        if (!due) {
            return;
        }
        lastReportGameTime = gameNow;
        lastReportNanos = nanoNow;
        FireControlCompat.LOGGER.info("[firecontrolcompat] EMP holds {} {} {} dark (burst at {} {} {})",
                pos.getX(), pos.getY(), pos.getZ(),
                (int) burst.centre().x, (int) burst.centre().y, (int) burst.centre().z);
    }

    /** One detonation: where it went off and when it stops mattering. */
    private record Burst(ResourceKey<Level> dimension, Vec3 centre, long expire, long expireNanos) {

        boolean expired(long gameNow, long nanoNow) {
            boolean liveTicks = gameNow != Long.MIN_VALUE && expire > gameNow;
            boolean liveClock = expireNanos > nanoNow;
            return !liveTicks && !liveClock;
        }

        boolean covers(Vec3 point) {
            return point != null && point.distanceToSqr(centre) <= RADIUS_SQUARED;
        }
    }
}
