package com.qkdream.firecontrolcompat.missile;

import com.hooya.stabilizedturret.content.controller.MathematicalMissileManager;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The mathematical missiles a level is flying, as this mod sees them.
 *
 * <p>Fire control 0.9.3 and later can fly a guided round in <em>mathematical mode</em>: the round
 * exists as an entity object whose position and velocity are stepped by
 * {@code GuidedMissileFlight.step} every tick, but <b>it is never added to the level</b>.
 * {@code MathematicalMissileManager.capture} takes the entity on the way in, records it in its
 * private {@code ACTIVE} table, and then cancels the join event - so
 * {@code level.getEntities(...)} cannot find it, and every entity sweep in the game, this mod's
 * radar included, walks straight past a missile that is flying towards the player.</p>
 *
 * <p>The pack does publish the table: {@code MathematicalMissileManager.missiles(ServerLevel)}
 * returns the round entities of one level, and {@code contains(Entity)} answers whether a given
 * entity is one of them. That is the only accessor needed here - no private field, no reflection,
 * and no copy that could drift out of date. Everything this class answers is read from the pack at
 * the moment it is asked, so a round that has just been detonated or discarded is gone from the
 * answer in the same tick.</p>
 *
 * <p>It is deliberately a read adapter and not a cache: the previous version kept its own table
 * filled from {@code MissileManager}, which is a <em>different</em> subsystem that this pack never
 * populates, so the radar reported "no missiles" forever and there was nothing in that answer to
 * show the mistake. Each read here also reports how many rounds it saw, so "the sky is empty" and
 * "the lookup is broken" can never look the same again.</p>
 */
public final class MathMissileMirror {

    private MathMissileMirror() {
    }

    /**
     * One round, in the shape the radar needs.
     *
     * <p>{@code previousPosition} is where the round was on the tick before, taken from its own
     * motion, which is what lets a swept intercept test the path between two samples rather than
     * the distance at the samples.</p>
     */
    public record Entry(
            UUID id,
            Vec3 position,
            Vec3 previousPosition,
            Vec3 velocity,
            int age,
            String phase,
            long lastSeenTick
    ) {
    }

    /** Quietest gap between two "math mirror" log lines, in ticks. */
    private static final long LOG_GAP_TICKS = 40L;

    private static long lastLogTick;
    private static int lastLoggedCount = -1;

    /**
     * The rounds this level is flying right now.
     *
     * <p>Read straight from {@code MathematicalMissileManager.missiles(level)}. Rounds that have
     * been removed are skipped: the table is the pack's, and a discarded round can still be in it
     * for the tick it was discarded in.</p>
     */
    public static List<Entry> entries(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        try {
            List<Entity> live = missiles(level);
            if (live.isEmpty()) {
                report(level, 0);
                return List.of();
            }
            long now = level.getGameTime();
            List<Entry> entries = new ArrayList<>(live.size());
            for (Entity missile : live) {
                if (missile == null || missile.isRemoved()) {
                    continue;
                }
                Vec3 at = missile.position();
                if (at == null) {
                    continue;
                }
                Vec3 velocity = missile.getDeltaMovement();
                entries.add(new Entry(
                        missile.getUUID(),
                        at,
                        at.subtract(velocity == null ? Vec3.ZERO : velocity),
                        velocity == null ? Vec3.ZERO : velocity,
                        missile.tickCount,
                        "math",
                        now));
            }
            report(level, entries.size());
            return entries;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("math mirror: read skipped: {}", failure.toString());
            return List.of();
        }
    }

    /**
     * The live round entities of this level, from the pack's own table.
     *
     * <p>Exposed so the interception sweep works on the same list the radar paints, from one place.</p>
     */
    public static List<Entity> missiles(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        List<Entity> live = new ArrayList<>();
        try {
            for (Entity missile : MathematicalMissileManager.missiles(level)) {
                if (missile != null && !missile.isRemoved() && missile.isAlive()) {
                    live.add(missile);
                }
            }
        } catch (Throwable failure) {
            ReportOnce.mirrorFailure(failure);
        }
        return live;
    }

    /** Whether that entity is one of the pack's mathematically flown rounds. */
    public static boolean contains(Entity entity) {
        try {
            return entity != null && MathematicalMissileManager.contains(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** How many rounds the pack says this level is flying. */
    public static int size(ServerLevel level) {
        return missiles(level).size();
    }

    /**
     * One line whenever the count changes, and at most one per {@link #LOG_GAP_TICKS} ticks
     * otherwise.
     *
     * <p>The previous gate compared against a {@code Long.MIN_VALUE} stamp with a subtraction, which
     * wraps to a negative number for every positive game time and silences the line forever. The
     * seed here is {@code 0} and is tested directly.</p>
     */
    private static void report(ServerLevel level, int count) {
        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
            return;
        }
        if (count == lastLoggedCount && lastLogTick != 0L && now - lastLogTick < LOG_GAP_TICKS) {
            return;
        }
        lastLoggedCount = count;
        lastLogTick = now;
        FireControlCompat.LOGGER.info("math mirror: missiles={} tick={}", count, now);
    }

    /** Keeps a repeatedly failing lookup from filling the log, without hiding the first failure. */
    private static final class ReportOnce {

        private static boolean reported;

        private ReportOnce() {
        }

        static void mirrorFailure(Throwable failure) {
            if (reported) {
                return;
            }
            reported = true;
            FireControlCompat.LOGGER.info("math mirror: table unreadable: {}", failure.toString());
        }
    }
}
