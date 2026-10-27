package com.qkdream.firecontrolcompat.missile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.hooya.stabilizedturret.content.missile.MissileManager;
import com.hooya.stabilizedturret.content.missile.VirtualMissile;
import com.qkdream.firecontrolcompat.StartupProbe;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.munitions.autocannon.flak.FlakExplosion;

/**
 * Lets the guns shoot down a missile that fire control is flying mathematically.
 *
 * <p>Fire control 0.9.3 flies its fast guided missiles as records: {@code MissileManager} keeps a
 * {@code Map<UUID, VirtualMissile>} per level, ticks them itself, and the client only draws the
 * model. Nothing of that exists as an entity, so the two mechanics that used to bring such a missile
 * down had nothing to collide with - a proximity-fuzed cannon shell looked for entities, found none,
 * and the missile flew through the burst untouched, and a magazine cook-off next to the impact point
 * never saw a missile either.</p>
 *
 * <p>The record carries what is needed to decide the same question: {@code position()} each tick,
 * {@code velocity()}, {@code id()}. This hook asks it once per tick per missile, and only for a
 * missile that already has something within {@link #RADIUS} blocks of it - the query is one small
 * AABB search for the shells that could fuze, which is the same work a proximity fuze would do.</p>
 *
 * <p>An interception here is the missile's own detonation: {@code MissileManager.detonate} is asked
 * to blow the record up at the meeting point, which is the code path the pack uses when the missile
 * reaches its target, so the burst, the damage and the removal are the pack's own. The shell that
 * made the intercept is spent.</p>
 *
 * <p>Checked again against fire control 0.9.4: the missile records did not change at all
 * ({@code MissileManager}, {@code VirtualMissile}, {@code missile.impact.*} are byte identical), so
 * the pack still has nothing that would let a gun bring one down. What 0.9.4 did add is a fuze for
 * the pack's <em>own</em> auto-defense interceptors: {@code AutoDefenseMissileGuidance.onMissileFuzes}
 * sweeps interceptor and threat together through {@code MovingMissileFuze.firstContact} and settles
 * the meeting point itself, over the {@code guided} map only. That is why this hook stays, and why
 * {@link #nativeOwned} steps out of the way of exactly those interceptors.</p>
 */
public final class MathIntercept {

    /**
     * Startup cost probe: marks the moment this class's static initialisation begins.
     *
     * <p>Placed as the very first initialiser in the class body so the matching
     * {@code staticEnd} at the bottom of the class measures every one of the static fields in
     * between. Nothing here is loaded early on purpose: the class stays lazy, so a session that
     * never intercepts anything never pays for this line.</p>
     */
    static {
        StartupProbe.staticBegin("MathIntercept");
    }

    private static final String PREFIX = "[firecontrolcompat] ";


    /**
     * The level a manager belongs to, read off its own field.
     *
     * <p>{@code MissileManager} keeps its level private and has no getter, and the tick hook only
     * receives the manager, so the field is read once per tick through a lookup that is cached.</p>
     */
    public static ServerLevel levelOf(MissileManager manager) {
        try {
            if (levelField == null) {
                java.lang.reflect.Field field = MissileManager.class.getDeclaredField("level");
                field.setAccessible(true);
                levelField = field;
            }
            Object value = levelField.get(manager);
            return value instanceof ServerLevel ? (ServerLevel) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Field levelField;

    /**
     * The mathematical missiles a level is flying right now.
     *
     * <p>This used to read {@code MissileManager}'s private table of managers through reflection,
     * and that is gone: a failed read and an empty sky both answered "no missiles", so the radar
     * reported zero for a whole session with nothing to tell the two apart.
     * {@link MathMissileMirror} is filled from inside the manager instead; the radar reads that.</p>
     *
     * @deprecated use {@link MathMissileMirror#entries(ServerLevel)}.
     */
    @Deprecated
    public static Collection<VirtualMissile> missilesOf(ServerLevel level) {
        return java.util.List.of();
    }

    /** Radius a proximity-fuzed shell fuses at, plus the shell's own flight in one tick. */
    public static final double RADIUS = 8.0;

    /**
     * Fuse radius used when the shell itself does not say: the range a proximity-fuzed round is
     * usually set to.
     */
    public static final double DEFAULT_FUZE_RADIUS = 5.0;

    /**
     * Fuse radius used when a <em>guided missile</em> does not publish one of its own.
     *
     * <p>A missile is not a shell with a fuze screwed into the nose: its proximity range is the
     * lethal radius of a warhead several times heavier, and the rounds this mod has to answer for
     * - the pack's 便携式防空 / 高速防空 rounds, our own beam-riding family - all publish ten
     * blocks or more. Six is the floor: below that a head-on pass at thirty-four blocks per tick
     * still fits between two samples, but the round would have to be aimed, not fuzed.</p>
     */
    public static final double DEFAULT_MISSILE_FUZE_RADIUS = 6.0;

    private static Method detonate;
    private static boolean looked;

    private MathIntercept() {
    }

    /**
     * Runs the proximity check for every mathematical missile of this level.
     *
     * <p>Called from the manager's own tick, before it advances the records: a missile shot down here
     * is detonated at its current position and gone from the map before this tick moves it.</p>
     *
     * <p>A summary of the window is logged every {@link #SCAN_WINDOW_TICKS} ticks even when nothing
     * is ever hit: {@code math-intercept scan: missiles=X shells=Y nearest=Z}. Without it a silent
     * log means both "no interception happened" and "the scan is not reaching any missile", and
     * those need opposite fixes.</p>
     */
    public static void interceptNearby(MissileManager manager, ServerLevel level) {
        try {
            Collection<VirtualMissile> missiles = manager.missiles();
            int count = missiles == null ? 0 : missiles.size();
            firecontrolcompat$windowMissiles = Math.max(firecontrolcompat$windowMissiles, count);
            if (missiles != null) {
                for (VirtualMissile missile : missiles) {
                    if (missile == null) {
                        continue;
                    }
                    firecontrolcompat$checkOne(manager, level, missile);
                }
            }
            firecontrolcompat$flushWindow(level);
        } catch (Throwable ignored) {
            // An interception is a bonus; it must never take the missile tick down.
        }
    }

    /** Quietest gap between two window summary lines, in ticks. */
    private static final long SCAN_WINDOW_TICKS = 40L;

    /** Highest missile count seen, shells examined and closest miss, over the current window. */
    private static int firecontrolcompat$windowMissiles;
    private static int firecontrolcompat$windowRounds;
    private static int firecontrolcompat$windowShells;
    private static double firecontrolcompat$windowNearest = Double.MAX_VALUE;
    private static long firecontrolcompat$windowAt;

    /**
     * Prints the window summary and starts a new one.
     *
     * <p>The seed is {@code 0} and is tested directly: a {@code Long.MIN_VALUE} seed compared with
     * a subtraction wraps to a negative number for every positive game time and silences the line
     * forever, which is how three earlier reports of this kind went missing.</p>
     */
    private static void firecontrolcompat$flushWindow(ServerLevel level) {
        long now = level.getGameTime();
        if (firecontrolcompat$windowAt != 0L && now - firecontrolcompat$windowAt < SCAN_WINDOW_TICKS) {
            return;
        }
        firecontrolcompat$windowAt = now;
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}math-intercept scan: missiles={} rounds={} shells={} nearest={} subs={}",
                PREFIX, firecontrolcompat$windowMissiles, firecontrolcompat$windowRounds,
                firecontrolcompat$windowShells,
                firecontrolcompat$windowNearest == Double.MAX_VALUE
                        ? "none"
                        : String.format(java.util.Locale.ROOT, "%.2f", firecontrolcompat$windowNearest),
                firecontrolcompat$subLevels(level).size());
        firecontrolcompat$windowMissiles = 0;
        firecontrolcompat$windowRounds = 0;
        firecontrolcompat$windowShells = 0;
        firecontrolcompat$windowNearest = Double.MAX_VALUE;
    }

