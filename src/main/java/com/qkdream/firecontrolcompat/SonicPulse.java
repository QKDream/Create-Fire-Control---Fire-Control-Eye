package com.qkdream.firecontrolcompat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.qkdream.firecontrolcompat.sonar.SonarKind;
import com.qkdream.firecontrolcompat.sonar.SonarLog;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vibrations the vanilla sculk network can hear.
 *
 * <p>Vanilla only turns a handful of events into vibrations: footsteps, doors,
 * explosions and so on. Everything a factory does - a diesel engine turning
 * over, a boiler hissing, a battery of guns firing, tracks grinding along the
 * ground - is silent as far as sculk sensors and shriekers are concerned. This
 * class registers six game events that carry those noises into the same
 * network, so a sensor wired to a machine hears it run and a shrieker answers
 * it exactly like it would answer a step.</p>
 *
 * <p>Machines on a Sable structure are ticked in the structure's plot space,
 * which is nowhere near where the player sees the ship; every pulse is
 * projected into world space first, so the vibration goes off where the machine
 * looks like it is. The same projection keeps a machine on a moving ship
 * audible while it travels.</p>
 *
 * <p>Guided munitions are covered the same way: the launch is a shot, the
 * cruise is a flight, and a torpedo swimming is a body in the water. A hull
 * that moves through water displaces it and is heard too - the wake is read
 * off the structure's own bounding box position, because Sable never turns a
 * splash into an event of its own.</p>
 */
public final class SonicPulse {

    /** Pumps, engines and burners running. */
    public static final DeferredRegister<GameEvent> GAME_EVENTS =
            DeferredRegister.create(Registries.GAME_EVENT, FireControlCompat.MOD_ID);

    /** Something is running: an engine, a burner, a gearbox under load. */
    public static final DeferredHolder<GameEvent, GameEvent> MACHINE_RUN =
            GAME_EVENTS.register("machine_run", () -> new GameEvent(16));

    /** Wheels and tracks are rolling over the ground. */
    public static final DeferredHolder<GameEvent, GameEvent> RUNNING_GEAR =
            GAME_EVENTS.register("running_gear", () -> new GameEvent(16));

    /** A gun went off. */
    public static final DeferredHolder<GameEvent, GameEvent> GUN_FIRE =
            GAME_EVENTS.register("gun_fire", () -> new GameEvent(48));

    /** A guided munition is on its way. */
    public static final DeferredHolder<GameEvent, GameEvent> MISSILE_FLIGHT =
            GAME_EVENTS.register("missile_flight", () -> new GameEvent(24));

    /** A torpedo is swimming towards its target. */
    public static final DeferredHolder<GameEvent, GameEvent> TORPEDO_RUN =
            GAME_EVENTS.register("torpedo_run", () -> new GameEvent(20));

    /** Water is being pushed aside: a hull under way, or something hitting it. */
    public static final DeferredHolder<GameEvent, GameEvent> WATER_IMPACT =
            GAME_EVENTS.register("water_impact", () -> new GameEvent(32));

    /** Ticks between two pulses from the same kind of source. */
    private static final int MACHINE_PERIOD = 10;
    private static final int GEAR_PERIOD = 10;
    /** Munitions cross a sensor's range in a heartbeat, so they pulse faster. */
    private static final int FLIGHT_PERIOD = 6;
    /** Water carries a hull's wake: half a second between two pulses is plenty. */
    private static final int WATER_PERIOD = 10;
    /** A decoy sings once a second, the rate a gun going off is filed at. */
    private static final int DECOY_PERIOD = 20;

    /**
     * How far outside a hull's own box a shot may land and still count as that
     * hull's: a barrel pokes out of the ship it stands on, and the muzzle is
     * where the round is born, but the noise belongs to the ship.
     */
    private static final double GUNFIRE_HULL_INFLATE = 4.0D;

    /** Last pulse per moving source, so a missile does not fire twenty a second. */
    private static final Map<Object, Long> LAST_PULSE = new HashMap<>();
    private static final int MAX_TRACKED_SOURCES = 2048;

    /** One line every few seconds proving the pulses leave, for a test run. */
    private static final long REPORT_PERIOD_NANOS = 10_000_000_000L;
    private static long lastReportNanos = -1L;

    private SonicPulse() {
    }

    /** Wires the events into the game's registry. */
    public static void register(IEventBus modEventBus) {
        GAME_EVENTS.register(modEventBus);
    }

