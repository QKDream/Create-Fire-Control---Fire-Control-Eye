package com.qkdream.firecontrolcompat.missile;

import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * The rounds of this mod that are <b>really in a level</b> right now, so they can be intercepted.
 *
 * <h2>Why a list is needed at all</h2>
 *
 * <p>{@link MathIntercept} sweeps two very different things. A round fire control flies
 * <em>mathematically</em> is a record: it never joins the level, and
 * {@code MathematicalMissileManager.missiles(level)} is the only way to see it. A round of this mod
 * in ordinary (entity) mode <b>is</b> an entity - but the same sweep cannot find it either, because
 * it iterates threats and then looks for interceptors, never the other way round, and the pack's own
 * proximity fuze only knows its own guided table. That is the "our anti-tank round cannot be shot
 * down while every other mathematical round can" report.</p>
 *
 * <p>So every server tick a round reports itself here, and the sweep walks this list as a second
 * source of threats. The map is <b>weak</b> and keyed by the entity, so a round that is discarded
 * without ever telling anyone (a chunk unload, a kill by another mod) leaves nothing behind.</p>
 *
 * <p>Rounds that fire control captured are skipped here on purpose: they are already in the pack's
 * table and the mirror pass owns them, and a round listed twice could be charged two
 * interceptions.</p>
 *
 * <h2>Birth bookkeeping (the "explodes the moment it leaves the tube" regression)</h2>
 *
 * <p>Registering the round as a threat is only half of what the sweep needs: it also needs to know
 * how <b>old</b> that round is. Without an age, the sweep that was just handed the round it is
 * flying next to cannot tell "a round that has been in the air for a second" from "a round that was
 * spawned this tick", and the interception it settles is the round against itself - the fuze sits
 * at distance zero for the whole flight, so the first sweep after launch blows the round up. Every
 * entry therefore carries the game tick the round was first seen on, and
 * {@link #GUARD_TICKS} of it are spent out of every interception decision (see
 * {@link #guarded(Entity)}).</p>
 *
 * <p>{@link #logOnce(Entity, String)} is the bookkeeping for the diagnostics: the sweep may look at
 * the same new round twenty times in those twenty ticks, and the crew needs one line saying why,
 * not twenty.</p>
 */
public final class InFlightRounds {

    /**
     * How long after birth a round takes part in no interception decision at all, in ticks.
     *
     * <p>Two things happen in the first ticks of a round's life that make any interception verdict
     * wrong: it is still inside its own launcher's neighbourhood (the rack, the neighbouring pylons,
     * the rounds still sitting on them) and, while it is the threat, it is also the closest
     * "interceptor" to itself - distance zero, forever, because the sweep's search box is built
     * around the threat's own position. Twenty ticks is one second: long enough for the round to be
     * clear of the rail and for a salvo to spread out, short enough that a round fired at point
     * blank range is still interceptable.</p>
     */
    public static final int GUARD_TICKS = 20;

    /**
     * Set on a round once an interception has claimed it, so it is never judged (or blown up) a
     * second time. The sweep, the cook-off pass and the round's own one-shot detonation all read
     * it.
     */
    public static final String SPENT_KEY = "firecontrolcompat:intercept_spent";

    /** When a round entered the air, plus what has already been said about it. */
    private static final class Entry {
        private final long spawnTick;
        private final Set<String> logged = new HashSet<>();

        private Entry(long spawnTick) {
            this.spawnTick = spawnTick;
        }
    }

    /** Weak so a round that leaves the world leaves nothing behind. */
    private static final Map<BeamRidingMissileEntity, Entry> LIVE = new WeakHashMap<>();

    /** Reports a round as in flight. Cheap: one map write per round per tick. */
    public static void track(Entity round) {
        if (!(round instanceof BeamRidingMissileEntity missile)) {
            return;
        }
        if (missile.level() == null || missile.level().isClientSide()) {
            return;
        }
        synchronized (LIVE) {
            if (!LIVE.containsKey(missile)) {
                LIVE.put(missile, new Entry(gameTime(missile)));
            }
        }
    }

    /** Drops a round that is going away. */
    public static void untrack(Entity round) {
        if (round == null) {
            return;
        }
        synchronized (LIVE) {
            LIVE.remove(round);
        }
    }

    /** The game tick this round was first seen flying, or {@code -1} when it was never seen. */
    public static long spawnTickOf(Entity round) {
        if (!(round instanceof BeamRidingMissileEntity missile)) {
            return -1L;
        }
        synchronized (LIVE) {
            Entry entry = LIVE.get(missile);
            return entry == null ? -1L : entry.spawnTick;
        }
    }

    /** Ticks since this round was first seen flying, or {@code -1} when it was never seen. */
    public static int ageTicks(Entity round) {
        long spawn = spawnTickOf(round);
        if (spawn < 0L) {
            return -1;
        }
        long age = gameTime(round) - spawn;
        if (age < 0L) {
            age = 0L;
        }
        return (int) Math.min(Integer.MAX_VALUE, age);
    }

    /** Whether this round is still inside its birth grace window (see {@link #GUARD_TICKS}). */
    public static boolean guarded(Entity round) {
        int age = ageTicks(round);
        return age >= 0 && age < GUARD_TICKS;
    }

    /**
     * Whether this reason is news for this round.
     *
     * @return {@code true} the first time this round is asked about this reason, {@code false}
     *         afterwards (and for anything that was never tracked)
     */
    public static boolean logOnce(Entity round, String reason) {
        if (!(round instanceof BeamRidingMissileEntity missile) || reason == null || reason.isEmpty()) {
            return true;
        }
        synchronized (LIVE) {
            Entry entry = LIVE.get(missile);
            return entry != null && entry.logged.add(reason);
        }
    }

    /**
     * The rounds of this mod really flying in {@code root} (entity mode only), with the dead ones
     * pruned as they are met.
     */
    public static List<Entity> live(ServerLevel root) {
        if (root == null) {
            return List.of();
        }
        List<Entity> found = new ArrayList<>();
        synchronized (LIVE) {
            Iterator<BeamRidingMissileEntity> iterator = LIVE.keySet().iterator();
            while (iterator.hasNext()) {
                BeamRidingMissileEntity round = iterator.next();
                if (round == null || round.isRemoved() || !round.isAlive()) {
                    iterator.remove();
                    continue;
                }
                if (round.firecontrolcompat$isMounted()) {
                    // Still on the rail: not flying, nothing to intercept yet.
                    continue;
                }
                if (spent(round)) {
                    // Already claimed by an interception: judging it again would only charge the
                    // same kill twice (the sweep, the cook-off pass and the burst all run here).
                    continue;
                }
                if (!(round.level() instanceof ServerLevel level) || MathIntercept.rootOf(level) != root) {
                    continue;
                }
                if (MathMissileMirror.contains(round)) {
                    // Captured by fire control: the mathematical pass owns it (see the class doc).
                    continue;
                }
                found.add(round);
            }
        }
        return found;
    }

    /** Whether an interception has already claimed this round. */
    public static boolean spent(Entity round) {
        if (round == null) {
            return false;
        }
        try {
            return round.getPersistentData().getBoolean(SPENT_KEY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Claims a round for an interception, so nothing judges it twice. */
    public static void markSpent(Entity round) {
        if (round == null) {
            return;
        }
        try {
            round.getPersistentData().putBoolean(SPENT_KEY, true);
        } catch (Throwable ignored) {
            // a round without persistent data simply has no mark
        }
    }

    /** How many rounds are registered, for the diagnostic line. */
    public static int size() {
        synchronized (LIVE) {
            return LIVE.size();
        }
    }

    private static long gameTime(Entity entity) {
        try {
            return entity.level() == null ? 0L : entity.level().getGameTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
