package com.qkdream.firecontrolcompat;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Per-launcher fire interval for the anti-air rails this mod adds to.
 *
 * <p>The pack's own anti-air loop paces the ordinary round, and the crewed mounts go through it -
 * but the high-speed and heavy rails are released by our own path, which has no pacing of its own, so
 * a held trigger emptied every tube in a few ticks. The interval is kept here rather than on the
 * block entity so it survives the rail's block id changing as it is armed and emptied, and it is
 * keyed by dimension plus position so two rails of the same kind never share a slot.</p>
 *
 * <p>The values are first guesses with the reasoning written down, and the log line carries the
 * measured {@code since=}, so they can be tuned from a real report instead of by another guess:
 * ordinary 20 ticks (one shot a second, which is what the pack's own loop allows), high-speed 12
 * (a burst weapon), heavy 50 (a long rail that is meant to feel heavy).</p>
 */
public final class AaFireCadence {

    /** The three anti-air families, with the interval each is held to. */
    public enum Kind {
        NORMAL(20),
        HIGH_SPEED(12),
        HEAVY(50);

        public final int waitTicks;

        Kind(int waitTicks) {
            this.waitTicks = waitTicks;
        }
    }

    /** Last fire per dimension+position, in game ticks. */
    private static final Map<String, Long> LAST_FIRE = new ConcurrentHashMap<>();

    /** Last ammo count seen per launcher, so a shot can be recognised by the count dropping. */
    private static final Map<String, Integer> LAST_AMMO = new ConcurrentHashMap<>();

    /** The last tick the cadence line was printed for, per launcher, so it is not a per-tick line. */
    private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();

    private AaFireCadence() {
    }

    /** The key a launcher is tracked under. */
    public static String key(Level level, BlockPos pos) {
        ResourceKey<Level> dimension = level == null ? null : level.dimension();
        return (dimension == null ? "?" : dimension.location().toString()) + "/" + pos.asLong();
    }

    /**
     * Which family a rail is holding.
     *
     * <p>Read from the round's own display name first, because that is the only statement the rail
     * makes about what it is loaded with - the block ids of the loaded and emptied variants differ
     * between pack versions. Anything unrecognised is treated as the high-speed family when it is
     * not the ordinary round, which is the shorter interval: a heavier rail that is paced too fast is
     * a smaller error than a light one paced as if it were heavy.</p>
     */
    public static Kind kindOf(boolean ordinary, String roundName) {
        if (ordinary) {
            return Kind.NORMAL;
        }
        String name = roundName == null ? "" : roundName.toLowerCase(Locale.ROOT);
        if (name.contains("重") || name.contains("heavy") || name.contains("48n6")
                || name.contains("tanshe_heavy")) {
            return Kind.HEAVY;
        }
        return Kind.HIGH_SPEED;
    }

    /** Ticks since this rail last fired, or a large number when it never has. */
    public static long since(Level level, BlockPos pos) {
        Long last = LAST_FIRE.get(key(level, pos));
        if (last == null || level == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, level.getGameTime() - last);
    }

    /** Whether the rail may fire now. */
    public static boolean ready(Level level, BlockPos pos, Kind kind) {
        return since(level, pos) >= kind.waitTicks;
    }

    /** Records that this rail fired. */
    public static void noteFired(Level level, BlockPos pos) {
        if (level != null) {
            LAST_FIRE.put(key(level, pos), level.getGameTime());
        }
    }

    /**
     * Recognises a shot by its ammunition count dropping, and records it.
     *
     * <p>The pack's own loop releases the round, so nothing of ours is called at the moment of fire:
     * the only observable trace is that the rail holds one fewer round than it did at the previous
     * press. That is enough, and it needs no cooperation from the pack.</p>
     */
    public static void observeAmmo(Level level, BlockPos pos, int ammo) {
        String key = key(level, pos);
        Integer previous = LAST_AMMO.put(key, ammo);
        if (previous != null && ammo < previous) {
            noteFired(level, pos);
        }
    }

    /**
     * The cadence line, at most once a second per rail.
     *
     * @return whether the line was printed
     */
    public static boolean log(Level level, BlockPos pos, Kind kind, boolean fired) {
        String key = key(level, pos);
        Long last = LAST_LOG.get(key);
        long now = System.currentTimeMillis();
        if (last != null && now - last < 1000L) {
            return false;
        }
        LAST_LOG.put(key, now);
        long since = since(level, pos);
        FireControlCompat.LOGGER.info(
                "aa cooldown: launcher={} kind={} since={} wait={} fired={}",
                pos, kind.name().toLowerCase(Locale.ROOT),
                since == Long.MAX_VALUE ? "-" : Long.toString(since), kind.waitTicks, fired);
        return true;
    }

    /** Forgets a rail, so a moved or broken launcher starts clean. */
    public static void forget(Level level, BlockPos pos) {
        String key = key(level, pos);
        LAST_FIRE.remove(key);
        LAST_AMMO.remove(key);
        LAST_LOG.remove(key);
    }
}