    /** A machine that is running right now. */
    public static void machine(BlockEntity source) {
        pulse(source, MACHINE_RUN, MACHINE_PERIOD);
    }

    /** Running gear: a wheel or a track turning under the machine. */
    public static void gear(BlockEntity source) {
        pulse(source, RUNNING_GEAR, GEAR_PERIOD);
    }

    /**
     * A guided munition, wherever it is in its flight: the launch, the cruise,
     * and - once it is in the water - the swim itself.
     */
    public static void projectile(Entity source) {
        if (source == null) {
            return;
        }
        Level level = source.level();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (source.tickCount <= 0) {
            gunfire(level, source.position());
            return;
        }
        if (source.isInWater()) {
            torpedo(source);
        } else {
            missile(source);
        }
    }

    /** A munition on its way through the air. */
    public static void missile(Entity source) {
        munition(source, MISSILE_FLIGHT, FLIGHT_PERIOD);
    }

    /** A munition in the water: only a body that is actually in it makes noise. */
    public static void torpedo(Entity source) {
        if (source == null || !source.isInWater()) {
            return;
        }
        munition(source, TORPEDO_RUN, WATER_PERIOD);
    }

    /**
     * Water being pushed aside by something that is not a munition: a hull
     * under way, or a hull that just ran into something.
     */
    public static void splash(Level level, Vec3 position, Object source) {
        if (level == null || position == null || level.isClientSide()) {
            return;
        }
        pulseAt(level, position, source, WATER_IMPACT, WATER_PERIOD);
    }

    /** A shot leaving a barrel. */
    public static void gunfire(Level level, Vec3 position) {
        if (level == null || position == null || level.isClientSide()) {
            return;
        }
        send(level, position, GUN_FIRE, GameEvent.Context.of(Blocks.AIR.defaultBlockState()), null);
    }

    /**
     * A shot that is only ever filed once, however often it is reported.
     *
     * <p>A round can be announced twice - by whoever launched it, and again by
     * the round itself on its first tick. The key is the identity of the round,
     * so both reports come out as the one bang the water heard.</p>
     */
    public static void gunfireOnce(Level level, Vec3 position, Object key) {
        if (level == null || position == null || level.isClientSide()) {
            return;
        }
        pulseAt(level, position, key == null ? null : "gunfire:" + key, GUN_FIRE, Integer.MAX_VALUE);
    }

    /**
     * A decoy singing in the water.
     *
     * <p>It is filed where it lies rather than on the hull it was launched
     * from: a shot is written onto the ship that fired it, and a lure that
     * drifted onto its own ship's mark would be no lure at all. What it makes
     * is the loudest noise the water carries - a gun going off - which is
     * exactly what it is imitating, so a homing torpedo turns onto it and the
     * sculk network answers it as it would answer a shot.</p>
     */
    public static void decoy(Level level, Vec3 position, Object source) {
        if (level == null || position == null || level.isClientSide()) {
            return;
        }
        pulseAt(level, position, source, GUN_FIRE, DECOY_PERIOD, false);
    }

    /** One pulse from a moving entity, keyed by its identity. */
    private static void munition(Entity source, Holder<GameEvent> event, int period) {
        if (source == null || event == null) {
            return;
        }
        Level level = source.level();
        if (level == null || level.isClientSide()) {
            return;
        }
        pulseAt(level, source.position(), source.getUUID(), event, period);
    }

    /**
     * Sends one pulse from a source that keeps moving, thinned out so a missile
     * in flight or a ship under way does not drown the sensor with twenty
     * vibrations a second.
     */
    private static void pulseAt(Level level, Vec3 position, Object source, Holder<GameEvent> event, int period) {
        pulseAt(level, position, source, event, period, true);
    }

