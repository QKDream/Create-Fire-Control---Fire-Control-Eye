package com.qkdream.firecontrolcompat;

import java.util.Locale;

/**
 * Timing and counters for the terrain radar screen.
 *
 * <p>The screen was reported as "the radar makes the game stutter" with nothing in the log to
 * argue with: the pack prints what a radar scan costs as a whole, but the screen is one part of
 * that scan and the only part this mod casts rays for. These counters make the part measurable -
 * how many rays a sweep really casts, how many of them came back blocked, what one ray costs, and
 * how many were answered without a ray at all.</p>
 *
 * <p>One line every {@link #WINDOW_TICKS} ticks (five seconds at twenty ticks a second), in this
 * shape:</p>
 *
 * <pre>occlusion perf: calls=412 hits=37 avg-us=8.4 max-us=310 skipped-cache=288 skipped-range=0</pre>
 *
 * <p>The first five fields are the ones a player greps for; the two at the end say which of the
 * two cheap paths did the skipping, so a cache that never hits and a range gate that swallows
 * everything cannot be mistaken for each other.</p>
 *
 * <p>This class deliberately names no Minecraft type: it is the pure bookkeeping half of the
 * screen, so the offline benchmark can drive it - and {@link OcclusionCache} - with plain javac
 * and no game on the classpath.</p>
 */
public final class OcclusionPerf {

    /** Length of one report window, in ticks: five seconds at twenty ticks a second. */
    public static final int WINDOW_TICKS = 100;

    // ---- current window -------------------------------------------------------------------
    private static long windowStart;
    private static long windowCalls;
    private static long windowHits;
    private static long windowNanos;
    private static long windowMaxNanos;
    private static long windowSkippedCache;
    private static long windowSkippedRange;

    // ---- what the command and the next report read ------------------------------------------
    private static long totalCalls;
    private static long totalHits;
    private static long totalSkippedCache;
    private static long totalSkippedRange;
    private static long lifetimeNanos;
    private static double lastAvgMicros;
    private static long lastMaxMicros;
    private static long lastWindowCalls;
    private static long lastWindowHits;

    // ---- the sweep in progress, so the status line can quote one tick ------------------------
    private static long sweepNanos;
    private static long sweepCalls;
    private static long lastSweepNanos;
    private static long lastSweepCalls;

    private OcclusionPerf() {
    }

    /**
     * Records one line-of-sight evaluation: how long it took and what it answered.
     *
     * @param nanos duration of the ray, from {@link System#nanoTime()}
     * @param hit   whether the ray came back blocked
     */
    public static void record(long nanos, boolean hit) {
        long span = nanos < 0L ? 0L : nanos;
        windowCalls++;
        totalCalls++;
        windowNanos += span;
        lifetimeNanos += span;
        sweepNanos += span;
        sweepCalls++;
        if (span > windowMaxNanos) {
            windowMaxNanos = span;
        }
        if (hit) {
            windowHits++;
            totalHits++;
        }
    }

    /** One ray that was not cast because the same sight line was already known to be clear. */
    public static void skipCache() {
        windowSkippedCache++;
        totalSkippedCache++;
    }

    /** One ray that was not cast because the contact is too far away for the screen to judge. */
    public static void skipRange() {
        windowSkippedRange++;
        totalSkippedRange++;
    }

    /**
     * Marks the start of one sweep, closing the previous one.
     *
     * <p>The report line speaks in five-second windows, but the question a lagging player actually
     * asks is "what did the last tick cost me". Closing the previous sweep here keeps that number
     * available without a second timing pass: it is the sum of the rays one sweep cast, in
     * microseconds. A sweep that cast nothing leaves the previous number standing, so a radar that
     * went quiet does not erase the evidence of what it cost while it was busy.</p>
     */
    public static void beginSweep() {
        if (sweepCalls > 0L) {
            lastSweepNanos = sweepNanos;
            lastSweepCalls = sweepCalls;
        }
        sweepNanos = 0L;
        sweepCalls = 0L;
    }

