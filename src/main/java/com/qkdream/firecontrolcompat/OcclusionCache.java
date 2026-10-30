package com.qkdream.firecontrolcompat;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers which sight lines were already found clear, for a couple of ticks.
 *
 * <p>A radar sweeps every tick or every other tick and the geometry it asks about barely moves
 * between two of them: the aerial is a block on a hull, and a contact a kilometre away shifts by a
 * few blocks. Casting the same ray again a tick later is the largest single waste in the screen -
 * and the reason a full contact list could cost a millisecond of the server thread a tick.</p>
 *
 * <h2>Only "clear" is remembered</h2>
 *
 * <p>A remembered <em>blocked</em> verdict would be a false positive the moment the target came out
 * from behind the hill, and the whole point of the screen is to never hide something the radar can
 * see. A remembered <em>clear</em> verdict can only be wrong in the other direction - a target that
 * slipped behind cover within the last {@link #TTL_TICKS} ticks is drawn one or two ticks longer -
 * which is exactly the trade the screen is allowed to make. So this cache stores clear answers and
 * nothing else, and a blocked answer is recomputed every time rather than trusted.</p>
 *
 * <h2>The key</h2>
 *
 * <p>{@code (dimension, observer cell, target cell, generation)}, with both positions quantised to
 * a {@link #CELL_BLOCKS}-block grid. Quantising is part of being conservative: two contacts that
 * fall in one cell can only ever collapse onto a <em>clear</em> answer, so a collision can lose an
 * occlusion but can never invent one. It also makes the cache usable on a moving hull, where an
 * exact block position changes every tick and would hit nothing at all.</p>
 *
 * <p>Expiry is counted in <b>ticks</b>, not wall-clock time: a server that stalls for a second
 * should not come back with every entry expired, and a server that runs the tick loop faster than
 * real time should not keep entries longer than the world actually moved. {@code generation} is
 * bumped whenever the coordinate space or the on/off switch changes, which retires every entry
 * decided under the old assumptions without walking the map.</p>
 *
 * <p>No Minecraft type is named here on purpose: the offline benchmark drives this class directly
 * with plain javac and no game on the classpath.</p>
 */
public final class OcclusionCache {

    /** How long a clear verdict is reused, in ticks. Also the per-target recheck interval. */
    public static final int TTL_TICKS = 2;

    /** Log2 of {@link #CELL_BLOCKS}. */
    private static final int CELL_SHIFT = 2;

    /** Edge of one position cell, in blocks. */
    public static final int CELL_BLOCKS = 1 << CELL_SHIFT;

    /** Entries kept before the whole map is dropped. */
    private static final int MAX_ENTRIES = 8192;

    private record Key(int dim, int ox, int oy, int oz, int tx, int ty, int tz, long generation) {
    }

    /** Expiry tick of each remembered clear sight line. */
    private static final Map<Key, Long> CLEAR = new HashMap<>();

    /** Retires every entry when the assumptions behind them change. */
    private static volatile long generation = 1L;

    private OcclusionCache() {
    }

    /** The generation stamped into every key written from now on. */
    public static long generation() {
        return generation;
    }

    /** Drops every entry and retires keys held by callers that already read a generation. */
    public static void invalidate() {
        synchronized (CLEAR) {
            CLEAR.clear();
            generation++;
        }
    }

    /** How many sight lines are remembered right now. */
    public static int size() {
        synchronized (CLEAR) {
            return CLEAR.size();
        }
    }

    /**
     * Whether this exact sight line was found clear recently enough to skip the ray.
     *
     * @param tick current game time in ticks
     */
    public static boolean isClear(String dimension, double observerX, double observerY,
            double observerZ, double targetX, double targetY, double targetZ, long tick) {
        Key key = key(dimension, observerX, observerY, observerZ, targetX, targetY, targetZ);
        synchronized (CLEAR) {
            Long expiry = CLEAR.get(key);
            if (expiry == null) {
                return false;
            }
            if (tick > expiry) {
                CLEAR.remove(key);
                return false;
            }
            return true;
        }
    }

    /** Remembers that this sight line is clear until {@code tick + }{@link #TTL_TICKS}. */
    public static void rememberClear(String dimension, double observerX, double observerY,
            double observerZ, double targetX, double targetY, double targetZ, long tick) {
        Key key = key(dimension, observerX, observerY, observerZ, targetX, targetY, targetZ);
        synchronized (CLEAR) {
            if (CLEAR.size() >= MAX_ENTRIES) {
                CLEAR.clear();
            }
            CLEAR.put(key, tick + TTL_TICKS);
        }
    }

    private static Key key(String dimension, double observerX, double observerY, double observerZ,
            double targetX, double targetY, double targetZ) {
        return new Key(
                dimension == null ? 0 : dimension.hashCode(),
                cell(observerX), cell(observerY), cell(observerZ),
                cell(targetX), cell(targetY), cell(targetZ),
                generation);
    }

    /** The cell a coordinate falls in; arithmetic shift, so negatives floor the same way. */
    private static int cell(double coordinate) {
        if (!Double.isFinite(coordinate)) {
            return Integer.MIN_VALUE;
        }
        return ((int) Math.floor(coordinate)) >> CELL_SHIFT;
    }

    /** Clears the cache for a benchmark run. */
    public static void reset() {
        synchronized (CLEAR) {
            CLEAR.clear();
            generation = 1L;
        }
    }
}