    /**
     * One swept proximity check for one mathematical missile.
     *
     * <p>Both the missile and the shell are tested over the distance they covered since the
     * previous tick, because a fast round crosses the fuse radius between two samples: at 34
     * blocks per tick the record can sit 20 blocks in front of a shell one tick and 20 blocks
     * behind it the next, and a distance taken at the sample points alone never sees the pass.
     * The check is the least distance between the two segments, with the meeting point taken on
     * the missile's own segment.</p>
     *
     * <p>The trigger distance is the shell's own proximity radius - the number the round would
     * fuze at on a real contact - read from the round itself and only capped by
     * {@link #RADIUS}, the search margin. A round that publishes no radius fuzes at
     * {@link #DEFAULT_FUZE_RADIUS}.</p>
     */
    private static void firecontrolcompat$checkOne(MissileManager manager, ServerLevel level,
            VirtualMissile missile) {
        Vec3 at = missile.position();
        if (at == null) {
            return;
        }
        Vec3 from = missile.previousPosition();
        if (from == null || !finite(from)) {
            from = at;
        }
        Sweep sweep = firecontrolcompat$sweep(level, from, at, null);
        if (sweep == null) {
            return;
        }
        if (sweep.skip() != null || sweep.shell() == null) {
            // A record has no identity and no platform to exclude, so nothing is ever skipped on
            // this path; the guard stays so a future owner cannot slip through it.
            return;
        }
        if (sweep.distance() > sweep.trigger()) {
            scanLog(level, missile.id().toString(), sweep.shell(), sweep.distance(),
                    sweep.trigger());
            return;
        }
        // A proximity-fuzed shell spends itself on the burst first, the way it would on a real
        // contact: the round's own detonation path when it has one, otherwise the shell is spent
        // here. Only then is the missile asked to blow up, so the burst and the kill agree.
        Boom boom = fireShell(level, sweep.shell(), sweep.point());
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}math-fuze: shell={} missile={} dist={} radius={} detonated={}",
                PREFIX, sweep.shell().getType().toString(), missile.id(),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.distance()),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.trigger()),
                boom.method());
        boolean blewUp = detonate(manager, level, missile, sweep.point());
        if (!blewUp) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}math-intercept skipped reason=detonate-refused missile={} at={}",
                    PREFIX,
                    missile.id(), sweep.point());
            return;
        }
        // The meeting point is played out twice over, on purpose. The round's own burst is what
        // the crew asked for, but a round whose detonation only runs server-side (a 面包学/CBC
        // {@code *Procedure} or a {@code detonate} that spawns its particles on the client only)
        // leaves nothing to look at - which is the "the round just vanishes" report. So the
        // interception always also gets the burst 载人航天 gives a missile that cooks off, and the
        // particles and the sound are broadcast to every player rather than drawn once.
        int effect = firecontrolcompat$interceptBurst(level, sweep.point());
        InterceptMarks.remember(level, sweep.point(), now(level));
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}math-intercept(swept): missile={} by={} dist={} at={} radius={} vfx={} body={}",
                PREFIX,
                missile.id(), sweep.shell().getType().toString(),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.distance()), sweep.point(),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.trigger()),
                effect, boom.method());
    }

    /**
     * Sweeps every round the pack is flying in mathematical mode against the shells around it.
     *
     * <p>This is the branch that matters on 0.9.4. A mathematical round is an entity object that
     * never joins the level - {@code MathematicalMissileManager.capture} cancels its join event and
     * keeps advancing it by hand - so no shell can collide with it and no entity sweep can find it.
     * {@code MathematicalMissileManager.missiles(level)} is the pack's own answer to "what is
     * flying", and its rounds carry a live position and {@code deltaMovement} every tick, which is
     * exactly what the swept proximity test needs: the round's own segment is
     * {@code position - deltaMovement .. position}.</p>
     *
     * <p>Called once per server tick per level from {@code MathEntityInterceptMixin}, which rides
     * {@code MathematicalMissileManager.sync}: the round table is read at the same point in the tick
     * the pack syncs it, so the sweep sees the tick's positions and a round that has just been
     * removed is already gone from the list.</p>
     */
    public static void sweepLevel(ServerLevel level) {
        long started = System.nanoTime();
        try {
            // The pack files a mathematical round under the level it was launched into the world
            // from, never under the plot its launcher happens to ride (see
            // {@code MathematicalMissileManager.capture} -> {@code MianbaoMissileCompat
            // .getRootServerLevel}). A sweep called for a sub-level would therefore read an empty
            // table; the root is resolved first so the round list and the search share a frame.
            ServerLevel root = firecontrolcompat$rootOf(level);
            firecontrolcompat$reportArmed(root);
            java.util.List<Entity> targets = com.qkdream.firecontrolcompat.missile.MathMissileMirror
                    .missiles(root);
            firecontrolcompat$windowMissiles =
                    Math.max(firecontrolcompat$windowMissiles, targets.size());
            for (Entity target : targets) {
                firecontrolcompat$checkTarget(root, target);
            }
            // Second source of threats: this mod's own rounds in ordinary (entity) mode. They never
            // reach the pack's mathematical table, so without this pass they could not be brought
            // down at all - which is exactly what was reported of the infrared anti-tank round
            // while every mathematical round was interceptable. See {@link InFlightRounds}.
            java.util.List<Entity> own = com.qkdream.firecontrolcompat.missile.InFlightRounds.live(root);
            firecontrolcompat$windowRounds = Math.max(firecontrolcompat$windowRounds, own.size());
            firecontrolcompat$windowMissiles =
                    Math.max(firecontrolcompat$windowMissiles, targets.size() + own.size());
            for (Entity round : own) {
                // A round is out of every interception decision for its first second: it is still
                // in its launcher's neighbourhood, and - the regression this guard was added for -
                // it is the closest "interceptor" to itself, so the sweep used to settle a hit at
                // distance zero on the very first tick after launch. See InFlightRounds#GUARD_TICKS.
                if (InFlightRounds.guarded(round)) {
                    firecontrolcompat$reportSkip(round, "guard-age", "-", "-");
                    continue;
                }
                firecontrolcompat$checkTarget(root, round);
            }
            firecontrolcompat$flushWindow(root);
            firecontrolcompat$recordCost(root, System.nanoTime() - started, targets.size());
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}math-intercept sweep skipped: {}", PREFIX, failure.toString());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cost readout
    // ---------------------------------------------------------------------------------------

    /** Quietest gap between two cost lines, in ticks. A hundred ticks is five seconds. */
    private static final long COST_WINDOW_TICKS = 100L;

    private static long costWindowAt;
    private static long costWindowNanos;
    private static long costWindowTicks;
    private static long costWindowQueries;
    private static long costWindowSkipped;
    private static int costWindowPeakMissiles;

    /**
     * Adds one tick's sweep to the window and, every five seconds, prints what it cost.
     *
     * <p>The maid anti-air report was "severe stutter", and this sweep is the one thing in the mod
     * that grows with both the rounds in the air and the number of Sable plots, so the number it
     * actually costs per tick has to be on the record rather than reasoned about. Microseconds per
     * tick, not milliseconds per call: one call per level per tick.</p>
     *
     * <p>The seed is {@code 0} and is compared directly - a {@code Long.MIN_VALUE} seed subtracted
     * from the game time wraps negative for every positive tick and would silence this forever.</p>
     */
    private static void firecontrolcompat$recordCost(ServerLevel root, long nanos, int missiles) {
        if (root == null) {
            return;
        }
        long now;
        try {
            now = root.getGameTime();
        } catch (Throwable ignored) {
            return;
        }
        costWindowNanos += Math.max(0L, nanos);
        costWindowTicks++;
        costWindowPeakMissiles = Math.max(costWindowPeakMissiles, missiles);
        if (costWindowAt != 0L && now - costWindowAt < COST_WINDOW_TICKS) {
            return;
        }
        long ticks = Math.max(1L, costWindowTicks);
        firecontrolcompat$costPerTickUs = costWindowNanos / 1000L / ticks;
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}math-intercept cost: us={} missiles={} plots={} queries={} skipped={} ticks={}",
                PREFIX, costWindowNanos / 1000L / ticks, costWindowPeakMissiles,
                firecontrolcompat$subLevels(root).size(), costWindowQueries, costWindowSkipped, ticks);
        costWindowAt = now;
        costWindowNanos = 0L;
        costWindowTicks = 0L;
        costWindowQueries = 0L;
        costWindowSkipped = 0L;
        costWindowPeakMissiles = 0;
    }

    /** Last measured cost of one sweep, in microseconds, for the radar/TPS readouts. */
    private static volatile long firecontrolcompat$costPerTickUs;

    /** Whether the "armed" line has been printed; the sweep's own receipt for this build. */
    private static boolean armedReported;

    /**
     * Says once per session who this sweep will accept as an interceptor.
     *
     * <p>Without it "a missile cannot shoot a mathematical round down" and "the old jar is still in
     * the mods folder" look exactly alike in the log, and they need opposite fixes. One line, once,
     * at the first tick the sweep runs.</p>
     */
    private static void firecontrolcompat$reportArmed(ServerLevel root) {
        if (armedReported) {
            return;
        }
        armedReported = true;
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}math-intercept armed: interceptor=<projectile|cbc|beam-riding|missile-key|tanshe>"
                        + " search={} fuse=shell{} missile{} plots={} takeoff={}",
                PREFIX,
                String.format(java.util.Locale.ROOT, "%.1f", RADIUS),
                String.format(java.util.Locale.ROOT, "%.1f", DEFAULT_FUZE_RADIUS),
                String.format(java.util.Locale.ROOT, "%.1f", DEFAULT_MISSILE_FUZE_RADIUS),
                firecontrolcompat$subLevels(root).size(),
                "missile-detach+math-discard");
    }

    /** One swept proximity check for one mathematically flown round entity. */
    private static void firecontrolcompat$checkTarget(ServerLevel level, Entity target) {
        if (target == null || target.isRemoved() || !target.isAlive()) {
            return;
        }
        if (!com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity.safetyFuseArmed(target)) {
            // Safety fuse: the heavy anti-air rounds cannot be brought down inside their arming run,
            // which is the window in which every "it exploded on its own launcher" report lived. The
            // sweep would otherwise settle a hit on the pad itself, because the pad is the one place
            // a fresh round is surrounded by exactly the shells, fragments and neighbouring rounds
            // the sweep looks for.
            com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity.reportSafetyFuse(target, "intercept");
            return;
        }
        if (InFlightRounds.spent(target)) {
            // Already claimed: an interception is one kill, and this round has been through the
            // sweep (or is going off right now) once already.
            return;
        }
        Vec3 at = target.position();
        if (at == null || !finite(at)) {
            return;
        }
        Vec3 velocity = target.getDeltaMovement();
        Vec3 from = velocity == null ? at : at.subtract(velocity);
        if (!finite(from)) {
            from = at;
        }
        Sweep sweep = firecontrolcompat$sweep(level, from, at, target);
        if (sweep == null) {
            return;
        }
        if (sweep.skip() != null) {
            // The only thing inside the fuze radius was something this round may not be judged
            // against: itself, a round off the same launcher, or a round still in its birth grace
            // window. Reported once per reason, then dropped - a round must never be its own
            // interceptor, and the "explodes the moment it leaves the tube" report was exactly that.
            firecontrolcompat$reportSkip(target, sweep.skip(),
                    sweep.shell() == null ? "-" : firecontrolcompat$registryName(sweep.shell()),
                    String.format(java.util.Locale.ROOT, "%.2f", sweep.distance()));
            return;
        }
        if (sweep.shell() == null) {
            return;
        }
        if (sweep.distance() > sweep.trigger()) {
            scanLog(level, String.valueOf(target.getUUID()), sweep.shell(), sweep.distance(),
                    sweep.trigger());
            return;
        }
        // A round spends itself on one burst. The mark lives on the interceptor, so a second
        // round of the same tick - or a later pass - cannot charge the same kill twice, and the
        // warhead is not detonated a second time at a point it never reached.
        if (firecontrolcompat$fuzeSpent(sweep.shell())) {
            return;
        }
        // Before anything goes off: claim the target and take it out of the in-flight registry.
        // Both are what stops the blast from being judged again - the burst below is an explosion,
        // which the cook-off pass sees, and a round still listed as a threat would be swept again
        // on the next tick. One kill, one warhead, one burst.
        InFlightRounds.markSpent(target);
        InFlightRounds.untrack(target);
        Boom boom = fireShell(level, sweep.shell(), sweep.point());
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}math-fuze: shell={} missile={} dist={} radius={} detonated={}",
                PREFIX, sweep.shell().getType().toString(), target.getUUID(),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.distance()),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.trigger()),
                boom.method());
        // The round is spent where the two position functions crossed. It has no body in the level,
        // so discarding it is the whole kill: the pack's own table drops it on the next tick (its
        // advance bails on a removed entity) and nothing of it reaches the ground.
        //
        // Discarding alone is what made an interception look like a round that simply vanished: the
        // kill was recorded and no warhead ever went off. A round of this mod therefore gets its own
        // detonation here (for the anti-tank round that is {@code AtgmBoom}, i.e. 面包学's own
        // blast and the shaped-charge jet), and anything else - a pack round flown as a record, with
        // no entity to detonate - gets the reference burst placed by hand at the interception point,
        // in this level. See {@code AtgmBoom#blastAt}.
        boolean selfBoom = firecontrolcompat$detonateTarget(target, level, sweep.point(), sweep.shell());
        if (!target.isRemoved()) {
            target.discard();
        }
        // See {@link #firecontrolcompat$interceptBurst}: the point is always given the cook-off
        // burst, whatever the round's own detonation did. A mathematical round has no body at all,
        // so without this the kill would be a log line and nothing else.
        int effect = firecontrolcompat$interceptBurst(level, sweep.point());
        InterceptMarks.remember(level, sweep.point(), now(level));
        if (sweep.missile()) {
            // A guided interceptor answered, not a shell: the crew asked for this shot, the round
            // has its own warhead and its own burst, and a miss here is a guidance question rather
            // than a fuze one - so the kill is reported on its own line, with the interceptor's
            // registry name (the thing the player actually loaded) rather than its Java class.
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}math-intercept(missile): missile={} by={} dist={} radius={} reason=ok"
                            + " boom={} effect=spawned body={} vfx={}",
                    PREFIX, target.getUUID(), firecontrolcompat$registryName(sweep.shell()),
                    String.format(java.util.Locale.ROOT, "%.2f", sweep.distance()),
                    String.format(java.util.Locale.ROOT, "%.2f", sweep.trigger()),
                    selfBoom ? "own" : "reference",
                    boom.method(), effect);
            return;
        }
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}math-intercept(swept): missile={} type={} by={} dist={} at={} radius={}"
                        + " reason=ok boom={} vfx={} body={}",
                PREFIX,
                target.getUUID(), target.getType().toString(), sweep.shell().getType().toString(),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.distance()), sweep.point(),
                String.format(java.util.Locale.ROOT, "%.2f", sweep.trigger()),
                selfBoom ? "own" : "reference", effect, boom.method());
    }

    /**
     * Blows the intercepted round up, at the point it was met, in the level the interception
     * happened in.
     *
     * <p>A round of this mod is asked to detonate itself: that is the same call its own impact makes,
     * so an anti-tank round goes off with 面包学's blast and its shaped-charge jet rather than with
     * whatever this sweep would invent. Everything else (a pack round flown as a record, which has no
     * entity in the level to detonate) is given the reference burst at the meeting point.</p>
     *
     * @return whether the round's own detonation ran
     */
    private static boolean firecontrolcompat$detonateTarget(Entity target, ServerLevel level, Vec3 at,
            Entity interceptor) {
        if (target instanceof com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity round) {
            try {
                round.detonate(at, "intercept:" + firecontrolcompat$registryName(interceptor));
                return true;
            } catch (Throwable failure) {
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "{}math-intercept: round {} refused its own detonation: {}", PREFIX,
                        target.getUUID(), failure.toString());
            }
        }
        try {
            com.qkdream.firecontrolcompat.AtgmBoom.blastAt(level, at,
                    "intercept:" + firecontrolcompat$registryName(interceptor));
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}math-intercept: reference burst refused at {}: {}", PREFIX, at,
                    failure.toString());
        }
        return false;
    }

    /**
     * One interceptor that came closest to a round, and how close it came.
     *
     * @param missile whether the interceptor is a guided missile rather than a shell, which
     *        changes the fuze radius it defaults to and the line the kill is reported on
     * @param skip why the closest candidate in reach may <b>not</b> be used as this round's
     *        interceptor ({@code self}, {@code same-owner}, {@code guard-age}), or null when the
     *        sweep settled on a real one. A sweep with a reason never kills anything: the caller
     *        reports it and moves on.
     */
    private record Sweep(Entity shell, double distance, Vec3 point, double trigger,
            boolean missile, String skip) {
    }

    /**
     * The nearest interceptor to one swept round segment, or null when there is none in reach.
     *
     * <p>Both sides are swept: the round over {@code from..at} and each interceptor over its own
     * last movement, because at 34 blocks per tick a round crosses the fuse radius between two
     * samples and a distance taken at the samples alone never sees the pass.</p>
     *
     * <p>The search box is the round's own segment inflated by {@link #RADIUS} - one box, sized by
     * the sweep, not by a fixed twenty-block bubble. A round that is only being looked at costs one
     * AABB query and nothing else; the per-candidate work is a segment solve over the handful of
     * rounds the box returned.</p>
     */
    private static Sweep firecontrolcompat$sweep(ServerLevel level, Vec3 from, Vec3 at, Entity threat) {
        if (level == null || at == null) {
            return null;
        }
        AABB box = new AABB(from == null ? at : from, at).inflate(RADIUS);
        List<Entity> shells = firecontrolcompat$interceptors(level, box);
        if (shells.isEmpty()) {
            return null;
        }
        firecontrolcompat$windowShells += shells.size();
        double best = Double.MAX_VALUE;
        Entity by = null;
        Vec3 hitPoint = at;
        double skippedBest = Double.MAX_VALUE;
        Entity skippedBy = null;
        Vec3 skippedPoint = null;
        String skippedReason = null;
        Vec3 start = from == null ? at : from;
        for (Entity shell : shells) {
            Vec3 to = firecontrolcompat$worldPosition(shell);
            if (!finite(to)) {
                continue;
            }
            Vec3 shellFrom = to.subtract(firecontrolcompat$worldVelocity(shell));
            double[] closest = closestPoints(start, at, shellFrom, to);
            double distance = closest[0];
            String rejected = firecontrolcompat$rejectReason(level, threat, shell);
            if (rejected != null) {
                // Kept aside rather than dropped: a candidate that may not answer for this round
                // still has to be able to say <em>why</em>, or a round that is skipped and a round
                // with nothing in reach look identical in the log. Only the closest one is kept,
                // and it is only ever reported - it never fuzes anything.
                if (distance < skippedBest) {
                    skippedBest = distance;
                    skippedBy = shell;
                    skippedReason = rejected;
                    skippedPoint = new Vec3(closest[1], closest[2], closest[3]);
                }
                continue;
            }
            if (distance < best) {
                best = distance;
                by = shell;
                hitPoint = new Vec3(closest[1], closest[2], closest[3]);
            }
        }
        if (by == null) {
            if (skippedBy == null) {
                return null;
            }
            double trigger = Math.min(RADIUS, Math.max(1.0D, fuzeRadius(skippedBy)));
            if (!(skippedBest <= trigger)) {
                // Out of that round's fuze radius: nothing was skipped, it simply is not close
                // enough to be worth a line.
                return null;
            }
            return new Sweep(skippedBy, skippedBest, skippedPoint == null ? at : skippedPoint,
                    trigger, firecontrolcompat$isMissile(skippedBy), skippedReason);
        }
        firecontrolcompat$windowNearest = Math.min(firecontrolcompat$windowNearest, best);
        double trigger = Math.min(RADIUS, Math.max(1.0D, fuzeRadius(by)));
        return new Sweep(by, best, hitPoint, trigger, firecontrolcompat$isMissile(by), null);
    }

    /**
     * Why {@code candidate} may not be the interceptor that answers for {@code threat}, or null
     * when it may.
     *
     * <p>Four things disqualify a candidate, and all four are the same mistake seen from different
     * sides - an entity answering for itself:</p>
     *
     * <ul>
     *   <li><b>self</b> - the candidate <em>is</em> the threat. The sweep's own search box is built
     *       around the threat's position, so the threat is always in its own candidate list at
     *       distance zero. This is the "the round explodes the moment it leaves the tube"
     *       regression: the first sweep after launch settled a hit between the round and
     *       itself.</li>
     *   <li><b>same-owner</b> - a projectile owner, a rack or a Sable structure the two share. Two
     *       rounds of one launcher are one platform: a salvo must never fuze on its own section, and
     *       a round sitting on the neighbouring pylon (a few blocks away, well inside the six block
     *       fuze) must not be an interceptor for the one leaving.</li>
     *   <li><b>guard-age</b> - a round still inside its first {@link InFlightRounds#GUARD_TICKS}
     *       ticks, either side. Nothing near a launcher is a decision that can be trusted yet.</li>
     * </ul>
     */
    private static String firecontrolcompat$rejectReason(ServerLevel level, Entity threat,
            Entity candidate) {
        if (candidate == null) {
            return null;
        }
        if (threat == null) {
            return null;
        }
        if (candidate == threat) {
            return "self";
        }
        try {
            if (candidate.getUUID() != null && candidate.getUUID().equals(threat.getUUID())) {
                return "self";
            }
        } catch (Throwable ignored) {
            // an entity without a readable id falls through to the platform checks
        }
        // Same owner: the shooter of both rounds is one entity (a player, a turret, a carrier).
        try {
            Entity mine = threat instanceof net.minecraft.world.entity.projectile.Projectile mineProjectile
                    ? mineProjectile.getOwner() : null;
            Entity theirs =
                    candidate instanceof net.minecraft.world.entity.projectile.Projectile theirProjectile
                            ? theirProjectile.getOwner() : null;
            if (mine != null && theirs != null
                    && (mine == theirs || (mine.getUUID() != null && mine.getUUID().equals(theirs.getUUID())))) {
                return "same-owner";
            }
        } catch (Throwable ignored) {
            // no owner on one of them: the platform checks below still apply
        }
        // Same rack / same launch platform. A round of this mod carries both, so a salvo and a
        // pylon neighbour are recognised without asking the world anything.
        try {
            if (threat instanceof com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity mine
                    && candidate instanceof com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity theirs) {
                net.minecraft.core.BlockPos myRack = mine.firecontrolcompat$launchSource();
                net.minecraft.core.BlockPos theirRack = theirs.firecontrolcompat$launchSource();
                if (myRack != null && myRack.equals(theirRack)) {
                    return "same-owner";
                }
                java.util.UUID myHull = mine.firecontrolcompat$launchSubLevel();
                java.util.UUID theirHull = theirs.firecontrolcompat$launchSubLevel();
                if (myHull != null && myHull.equals(theirHull)) {
                    return "same-owner";
                }
            }
        } catch (Throwable ignored) {
            // a round without launch data falls through to the IFF lookup
        }
        // Same IFF platform, which is how a round that carries no launch data of its own is still
        // tied to the hull that released it (see IffOwnership).
        try {
            if (level != null) {
                java.util.UUID myHull = com.qkdream.firecontrolcompat.iff.IffOwnership
                        .subLevelOf(level, threat);
                if (myHull != null) {
                    java.util.UUID theirHull = com.qkdream.firecontrolcompat.iff.IffOwnership
                            .subLevelOf(level, candidate);
                    if (myHull.equals(theirHull)) {
                        return "same-owner";
                    }
                }
            }
        } catch (Throwable ignored) {
            // no IFF on the platform: the identity and rack checks above already answered
        }
        // Birth grace window, on either side: a round that has just left its tube is not a target
        // and not an interceptor.
        if (InFlightRounds.guarded(threat) || InFlightRounds.guarded(candidate)) {
            return "guard-age";
        }
        return null;
    }

    /**
     * Reports a skipped interception, once per round per reason.
     *
     * <p>The line is the whole evidence that the sweep <em>looked</em>: without it "the round was
     * skipped on purpose" and "the sweep never reached the round" read the same in the log, and they
     * need opposite fixes. It is rate limited by round and by reason rather than by wall clock,
     * because the interesting case (a fresh round) is judged twenty times in twenty ticks and the
     * crew needs one line, not twenty.</p>
     */
    private static void firecontrolcompat$reportSkip(Entity round, String reason, String by,
            Object distance) {
        try {
            if (round == null || reason == null) {
                return;
            }
            if (!InFlightRounds.logOnce(round, reason)) {
                return;
            }
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}math-intercept skip: missile={} reason={} by={} dist={} age={}",
                    PREFIX, round.getUUID(), reason, by, distance, InFlightRounds.ageTicks(round));
        } catch (Throwable ignored) {
            // Reporting must never take the scan down.
        }
    }

    /**
     * Every interceptor inside the swept box, from the level the round is filed under and from the
     * Sable sub-levels of that level.
     *
     * <p>The root sweep is the shell case and has always been there. The sub-level pass is the
     * missile case: a launcher carried by a hull spawns its round into the plot's own
     * {@code ServerLevel} (see {@code MianbaoAaFireCompat#levelsOf}, which picks
     * {@code SubLevel#getLevel} first for exactly that reason), and a sub-level's entities are
     * never returned by a query on the root - nor is a sub-level one of
     * {@code MinecraftServer#getAllLevels}. A beam-riding or 便携式防空 round answering a
     * mathematical threat was therefore invisible to this sweep however close it passed: the log
     * said {@code missiles=1 shells=0} while six rounds were in their terminal dive.</p>
     *
     * <p>The two searches share one world-space box, which is what a sub-level's entities are
     * addressed in - {@code CompatScanner#scanEntities} reads a hull-borne round the same way.</p>
     */
    private static List<Entity> firecontrolcompat$interceptors(ServerLevel root, AABB box) {
        List<Entity> found = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        try {
            for (Entity entity : root.getEntities((Entity) null, box,
                    MathIntercept::firecontrolcompat$isCandidate)) {
                if (entity != null && seen.add(entity.getUUID())) {
                    found.add(entity);
                }
            }
            costWindowQueries++;
        } catch (Throwable ignored) {
            // a root query that fails still leaves the sub-level pass below
        }
        for (Plot plot : firecontrolcompat$plots(root)) {
            if (!firecontrolcompat$mayHit(plot, box)) {
                // A plot whose world bounds are nowhere near the round cannot hold the interceptor:
                // skipping the query is the whole point of caching the bounds (see the cost line,
                // which reports how many plot searches one tick actually made).
                costWindowSkipped++;
                continue;
            }
            try {
                for (Entity entity : plot.level().getEntitiesOfClass(Entity.class, box,
                        MathIntercept::firecontrolcompat$isCandidate)) {
                    if (entity != null && seen.add(entity.getUUID())) {
                        found.add(entity);
                    }
                }
                costWindowQueries++;
            } catch (Throwable ignored) {
                // one unreadable plot must not cost the sweep its other levels
            }
        }
        return found;
    }

    /** One Sable plot and the world box it occupies, resolved once per tick. */
    private record Plot(ServerLevel level, AABB bounds) {
    }

    /**
     * Whether a plot's world box can contain anything the round's box asks about.
     *
     * <p>A round sweeping mid-air over the ocean has no business running an entity query on a plot
     * parked two kilometres away, and that query is not free: it walks the plot's own section
     * storage. The bounds are the plot's real world extent, so the test is exact rather than a
     * guess - a plot that fails it cannot hold a candidate.</p>
     */
    private static boolean firecontrolcompat$mayHit(Plot plot, AABB box) {
        if (plot == null || box == null) {
            return false;
        }
        AABB bounds = plot.bounds();
        if (bounds == null) {
            // Unknown extent: never skip, a missed interceptor is worse than a wasted query.
            return true;
        }
        return bounds.intersects(box);
    }

    /** Candidate filter: anything that could be the interceptor, minus what the pack already flies. */
    private static boolean firecontrolcompat$isCandidate(Entity entity) {
        try {
            return firecontrolcompat$isShell(entity) && !nativeOwned(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether an entity is a guided missile rather than a shell. */
    private static boolean firecontrolcompat$isMissile(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity) {
            return true;
        }
        EntityType<?> type;
        try {
            type = entity.getType();
        } catch (Throwable ignored) {
            type = null;
        }
        if (type != null) {
            Boolean cached = MISSILE_TYPES.get(type);
            if (cached != null) {
                return cached;
            }
        }
        boolean missile = false;
        try {
            ResourceLocation key = type == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(type);
            missile = key != null && firecontrolcompat$missileName(key.getPath());
        } catch (Throwable ignored) {
            // an unregistered type falls through to the class name
        }
        if (!missile) {
            missile = firecontrolcompat$missileName(entity.getClass().getName());
        }
        if (type != null) {
            MISSILE_TYPES.put(type, missile);
        }
        return missile;
    }

    /** Whether a registry path or a class name says "missile": 面包学 spells it {@code tanshe}. */
    private static boolean firecontrolcompat$missileName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("missile") || lower.contains("tanshe");
    }

    /** One answer per entity type; the registry lookup and the lower-casing are not per-tick work. */
    private static final Map<EntityType<?>, Boolean> MISSILE_TYPES = new HashMap<>();

    /** The interceptor's registry name, which is what the player loaded and what a log should say. */
    private static String firecontrolcompat$registryName(Entity entity) {
        try {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (key != null) {
                return key.toString();
            }
        } catch (Throwable ignored) {
            // fall through to the type's own toString
        }
        try {
            return entity.getType().toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** Where an entity is in the world, projected out of a Sable plot when it rides one. */
    private static Vec3 firecontrolcompat$worldPosition(Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            Vec3 projected =
                    com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat
                            .getWorldPosition(entity);
            if (finite(projected)) {
                return projected;
            }
        } catch (Throwable ignored) {
            // the pack's projector is the preferred answer, never the only one
        }
        return entity.position();
    }

    /** How fast an entity is going in the world, in the same frame as its position. */
    private static Vec3 firecontrolcompat$worldVelocity(Entity entity) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        try {
            Vec3 projected =
                    com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat
                            .getWorldVelocity(entity);
            if (finite(projected)) {
                return projected;
            }
        } catch (Throwable ignored) {
            // fall back on the entity's own motion
        }
        Vec3 delta = entity.getDeltaMovement();
        return finite(delta) ? delta : Vec3.ZERO;
    }

    /** The root level of the world a level belongs to: itself unless it is a Sable plot. */
    public static ServerLevel rootOf(ServerLevel level) {
        return firecontrolcompat$rootOf(level);
    }

    /** The root level of the world a level belongs to: itself unless it is a Sable plot. */
    private static ServerLevel firecontrolcompat$rootOf(ServerLevel level) {
        if (level == null) {
            return null;
        }
        try {
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            Level root = container == null ? null : container.getLevel();
            if (root instanceof ServerLevel serverLevel) {
                return serverLevel;
            }
        } catch (Throwable ignored) {
            // no Sable, or a level it does not own: the level is its own root
        }
        return level;
    }

    /**
     * The server levels a round filed under this root may actually be flying in: every Sable plot
     * of that root. Rebuilt once a tick and shared by every round of the tick, because the list is
     * a property of the world and not of the round.
     */
    private static List<ServerLevel> firecontrolcompat$subLevels(ServerLevel root) {
        List<Plot> plots = firecontrolcompat$plots(root);
        if (plots.isEmpty()) {
            return List.of();
        }
        List<ServerLevel> levels = new ArrayList<>(plots.size());
        for (Plot plot : plots) {
            levels.add(plot.level());
        }
        return levels;
    }

    /**
     * The Sable plots of a root with their world boxes, rebuilt once a tick and shared by every
     * round of the tick.
     *
     * <p>The box is what makes the per-round sweep cheap: without it every round asks every plot
     * "do you have anything in this eight-block bubble" and a world with a fleet in it costs
     * {@code rounds x plots} queries per tick. With it, a plot is only asked when the round's own
     * swept box actually overlaps the plot's extent.</p>
     */
    private static List<Plot> firecontrolcompat$plots(ServerLevel root) {
        if (root == null) {
            return List.of();
        }
        long now = now(root);
        if (plotsKnown && plotsRoot == root && plotsTick == now) {
            return plots;
        }
        List<Plot> found = new ArrayList<>();
        try {
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(root);
            for (dev.ryanhcode.sable.sublevel.SubLevel sub
                    : container == null ? List.<dev.ryanhcode.sable.sublevel.SubLevel>of()
                            : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                if (sub.getLevel() instanceof ServerLevel plot && plot != root) {
                    found.add(new Plot(plot, firecontrolcompat$boundsOf(sub)));
                }
            }
        } catch (Throwable ignored) {
            // an unreadable container means "no plots", which is the same as no candidates there
        }
        plots = List.copyOf(found);
        plotsRoot = root;
        plotsTick = now;
        plotsKnown = true;
        return plots;
    }

    /** A plot's world extent, or null when the platform cannot say (then nothing is skipped). */
    private static AABB firecontrolcompat$boundsOf(dev.ryanhcode.sable.sublevel.SubLevel sub) {
        try {
            AABB bounds =
                    com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity
                            .subLevelWorldBounds(sub);
            if (bounds != null && Double.isFinite(bounds.minX) && Double.isFinite(bounds.maxX)
                    && Double.isFinite(bounds.minY) && Double.isFinite(bounds.maxY)
                    && Double.isFinite(bounds.minZ) && Double.isFinite(bounds.maxZ)) {
                return bounds;
            }
        } catch (Throwable ignored) {
            // no usable extent: the caller keeps querying this plot
        }
        return null;
    }

    private static boolean plotsKnown;
    private static ServerLevel plotsRoot;
    private static long plotsTick;
    private static List<Plot> plots = List.of();

    private static long now(ServerLevel level) {
        try {
            return level.getGameTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * Plays the interception out where it happened: an explosion look, a flash and a burst of
     * smoke, plus the sound of the burst.
     *
     * <p>Deliberately <em>not</em> a block-breaking explosion: the round's own warhead has already
     * been detonated by the pack at this point, and a second damaging blast at the same spot would
     * be both a doubled hit and a fresh hole in the terrain for every interception. What is missing
     * from a kill that happens in mid-air is the sight and the sound, so that is what is added.</p>
     *
     * <p>Sent to the players of the level one by one, because the eight-argument
     * {@code ServerLevel.sendParticles} only hands the packet to a player whose <em>block position</em>
     * is closer than 32 blocks to it ({@code closerToCenterThan(pos, 32.0D)} in the pack's own
     * bytecode) and drops it for everyone further away. A burst at the round's fuzing distance is
     * exactly that case: the kill happened, the client was never told, and the interception looked
     * silent. The explicit form takes a "far" flag that lifts the limit to 512 blocks, and a direct
     * packet is the last resort so that an aircraft parked in a sub-level's storage space - millions
     * of blocks from where it is drawn - cannot swallow the burst either.</p>
     *
     * @return how many particles the clients were told to spawn, or -1 when the effect failed
     */
    private static int firecontrolcompat$spawnInterceptEffect(ServerLevel level, Vec3 at) {
        if (level == null || at == null) {
            return -1;
        }
        // Raised a little: the meeting point can be a block inside terrain, where a burst is
        // clipped away instead of seen.
        Vec3 spot = at.add(0.0D, EFFECT_LIFT, 0.0D);
        int particles = 0;
        // Who could have seen it: a silent burst at 200 blocks and a burst with no player in the
        // level look the same in the log without this, and they need opposite fixes.
        int viewers = 0;
        double nearest = Double.MAX_VALUE;
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player == null) {
                continue;
            }
            viewers++;
            nearest = Math.min(nearest, Math.sqrt(player.position().distanceToSqr(spot)));
        }
        try {
            particles += firecontrolcompat$broadcastParticles(level,
                    net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, spot, 3,
                    0.5D, 0.5D, 0.5D, 0.0D);
            particles += firecontrolcompat$broadcastParticles(level,
                    net.minecraft.core.particles.ParticleTypes.EXPLOSION, spot, 4,
                    0.3D, 0.3D, 0.3D, 0.0D);
            particles += firecontrolcompat$broadcastParticles(level,
                    net.minecraft.core.particles.ParticleTypes.FLASH, spot, 2,
                    0.0D, 0.0D, 0.0D, 0.0D);
            particles += firecontrolcompat$broadcastParticles(level,
                    net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, spot, 24,
                    1.2D, 1.2D, 1.2D, 0.03D);
            particles += firecontrolcompat$broadcastParticles(level,
                    net.minecraft.core.particles.ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, spot, 6,
                    0.7D, 0.7D, 0.7D, 0.0D);
            // The sound is broadcast with a volume whose own reach (16 blocks per unit) has to
            // cover the aircraft, so the crew hears the burst as well as sees it.
            level.playSound(null, spot.x, spot.y, spot.z,
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    net.minecraft.sounds.SoundSource.HOSTILE, 8.0F, 0.85F);
            level.playSound(null, spot.x, spot.y, spot.z,
                    net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
                    net.minecraft.sounds.SoundSource.HOSTILE, 6.0F, 1.25F);
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept vfx: particles={} ok={} pos={} players={} nearest={}",
                    PREFIX, particles, particles > 0, spot, viewers,
                    nearest == Double.MAX_VALUE ? "none"
                            : String.format(java.util.Locale.ROOT, "%.1f", nearest));
            return particles;
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept vfx: particles={} ok=false pos={} players={} err={}",
                    PREFIX, particles, spot, viewers, failure.toString());
            return particles > 0 ? particles : -1;
        }
    }

    /** How far above the meeting point the burst is drawn, so it is not buried in a block. */
    private static final double EFFECT_LIFT = 0.2D;

    /**
     * The burst an interception is <em>seen</em> as: 载人航天's missile cook-off, not a single puff.
     *
     * <p>The look the crew asked for is the one {@code cbcmsmwcompat} gives a missile that cooks
     * off - {@code CookOffHandler.cookOffMissile} discards the round and queues
     * {@code CompatConfig.COOK_OFF_EXPLOSION_COUNT} CBC {@code ShellExplosion}s, spaced
     * {@code COOK_OFF_EXPLOSION_INTERVAL} ticks apart, each with a random horizontal jitter and
     * {@code CreateBigCannons.handleCustomExplosion} to detonate it. A single explosion at the
     * meeting point reads as a firework; a short rolling series reads as a magazine going up, which
     * is what a missile being hit looks like.</p>
     *
     * <p>That helper is public in {@code com.qkdream.cbcmsmwcompat.ammorack.CookOffHandler} - a mod
     * of ours, shipped alongside this one - so it is called through reflection: this mod must keep
     * loading with {@code cbcmsmwcompat} absent, and a hard reference would take the whole mod down
     * at class load instead. When it is absent the same shape is made here out of one CBC
     * {@code ShellExplosion}, and the particle burst and the sound are added either way - they are
     * broadcast to every player, which is the part that made the burst visible at 200 blocks.</p>
     *
     * @return how many particles the clients were told to spawn, or -1 when even those failed
     */
    private static int firecontrolcompat$interceptBurst(ServerLevel level, Vec3 at) {
        if (level == null || at == null) {
            return -1;
        }
        // The immediate blast goes first, in this very tick, before anything is queued: a cook-off
        // series is spaced several ticks apart and belongs to 载人航天/cbcmsmwcompat's own tick
        // handler, so on its own it can be invisible when that handler is not running - which is
        // exactly the "the kill made no explosion" report. A vanilla explosion and an immediate CBC
        // flak shell are both rendered by the client from a packet sent right here.
        boolean immediate = firecontrolcompat$immediateBurst(level, at);
        int queued = 0;
        String style = "none";
        try {
            queued = firecontrolcompat$cookOffBurst(level, at);
            if (queued > 0) {
                style = "cookoff";
            }
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept-vfx: style=cookoff ok=false pos={} err={}", PREFIX, at, failure.toString());
        }
        int particles = firecontrolcompat$spawnInterceptEffect(level, at);
        int players = 0;
        try {
            players = level.players().size();
        } catch (Throwable ignored) {
            players = 0;
        }
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}intercept-burst: immediate={} queued={} style={} players={} particles={} pos={}",
                PREFIX, immediate, queued, style, players, particles, at);
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}intercept-vfx: style={} particles={} sound={} pos={}",
                PREFIX, style, particles,
                "minecraft:entity.generic.explode+firework_rocket_large_blast", at);
        return particles;
    }

    /** Strength of the blast this mod detonates itself on every interception. */
    private static final float IMMEDIATE_STRENGTH = 2.5F;

    /** How many cook-off explosions are asked for; the helper spaces them a couple of ticks apart. */
    private static final int COOK_OFF_COUNT = 3;

    /**
     * The blast the crew sees the instant the round is hit.
     *
     * <p>Two blasts are made here, both synchronously:</p>
     *
     * <ul>
     *   <li>a plain {@code ServerLevel#explode} with {@code ExplosionInteraction.NONE} - no block is
     *       broken and nothing burns, but every client is sent the vanilla explosion packet, which is
     *       the fireball, the smoke and the boom without any mod having to be present;</li>
     *   <li>Create Big Cannons' own {@code FlakExplosion} through
     *       {@code CreateBigCannons.handleCustomExplosion}, detonated <em>now</em> rather than queued,
     *       so a flak kill still looks like flak.</li>
     * </ul>
     *
     * <p>The queued cook-off series is added on top of this by {@link #firecontrolcompat$interceptBurst}
     * - it is the rolling "magazine going up" shape, not a replacement for the first blast.</p>
     *
     * @return whether at least one of the two blasts was made
     */
    private static boolean firecontrolcompat$immediateBurst(ServerLevel level, Vec3 at) {
        boolean any = false;
        try {
            level.explode(null, at.x, at.y, at.z, IMMEDIATE_STRENGTH, false,
                    net.minecraft.world.level.Level.ExplosionInteraction.NONE);
            any = true;
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept-burst: vanilla explode refused at {}: {}", PREFIX, at, failure.toString());
        }
        try {
            FlakExplosion explosion = new FlakExplosion(level, null,
                    level.damageSources().explosion(null, null),
                    at.x, at.y, at.z, IMMEDIATE_STRENGTH, IMMEDIATE_STRENGTH,
                    Explosion.BlockInteraction.KEEP);
            CreateBigCannons.handleCustomExplosion(level, explosion);
            any = true;
        } catch (Throwable ignored) {
            // Create Big Cannons absent, or its factory moved: the vanilla blast above still showed.
        }
        return any;
    }

    /**
     * The cook-off burst itself: {@code cbcmsmwcompat}'s own helper when that mod is loaded,
     * otherwise the same shape made here.
     *
     * <p>Smoke and fire are switched on for the looks; the count is {@link #COOK_OFF_COUNT}, which
     * is the interval the cook-off uses (one every two ticks) without turning one interception into
     * a barrage.</p>
     *
     * @return how many explosions were asked for, zero when neither path could place one
     */
    private static int firecontrolcompat$cookOffBurst(ServerLevel level, Vec3 at) {
        try {
            Class<?> handler = Class.forName("com.qkdream.cbcmsmwcompat.ammorack.CookOffHandler");
            java.lang.reflect.Method schedule = handler.getMethod("scheduleCookOffExplosions",
                    ServerLevel.class, Vec3.class, double.class,
                    boolean.class, boolean.class, int.class);
            schedule.invoke(null, level, at, 1.0D, true, true, COOK_OFF_COUNT);
            return COOK_OFF_COUNT;
        } catch (Throwable ignored) {
            // cbcmsmwcompat absent (or its signature moved): build the same burst here.
        }
        try {
            FlakExplosion explosion = new FlakExplosion(level, null, level.damageSources().explosion(null, null),
                    at.x, at.y, at.z, FALLBACK_STRENGTH, FALLBACK_STRENGTH, Explosion.BlockInteraction.KEEP);
            CreateBigCannons.handleCustomExplosion(level, explosion);
            return 1;
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept-vfx: cookoff burst unavailable at {}: {}", PREFIX, at, failure.toString());
            return 0;
        }
    }

    /**
     * One particle burst, handed to every player of the level.
     *
     * @return how many particles were accepted, or zero when no client could be reached
     */
    private static int firecontrolcompat$broadcastParticles(ServerLevel level,
            net.minecraft.core.particles.ParticleOptions type, Vec3 at, int count,
            double dx, double dy, double dz, double speed) {
        int delivered = 0;
        net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket packet = null;
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player == null) {
                continue;
            }
            boolean sent = false;
            try {
                if (player.blockPosition().closerToCenterThan(at, 512.0D)) {
                    // The "far" flag is the difference between 512 blocks and the default 32.
                    sent = level.sendParticles(player, type, true, at.x, at.y, at.z, count,
                            dx, dy, dz, speed);
                }
            } catch (Throwable ignored) {
                sent = false;
            }
            if (!sent) {
                try {
                    if (packet == null) {
                        packet = new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                                type, true, at.x, at.y, at.z, (float) dx, (float) dy, (float) dz,
                                (float) speed, count);
                    }
                    player.connection.send(packet);
                    sent = true;
                } catch (Throwable ignored) {
                    sent = false;
                }
            }
            if (sent) {
                delivered++;
            }
        }
        return delivered > 0 ? count * delivered : 0;
    }

    /**
     * How wide a round's own fuse is, when it carries one.
     *
     * <p>Read through the getter or field the entity exposes ({@code proximityRadius},
     * {@code fuzeRadius}, {@code fuseRadius}). The walk goes up the class hierarchy: a
     * beam-riding round declares {@code proximityRadius} on {@code BeamRidingMissileEntity} and
     * every one of the six rounds in service is a subclass, so a declared-field lookup on the leaf
     * class answered nothing and every one of them fused at the shell default.</p>
     *
     * <p>Nothing is assumed beyond the family: a missile that publishes no range fuses at
     * {@link #DEFAULT_MISSILE_FUZE_RADIUS}, a shell at {@link #DEFAULT_FUZE_RADIUS}, and any
     * failure falls back the same way.</p>
     */
    private static double fuzeRadius(Entity shell) {
        double own = firecontrolcompat$readRadius(shell);
        if (own > 0.0D) {
            return own;
        }
        return firecontrolcompat$isMissile(shell)
                ? DEFAULT_MISSILE_FUZE_RADIUS
                : DEFAULT_FUZE_RADIUS;
    }

    /** The radius the round itself publishes, or {@code 0} when it publishes none. */
    private static double firecontrolcompat$readRadius(Entity shell) {
        if (shell == null) {
            return 0.0D;
        }
        // A fuze fire control configured is written on the round, not kept in a field: a
        // 便携式防空 round carries the range its computer set ({@code OrdinaryAirDefenseGuidance}
        // reads the same tag for its own fuze), and a display interceptor carries the display's.
        // That is the truest answer to "how wide is this round's fuse", so it is asked first.
        try {
            net.minecraft.nbt.CompoundTag data = shell.getPersistentData();
            for (String tag : new String[] {"CreateFireControlOrdinaryAaFuse",
                    "CreateFireControlDisplayFuse"}) {
                if (data.contains(tag)) {
                    double value = data.getDouble(tag);
                    if (Double.isFinite(value) && value > 0.0D) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
            // a round without persistent data simply has no configured fuse
        }
        for (Class<?> type = shell.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (String name : new String[] {"proximityRadius", "fuzeRadius", "fuseRadius",
                    "getProximityRadius"}) {
                try {
                    java.lang.reflect.Method method = type.getDeclaredMethod(name);
                    if (method.getReturnType() == double.class
                            || method.getReturnType() == float.class) {
                        method.setAccessible(true);
                        double value = ((Number) method.invoke(shell)).doubleValue();
                        if (Double.isFinite(value) && value > 0.0D) {
                            return value;
                        }
                    }
                } catch (Throwable ignored) {
                    // try the next shape
                }
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    if (field.getType() == double.class || field.getType() == float.class) {
                        double value = field.getDouble(shell);
                        if (Double.isFinite(value) && value > 0.0D) {
                            return value;
                        }
                    }
                } catch (Throwable ignored) {
                    // try the next shape
                }
            }
        }
        return 0.0D;
    }

    /** What the shell's own detonation did, for the log and for the burst's strength. */
    private record Boom(String method, float strength, boolean ownVfx) {
    }

    /** Set on a round once its fuze has been simulated, so a round never bursts twice. */
    private static final String FUZE_SIM_KEY = "firecontrolcompat:fuze_sim";

    /** Strength of the blast this mod makes itself when a round has no detonation to call. */
    private static final float FALLBACK_STRENGTH = 2.5F;

    /** Printed strength for a missile warhead the pack detonated: it picks its own, per round. */
    private static final float PACK_STRENGTH = -1.0F;

    /**
     * Detonates a guided missile through the pack's own warhead path.
     *
     * <p>{@code MianbaoMissileDetonation.detonate(Entity, Vec3)} is what the pack itself calls when
     * a round's fuze fires, and it is guarded by a {@code CreateFireControlDetonated} tag, so
     * calling it on a round the pack has already spent is a no-op rather than a second blast.</p>
     *
     * @return a printable name of the path that ran, or null when the round has no such warhead
     */
    private static String firecontrolcompat$packDetonate(Entity shell, Vec3 at) {
        try {
            com.hooya.stabilizedturret.content.controller.MianbaoMissileDetonation
                    .detonate(shell, at);
            return "MianbaoMissileDetonation#detonate";
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept boom: missile-warhead refused pos={} err={}", PREFIX, at,
                    failure.toString());
            return null;
        }
    }

    /**
     * A blast that only looks and sounds like one: no block is broken and nothing is hurt.
     *
     * <p>The shell's own detonation is what does damage, and it is only missing when a round has no
     * detonation at all. The burst made here is therefore a second chance to be seen, never a
     * second warhead: the crew's own aircraft and the gun that fired can be right next to it.</p>
     */
    private static final net.minecraft.world.level.ExplosionDamageCalculator BLAST_LOOK_ONLY =
            new net.minecraft.world.level.ExplosionDamageCalculator() {
                @Override
                public boolean shouldBlockExplode(net.minecraft.world.level.Explosion explosion,
                        net.minecraft.world.level.BlockGetter reader, net.minecraft.core.BlockPos pos,
                        net.minecraft.world.level.block.state.BlockState state, float power) {
                    return false;
                }

                @Override
                public boolean shouldDamageEntity(net.minecraft.world.level.Explosion explosion,
                        Entity entity) {
                    return false;
                }
            };

    /**
     * Spends the shell on the burst.
     *
     * <p>The round's own detonation is preferred - it carries that round's warhead, fragments and
     * sound - and is looked up by walking the class and its superclasses, because the entry point
     * belongs to another mod, is not public, and is inherited. The flak round fire control's guns
     * actually fire, {@code com.cbcaddon.addon.entity.ShrapnelAutocannonProjectile}, declares
     * {@code protected void detonate(net.minecraft.core.Position)} on top of
     * {@code FlakAutocannonProjectile#detonate(net.minecraft.core.Position)}; that method builds
     * the CBC {@code FlakExplosion}, spawns the {@code FLAK_BURST} and the fragment cone. The old
     * lookup asked for a <em>public</em> {@code detonate(Vec3)}, which is neither public nor keyed on
     * {@code Vec3}, so it found nothing, fell through to {@code discard()} and still answered
     * "detonated": the round disappeared with its warhead unspent, which is the missing burst the
     * report describes.</p>
     *
     * <p>When the shell really has no entry point of its own the burst is made here: a plain
     * explosion that does not touch the terrain ({@link net.minecraft.world.level.Level.ExplosionInteraction#NONE}).
     * The shell is spent either way, so it cannot fly on and burst again somewhere else.</p>
     */
    private static Boom fireShell(ServerLevel level, Entity shell, Vec3 at) {
        if (shell == null || at == null) {
            return new Boom("none", 0.0F, false);
        }
        // A fuze is spent once. The mark lives on the round itself, so two sweeps in the
        // same tick - or a second sweep after a re-entry - cannot burst it twice.
        if (firecontrolcompat$fuzeSpent(shell)) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}fuze-sim: shell={} point={} method=already-spent ok=true",
                    PREFIX, shell.getType().toString(), at);
            return new Boom("already-spent", 0.0F, true);
        }
        firecontrolcompat$markFuze(shell);
        // 1. The round's own detonation: its warhead, its fragments, its sound and - this is
        //    the point - its own burst, so nothing of ours is drawn on top of it.
        String own = firecontrolcompat$detonateOwn(shell, at);
        if (own != null) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept boom: method={} strength=own pos={}",
                    PREFIX, own, at);
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}fuze-sim: level=own method={} ok=true shell={} pos={}",
                    PREFIX, own);
            try {
                if (!shell.isRemoved()) {
                    shell.discard();
                }
            } catch (Throwable ignored) {
                // a spent round that cannot be removed is the pack's business
            }
            return new Boom(own, 0.0F, true);
        }
        if (level == null) {
            return new Boom("none", 0.0F, false);
        }
        // 2. A guided missile that declares no detonation of its own still has a warhead: the pack
        //    detonates every one of its rounds through {@code MianbaoMissileDetonation}, which is
        //    the same entry point its own fuze calls ({@code OrdinaryAirDefenseGuidance.tick}), and
        //    that call is idempotent - it tags the round
        //    {@code CreateFireControlDetonated} first. So a 便携式防空 round answering a
        //    mathematical threat goes off exactly as it would on a real contact: its own strength,
        //    its own broadcast, its own damage and its own removal. Shells are deliberately left
        //    out of this step: a cannon round has no missile warhead and the flak burst below is
        //    the right one for it.
        if (firecontrolcompat$isMissile(shell)) {
            String pack = firecontrolcompat$packDetonate(shell, at);
            if (pack != null) {
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "{}intercept boom: method={} pos={}", PREFIX, pack, at);
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "{}fuze-sim: level=missile-warhead method={} ok=true shell={} pos={}",
                        PREFIX, pack, firecontrolcompat$registryName(shell), at);
                try {
                    if (!shell.isRemoved()) {
                        shell.discard();
                    }
                } catch (Throwable ignored) {
                    // a round the pack already removed needs nothing here
                }
                return new Boom(pack, PACK_STRENGTH, true);
            }
        }
        // 3. No entry point of its own: build the burst with CBC's own explosion instead of
        //    a pile of vanilla particles, so it looks and sounds exactly like a CBC flak
        //    burst. Block damage and entity damage are both held at zero - this stands in
        //    for a fuze that never had a warhead, and the crew's own aircraft can be right
        //    next to the meeting point.
        String cbc = firecontrolcompat$cbcBurst(level, shell, at);
        if (cbc != null) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept boom: method={} strength={} pos={}",
                    PREFIX, cbc, FALLBACK_STRENGTH, at);
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}fuze-sim: level=cbcfactory method={} ok=true strength={} pos={}",
                    PREFIX, cbc, FALLBACK_STRENGTH, at);
            try {
                shell.discard();
            } catch (Throwable ignored) {
                // nothing else to do
            }
            return new Boom(cbc, FALLBACK_STRENGTH, true);
        }
        // 4. Last resort: a blast that only looks and sounds like one.
        try {
            level.explode(shell, net.minecraft.world.level.Explosion.getDefaultDamageSource(level, shell),
                    BLAST_LOOK_ONLY, at.x, at.y, at.z, FALLBACK_STRENGTH, false,
                    Level.ExplosionInteraction.NONE);
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept boom: method=fallback strength={} pos={} failed={}",
                    PREFIX, FALLBACK_STRENGTH, at, failure.toString());
        }
        try {
            shell.discard();
        } catch (Throwable ignored) {
            // nothing else to do
        }
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}intercept boom: method=fallback strength={} pos={}",
                PREFIX, FALLBACK_STRENGTH, at);
        com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                "{}fuze-sim: level=fallback method=vanilla-explode ok=true strength={} pos={}",
                PREFIX, FALLBACK_STRENGTH, at);
        return new Boom("fallback", FALLBACK_STRENGTH, false);
    }

    /** Whether this round's fuze has already been simulated. */
    private static boolean firecontrolcompat$fuzeSpent(Entity shell) {
        try {
            return shell.getPersistentData().getBoolean(FUZE_SIM_KEY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Marks the round as spent, so a second sweep cannot burst it again. */
    private static void firecontrolcompat$markFuze(Entity shell) {
        try {
            shell.getPersistentData().putBoolean(FUZE_SIM_KEY, true);
        } catch (Throwable ignored) {
            // a round without persistent data simply has no guard
        }
    }

    /**
     * CBC's own flak explosion, built through the pack's factory so the burst is the same
     * object the pack's flak rounds make: same particles, same sound packet, same client
     * handling.
     *
     * <p>Both damage powers are zero and the block interaction is {@code KEEP}: this burst
     * stands in for a fuze the round never had, so it must not be a second warhead. The
     * seventh argument is the blast's <em>radius</em> (CBC files it as
     * {@code blockDamagePower}), so the visual size is kept while nothing is broken.</p>
     *
     * @return a printable name of the path that ran, or null when CBC could not build it
     */
    private static String firecontrolcompat$cbcBurst(ServerLevel level, Entity shell, Vec3 at) {
        try {
            net.minecraft.world.damagesource.DamageSource source =
                    level.damageSources().explosion(shell, shell);
            FlakExplosion explosion = new FlakExplosion(level, shell, source,
                    at.x, at.y, at.z, FALLBACK_STRENGTH, 0.0F, Explosion.BlockInteraction.KEEP);
            CreateBigCannons.handleCustomExplosion(level, explosion);
            return "CBC#FlakExplosion";
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}intercept boom: cbc-factory failed pos={} err={}",
                    PREFIX, at, failure.toString());
            return null;
        }
    }

    /**
     * The one-argument detonation of the shell, whatever its name, visibility or declaring class.
     *
     * <p>The single argument is matched by "a parameter a {@link Vec3} can be passed as", so the
     * {@code net.minecraft.core.Position} of CBC's flak rounds is found as readily as a
     * {@code Vec3}. Both the one-argument and the no-argument shapes are tried; a shell with
     * neither answers {@code null} and the caller makes the burst itself.</p>
     *
     * @return a printable name of the entry point that ran, or null when the shell has none
     */
    private static String firecontrolcompat$detonateOwn(Entity shell, Vec3 at) {
        java.util.List<String> names =
                java.util.List.of("detonate", "detonateProjectile", "explode", "onHit");
        for (Class<?> type = shell.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Method[] declared;
            try {
                declared = type.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (java.lang.reflect.Method method : declared) {
                if (!names.contains(method.getName())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || !parameters[0].isAssignableFrom(Vec3.class)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(shell, at);
                    return type.getSimpleName() + "#" + method.getName()
                            + "(" + parameters[0].getSimpleName() + ")";
                } catch (Throwable ignored) {
                    // try the next shape
                }
            }
        }
        // A missile states the way it went off as well as where: our own beam-riding family
        // declares {@code detonate(Vec3, String cause)}, which the one-argument shapes above cannot
        // see, so a round of ours used to be discarded with its warhead unspent and only the
        // no-damage CBC burst standing in for it. The cause is the reason the fuze fired.
        for (Class<?> type = shell.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Method[] declared;
            try {
                declared = type.getDeclaredMethods();
            } catch (Throwable ignored) {
                continue;
            }
            for (java.lang.reflect.Method method : declared) {
                if (!names.contains(method.getName())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 2 || !parameters[0].isAssignableFrom(Vec3.class)
                        || parameters[1] != String.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(shell, at, "intercept");
                    return type.getSimpleName() + "#" + method.getName()
                            + "(" + parameters[0].getSimpleName() + ",String)";
                } catch (Throwable ignored) {
                    // try the next shape
                }
            }
        }
        for (String name : names) {
            try {
                java.lang.reflect.Method method = shell.getClass().getMethod(name);
                method.setAccessible(true);
                method.invoke(shell);
                return shell.getClass().getSimpleName() + "#" + name + "()";
            } catch (Throwable ignored) {
                // try the next shape
            }
        }
        return null;
    }

    /**
     * Least distance between two segments, as {@code {distance, x, y, z}} with the point on the
     * first segment. Standard clamped closest-approach solve; degenerate segments fall back to
     * their own end points.
     */
    private static double[] closestPoints(Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1) {
        Vec3 da = a1.subtract(a0);
        Vec3 db = b1.subtract(b0);
        Vec3 r = a0.subtract(b0);
        double a = da.dot(da);
        double e = db.dot(db);
        double f = db.dot(r);
        double s;
        double t;
        if (a <= 1.0E-12 && e <= 1.0E-12) {
            Vec3 p = a0;
            return new double[] {p.distanceTo(b0), p.x, p.y, p.z};
        }
        if (a <= 1.0E-12) {
            s = 0.0;
            t = clamp01(f / e);
        } else {
            double c = da.dot(r);
            if (e <= 1.0E-12) {
                t = 0.0;
                s = clamp01(-c / a);
            } else {
                double b = da.dot(db);
                double denominator = a * e - b * b;
                s = denominator > 1.0E-12 ? clamp01((b * f - c * e) / denominator) : 0.0;
                t = (b * s + f) / e;
                if (t < 0.0) {
                    t = 0.0;
                    s = clamp01(-c / a);
                } else if (t > 1.0) {
                    t = 1.0;
                    s = clamp01((b - c) / a);
                }
            }
        }
        Vec3 p = a0.add(da.scale(s));
        Vec3 q = b0.add(db.scale(t));
        return new double[] {p.distanceTo(q), p.x, p.y, p.z};
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    /** One scan report per second at most, so the check can be watched without filling the log. */
    private static long lastScanLog;

    private static void scanLog(ServerLevel level, String missile, Entity shell, double distance,
            double trigger) {
        try {
            long now = level.getGameTime();
            // The seed is 0 and is tested directly: `now - Long.MIN_VALUE` wraps to a negative
            // number, so the comparison stays true forever and this line - the only evidence that
            // missiles are being followed at all - could never be printed.
            if (lastScanLog != 0L && now - lastScanLog < 20L) {
                return;
            }
            lastScanLog = now;
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "{}math-intercept near: missile={} nearest={} dist={} radius={}",
                    PREFIX,
                    missile, firecontrolcompat$registryName(shell),
                    String.format(java.util.Locale.ROOT, "%.2f", distance),
                    String.format(java.util.Locale.ROOT, "%.2f", trigger));
        } catch (Throwable ignored) {
            // Reporting must never take the scan down.
        }
    }

    /**
     * Whether fire control's own guided interceptor is already flying this
     * entity, i.e. whether the pack's 0.9.4 moving fuze owns the shot.
     *
     * <p>0.9.4 added {@code AutoDefenseMissileGuidance.onMissileFuzes}, which
     * sweeps the interceptor and the threat it was launched at against each
     * other and settles the meeting point itself (see {@code MovingMissileFuze}).
     * Those interceptors are ordinary projectiles too, so without this check a
     * burst fired at something else would eat one on its way past. They are
     * kept out of our shell set, and the pack's fuze gets to answer for them.</p>
     *
     * <p>Reflective and cached: when the pack's field moves, this quietly
     * answers "not owned" and the interception falls back to the old behaviour.</p>
     */
    private static boolean nativeOwned(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            java.util.Map<?, ?> guided = nativeGuided();
            return guided != null && guided.containsKey(entity);
        } catch (Throwable ignored) {
            nativeGuidedField = NO_FIELD;
            return false;
        }
    }

    private static final Object NO_FIELD = new Object();
    private static Object nativeGuidedField;

    private static java.util.Map<?, ?> nativeGuided() {
        Object field = nativeGuidedField;
        if (field == null) {
            try {
                Class<?> guidance = Class.forName("com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance");
                java.lang.reflect.Field declared = guidance.getDeclaredField("guided");
                declared.setAccessible(true);
                field = declared;
            } catch (Throwable ignored) {
                field = NO_FIELD;
            }
            nativeGuidedField = field;
        }
        if (field == NO_FIELD) {
            return null;
        }
        try {
            Object value = ((java.lang.reflect.Field) field).get(null);
            return value instanceof java.util.Map<?, ?> map ? map : null;
        } catch (Throwable ignored) {
            nativeGuidedField = NO_FIELD;
            return null;
        }
    }

    /**
     * Anything a proximity fuze would answer to.
     *
     * <p>Two families, and the second one is the point of this method:</p>
     *
     * <ul>
     *   <li>shells - our cannon rounds, Create Big Cannons' flak, and any other
     *       {@code Projectile} in the air;</li>
     *   <li>guided missiles - our beam-riding / infrared / heavy / 涅瓦 / 巡飞 family, and the
     *       pack's own anti-air rounds ({@code portableantiairmissiletanshe},
     *       {@code antiairmissiletanshe}, {@code aim120}...), which the launcher spawns in the
     *       level and which fly an ordinary projectile flight.</li>
     * </ul>
     *
     * <p>The missile test is by registry key (and, when a type cannot be resolved, by class name),
     * never by a string scattered through the call site: 面包学 registers its rounds as
     * {@code ...tanshe...} and everything else spells {@code missile}, so both spellings are one
     * predicate here.</p>
     *
     * <p>A mathematical round is never an interceptor: it has no body in the level, and if the pack
     * ever hands one over it is the thing being shot at.</p>
     */
    private static boolean firecontrolcompat$isShell(Entity entity) {
        if (entity == null || entity.isRemoved() || !entity.isAlive()) {
            return false;
        }
        if (com.qkdream.firecontrolcompat.missile.MathMissileMirror.contains(entity)) {
            return false;
        }
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile) {
            return true;
        }
        if (entity instanceof com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity) {
            return true;
        }
        String name = entity.getClass().getName();
        if (name.startsWith("rbasamoyai.createbigcannons")
                || name.startsWith("com.qkdream.firecontrolcompat")) {
            return true;
        }
        return firecontrolcompat$isMissile(entity);
    }

    private static boolean detonate(MissileManager manager, ServerLevel level, VirtualMissile missile, Vec3 at) {
        try {
            Method method = detonateMethod();
            if (method == null) {
                return false;
            }
            Object result = method.invoke(null, level, missile.id(), at);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static Method detonateMethod() {
        if (!looked) {
            looked = true;
            try {
                Method found = MissileManager.class.getDeclaredMethod("detonate",
                        ServerLevel.class, java.util.UUID.class, Vec3.class);
                found.setAccessible(true);
                detonate = found;
            } catch (Throwable ignored) {
                detonate = null;
            }
        }
        return detonate;
    }

    /** Startup cost probe: closes the static initialisation window opened at the top. */
    static {
        StartupProbe.staticEnd("MathIntercept");
    }
}