    /** What the last completed sweep's rays cost together, in microseconds. */
    public static double lastSweepMicros() {
        return lastSweepNanos / 1000.0D;
    }

    /** How many rays the last completed sweep cast. */
    public static long lastSweepCalls() {
        return lastSweepCalls;
    }

    /**
     * Ends a window and answers its report line, or {@code null} while the window is still open.
     *
     * <p>A window in which nothing at all happened prints nothing and is simply rolled over: an
     * idle radar is the normal case, and a line of zeroes every five seconds for every computer in
     * the world would be the very log spam this screen's other lines were throttled for.</p>
     */
    public static String reportIfDue(long tick) {
        if (tick - windowStart < WINDOW_TICKS) {
            return null;
        }
        long ticks = tick - windowStart;
        windowStart = tick;
        if (windowCalls == 0L && windowSkippedCache == 0L && windowSkippedRange == 0L) {
            resetWindow();
            return null;
        }
        double avgMicros = windowCalls == 0L ? 0.0D : (double) windowNanos / 1000.0D / (double) windowCalls;
        long maxMicros = windowMaxNanos / 1000L;
        lastAvgMicros = avgMicros;
        lastMaxMicros = maxMicros;
        lastWindowCalls = windowCalls;
        lastWindowHits = windowHits;
        String line = String.format(Locale.ROOT,
                "occlusion perf: calls=%d hits=%d avg-us=%.1f max-us=%d skipped-cache=%d"
                        + " skipped-range=%d ticks=%d",
                windowCalls, windowHits, avgMicros, maxMicros, windowSkippedCache,
                windowSkippedRange, ticks);
        resetWindow();
        return line;
    }

    private static void resetWindow() {
        windowCalls = 0L;
        windowHits = 0L;
        windowNanos = 0L;
        windowMaxNanos = 0L;
        windowSkippedCache = 0L;
        windowSkippedRange = 0L;
    }

    /** Rays cast since the process started. */
    public static long totalCalls() {
        return totalCalls;
    }

    /** Rays that came back blocked since the process started. */
    public static long totalHits() {
        return totalHits;
    }

    /** Rays answered from the cache instead of being cast. */
    public static long totalSkippedCache() {
        return totalSkippedCache;
    }

    /** Rays skipped because the contact was beyond the screen's range. */
    public static long totalSkippedRange() {
        return totalSkippedRange;
    }

    /** Average cost of one ray over the last closed window, in microseconds. */
    public static double lastAvgMicros() {
        return lastAvgMicros;
    }

    /** Worst single ray over the last closed window, in microseconds. */
    public static long lastMaxMicros() {
        return lastMaxMicros;
    }

    /** Rays cast in the last closed window. */
    public static long lastWindowCalls() {
        return lastWindowCalls;
    }

    /** Rays that came back blocked in the last closed window. */
    public static long lastWindowHits() {
        return lastWindowHits;
    }

    /** Average over the whole process, in microseconds; zero before the first ray. */
    public static double lifetimeAvgMicros() {
        return totalCalls == 0L ? 0.0D : (double) lifetimeNanos / 1000.0D / (double) totalCalls;
    }

    /** Clears every counter; used by the offline benchmark, never by the game. */
    public static void reset() {
        resetWindow();
        windowStart = 0L;
        totalCalls = 0L;
        totalHits = 0L;
        totalSkippedCache = 0L;
        totalSkippedRange = 0L;
        lifetimeNanos = 0L;
        lastAvgMicros = 0.0D;
        lastMaxMicros = 0L;
        lastWindowCalls = 0L;
        lastWindowHits = 0L;
        sweepNanos = 0L;
        sweepCalls = 0L;
        lastSweepNanos = 0L;
        lastSweepCalls = 0L;
    }
}