    /**
     * One pulse, with the option of filing it on the hull it came from: a shot
     * belongs to the ship that fired it, a decoy belongs where it lies.
     */
    private static void pulseAt(Level level, Vec3 position, Object source, Holder<GameEvent> event, int period,
                                boolean anchorAtHull) {
        if (event == null) {
            return;
        }
        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
            return;
        }
        if (source != null) {
            Long last = LAST_PULSE.get(source);
            if (last != null && now - last < period) {
                return;
            }
            if (LAST_PULSE.size() >= MAX_TRACKED_SOURCES) {
                LAST_PULSE.clear();
            }
            LAST_PULSE.put(source, now);
        }
        send(level, position, event, GameEvent.Context.of(Blocks.AIR.defaultBlockState()), source, anchorAtHull);
    }

    /**
     * Sends one pulse from a block entity, thinned out so a machine that runs
     * for an hour does not drown the sensor with twenty vibrations a second.
     */
    private static void pulse(BlockEntity source, Holder<GameEvent> event, int period) {
        if (source == null || event == null) {
            return;
        }
        Level level = source.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        if (level.getGameTime() % period != 0L) {
            return;
        }
        BlockPos pos = source.getBlockPos();
        send(level, Vec3.atCenterOf(pos), event, GameEvent.Context.of(source.getBlockState()), null);
    }

    /**
     * Puts the vibration where the machine is seen, not where it is stored.
     *
     * <p>The owner is filed with the noise so a listener can tell its own
     * wake from the sea around it: a munition hears every other source but
     * never itself. Machines and shots carry none, because nothing filters
     * them by identity.</p>
     */
    private static void send(Level level, Vec3 position, Holder<GameEvent> event, GameEvent.Context context, Object owner) {
        send(level, position, event, context, owner, true);
    }

    private static void send(Level level, Vec3 position, Holder<GameEvent> event, GameEvent.Context context,
                             Object owner, boolean anchorAtHull) {
        try {
            Vec3 world = SubLevelPositions.worldPosition(level, position);
            if (SilencerShield.muffled(level, world)) {
                return;
            }
            level.gameEvent(event, world, context);
            SonarKind kind = sonarKind(event);
            if (kind != null) {
                SonarLog.record(level, kind == SonarKind.GUNFIRE && anchorAtHull ? gunfireAnchor(level, world) : world, kind, owner);
            }
            report(level, world, event);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Where a shot is filed: on the hull that fired it, not at the muzzle.
     *
     * <p>A gun is bolted to something - a ship, a bunker, a turret on a deck -
     * and that something is what the water hears. Filing the bang at the muzzle
     * leaves the sound floating in the open next to the ship instead of on her,
     * so the hull the shot came from would still read as a hull rather than as a
     * gun. The anchor is the middle of the nearest body whose box holds the
     * muzzle, which is the very spot the hull is marked at, so the two marks
     * merge and the loudest of them wins.</p>
     */
    private static Vec3 gunfireAnchor(Level level, Vec3 world) {
        if (level == null || world == null) {
            return world;
        }
        try {
            Set<UUID> bodies = new LinkedHashSet<>(PhysicsGroups.bodiesContaining(level, world, GUNFIRE_HULL_INFLATE));
            Vec3 anchor = null;
            double nearest = Double.MAX_VALUE;
            for (UUID id : bodies) {
                SubLevel sub = PhysicsGroups.resolve(level, id);
                if (sub == null) {
                    continue;
                }
                Vec3 centre = sub.boundingBox().toMojang().getCenter();
                double distance = centre.distanceToSqr(world);
                if (distance < nearest) {
                    nearest = distance;
                    anchor = centre;
                }
            }
            if (anchor != null) {
                return anchor;
            }
        } catch (Throwable ignored) {
        }
        return world;
    }

    /**
     * How loud a machine's noise is, for the sonar sets listening to the water:
     * a gun going off carries, a munition under way carries less, a hull
     * shifting water or an engine running less again.
     */
    private static SonarKind sonarKind(Holder<GameEvent> event) {
        if (event == null) {
            return null;
        }
        try {
            GameEvent value = event.value();
            if (value == GUN_FIRE.get()) {
                return SonarKind.GUNFIRE;
            }
            if (value == MISSILE_FLIGHT.get()) {
                return SonarKind.MISSILE;
            }
            if (value == TORPEDO_RUN.get()) {
                return SonarKind.TORPEDO;
            }
            if (value == WATER_IMPACT.get()) {
                return SonarKind.BODY;
            }
            if (value == MACHINE_RUN.get() || value == RUNNING_GEAR.get()) {
                return SonarKind.MACHINE;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Thinned-down breadcrumb, so a test run shows the pulses really leave. */
    private static void report(Level level, Vec3 position, Holder<GameEvent> event) {
        long now = System.nanoTime();
        if (lastReportNanos >= 0L && now - lastReportNanos < REPORT_PERIOD_NANOS) {
            return;
        }
        lastReportNanos = now;
        try {
            FireControlCompat.LOGGER.info("[firecontrolcompat] vibration {} at {} {} {} in {}",
                    event.unwrapKey().map(key -> key.location().toString()).orElse("?"),
                    (int) position.x, (int) position.y, (int) position.z, level.dimension().location());
        } catch (Throwable ignored) {
        }
    }
}
