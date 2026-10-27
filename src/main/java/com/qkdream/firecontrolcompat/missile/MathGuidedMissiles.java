package com.qkdream.firecontrolcompat.missile;

import com.hooya.stabilizedturret.content.controller.GuidedMissileMode;
import com.hooya.stabilizedturret.content.controller.MathematicalMissileManager;
import com.qkdream.firecontrolcompat.BombRackCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.StartupProbe;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Flies this mod's three guided rounds on fire control's mathematical flight path.
 *
 * <p>The user's ask was that the infrared round, the heavy anti-air round and the super-heavy one
 * get "the same mathematical flight as fire control's own rounds, with only the render local".
 * That mode already exists in the pack: {@code MathematicalMissileManager.capture} takes an entity
 * on its way into the level, records its position and velocity, cancels the join, and from then on
 * the round is a record - it is stepped by {@code MathematicalMissileManager.advance}, drawn on the
 * client from {@code MathematicalMissilePayload}, and collided swept-style inside {@code advance}.</p>
 *
 * <h2>What was missing for our rounds</h2>
 *
 * <p>Three things, and this class plus one mixin supplies all three:</p>
 *
 * <ol>
 *   <li><b>Eligibility.</b> {@code GuidedMissileMode.eligible} only recognises the pack's own
 *       families, so {@link com.qkdream.firecontrolcompat.mixin.GuidedMissileModeEligibleMixin}
 *       widens it; {@link #isMathEligible} is the single test both use.</li>
 *   <li><b>Adoption.</b> {@code capture} also wants one of the pack's "this round was handed to a
 *       fire control computer" tags. {@link #onJoin} writes it at the highest priority, i.e. before
 *       {@code capture} (which runs at the lowest) looks at the round.</li>
 *   <li><b>A driver.</b> A round that never joins the level is never ticked by the level, so its own
 *       {@code tick()} cannot run - and that is where this mod's guidance lives. {@link #onTick}
 *       calls {@link BeamRidingMissileEntity#firecontrolcompat$mathTick} for each of our rounds and
 *       then lets the pack advance it, which is the same order the pack's own guidance uses for its
 *       rounds.</li>
 * </ol>
 *
 * <h2>Why the pack's motor is neutralised</h2>
 *
 * <p>{@code advance} also calls {@code advanceNativeEngine}, which looks the round up in
 * {@code MianbaoMissileFlightProfile.of(registryPath)} and multiplies its speed by that family's
 * motor curve. Our three rounds have no entry there and would inherit the generic aircraft curve -
 * a 5.5x boost over ten ticks on top of this mod's own acceleration ramp, which is five times the
 * top speed the round is balanced for. The engine counter {@code fadongji1} is therefore parked
 * past that curve's window when the round is adopted, so the multiplier is exactly 1.0 and the
 * speed the mod's own ramp asks for is the speed the round flies.</p>
 *
 * <h2>This class must be on the event bus (2026-10-27, the airframe bug)</h2>
 *
 * <p>It has two {@code @SubscribeEvent} methods - {@link #onJoin} and {@link #onTick} - and the
 * {@code @EventBusSubscriber} annotation above the class had been lost, so <b>neither ever ran</b>.
 * The consequence was invisible for a whole session and looked like a rendering fault: a
 * mathematically flown round never joins the level, fire control's own {@code broadcast}
 * returns
 * immediately for a round its manager holds, and the only remaining sender of the BVR payload is
 * {@link #firecontrolcompat$drive} - which is {@link #onTick}. With no payload there is no client
 * proxy, so no renderer is ever called, so a launched round simply does not exist on the screen
 * while every other log line reads normal. The same hook is also what flies our rounds' guidance and
 * what writes the adoption tag on join, so the rounds were additionally flying unguided.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class MathGuidedMissiles {

    /** Startup cost probe: opens the static initialisation window (see the closing block). */
    static {
        StartupProbe.staticBegin("MathGuidedMissiles");
    }

    private static final String PREFIX = "[firecontrolcompat] ";

    /** Registry namespace the three rounds live in. */
    private static final String NAMESPACE = "firecontrolcompat";

    /** The rounds of ours the user named, by registry path. */
    private static final Set<String> PATHS = Set.of(
            "infrared_missile", "heavy_aa_tanshe", "neva_tanshe", "ir_atgm_tanshe");

    /** 面包学's namespace, for the bombs this mod releases off its own racks. */
    private static final String BOMB_NAMESPACE = "mianbaos_modernwarfare";

    /**
     * The bombs this mod releases that are flown mathematically.
     *
     * <p>The user's second request was that the glide bombs <b>this mod releases</b> get the same
     * treatment as its own rounds. A glide bomb is 面包学's entity, not one of this mod's - it has no
     * {@code tick()} of this mod to reuse - so it is adopted only when this mod's own rack release
     * threw it (see {@link com.qkdream.firecontrolcompat.BombRackCompat#claimMathRelease}). A bomb
     * the pack released on its own path is left entirely alone: the pack already has a mathematical
     * bomb drive of its own, and two drivers would step one bomb twice.</p>
     */
    private static final Set<String> BOMB_PATHS = Set.of("glidebombtanshe");

    /** Pack tag that makes {@code MathematicalMissileManager.capture} adopt the round. */
    private static final String ADOPTED_TAG = "CreateFireControlAaGuided";

    /** This mod's own marker: the round is being flown mathematically. */
    public static final String MATH_TAG = "firecontrolcompat:math_guided";

    /** Marks a bomb whose aim point has already been moved into the world. */
    private static final String BOMB_TARGET_WORLD_TAG = "firecontrolcompat:math_bomb_world";

    /** Engine counter the pack's motor curve reads; parked past its window (see the class doc). */
    private static final String PACK_ENGINE_TAG = "fadongji1";

    /** Value that puts {@code fadongji1} outside every motor curve in the pack's table. */
    private static final double PACK_ENGINE_PARKED = 900.0D;

    private MathGuidedMissiles() {
    }

    /**
     * Whether this round is one the mod flies mathematically.
     *
     * <p>Answered from the registry key, not from the Java class, so a round re-registered under a
     * different class - or an add-on that registers one of these names - is treated the same way.
     * A sub-level round answers the same, because the type is the type wherever it was spawned.</p>
     */
    public static boolean isMathEligible(Entity entity) {
        if (!isMathCandidate(entity)) {
            return false;
        }
        if (isMathBomb(entity)) {
            // A bomb of 面包学's is eligible only once this mod has adopted it: the pack has a
            // mathematical bomb drive of its own, and widening eligibility for bombs in general would
            // hand it bombs the pack itself released - the same bomb would then be claimed by both
            // drives. The adoption mark is written by this class before fire control's capture reads
            // the answer, so our own bombs still qualify.
            return isMathGuided(entity);
        }
        return entity instanceof BeamRidingMissileEntity;
    }

    /** Whether the entity's type is one this class has any business flying. */
    public static boolean isMathCandidate(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation key;
        try {
            EntityType<?> type = entity.getType();
            key = type == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(type);
        } catch (Throwable ignored) {
            return false;
        }
        if (key == null) {
            return false;
        }
        if (NAMESPACE.equals(key.getNamespace())) {
            return entity instanceof BeamRidingMissileEntity && PATHS.contains(key.getPath());
        }
        if (BOMB_NAMESPACE.equals(key.getNamespace())) {
            return BOMB_PATHS.contains(key.getPath());
        }
        return false;
    }

    /** Whether this round is one of 面包学's bombs that this mod flies mathematically. */
    public static boolean isMathBomb(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return key != null && BOMB_NAMESPACE.equals(key.getNamespace()) && BOMB_PATHS.contains(key.getPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether a round is already flying mathematically, by this mod's own mark. */
    public static boolean isMathGuided(Entity entity) {
        try {
            return entity != null && entity.getPersistentData().getBoolean(MATH_TAG);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether a launch succeeded, given what the level said.
     *
     * <p>{@code addFreshEntity} answers <b>false</b> for a round the pack captured: the join event
     * was cancelled, so the round really did not enter the level - that is the whole mechanism, not
     * a failure. Every launcher in this mod checks that answer before spending ammo, clearing its
     * tube or reporting a miss, so a captured round has to read as launched there or the round flies
     * while the launcher still believes it never left. Anything the manager does not hold keeps the
     * level's own answer.</p>
     */
    public static boolean spawned(Entity missile, boolean addedByLevel) {
        if (addedByLevel) {
            return true;
        }
        try {
            return missile != null && MathematicalMissileManager.contains(missile);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Marks our rounds adopted before the pack decides whether to capture them.
     *
     * <p>{@code HIGHEST} is the whole point of this hook: {@code capture} runs at {@code LOWEST}, so
     * this is the last word before the join is cancelled. Anything that is not one of the three
     * rounds is left completely alone.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        try {
            if (event == null || event.getLevel() == null || event.getLevel().isClientSide()) {
                return;
            }
            Entity entity = event.getEntity();
            if (!isMathCandidate(entity)) {
                return;
            }
            if (isMathBomb(entity)) {
                // A bomb of 面包学's flies mathematically only when this mod's own rack threw it, and
                // its aim point has to be handed over here - the pack writes it after the spawn, which
                // a captured bomb never reaches. The route is <b>not</b> asked for first, because fire
                // control's own answer for a bomb is "not eligible" until the mark below exists; the
                // answer is read by its capture step, one priority later, after this hook has marked
                // it.
                Vec3 target = BombRackCompat.claimMathRelease(event.getLevel(), entity);
                if (target == null) {
                    return;
                }
                entity.getPersistentData().putBoolean(ADOPTED_TAG, true);
                entity.getPersistentData().putBoolean(MATH_TAG, true);
                entity.getPersistentData().putDouble(PACK_ENGINE_TAG, PACK_ENGINE_PARKED);
                reportJoined(entity, event.getLevel());
                return;
            }
            // The route is asked for before anything is written onto the round: in "entity" mode the
            // pack does not capture it, so the round flies itself and must be left exactly as it was
            // - the parked engine counter below would otherwise look like an expired flight to a round
            // whose own tick reads it.
            boolean mathematical;
            try {
                mathematical = GuidedMissileMode.mathematical(entity);
            } catch (Throwable ignored) {
                return;
            }
            if (!mathematical) {
                return;
            }
            firecontrolcompat$worldSpaceTarget(entity, event.getLevel());
            entity.getPersistentData().putBoolean(ADOPTED_TAG, true);
            entity.getPersistentData().putBoolean(MATH_TAG, true);
            // See the class doc: this is what keeps the pack's generic motor curve off the round.
            entity.getPersistentData().putDouble(PACK_ENGINE_TAG, PACK_ENGINE_PARKED);
            reportJoined(entity, event.getLevel());
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}math-guided: join hook skipped: {}", PREFIX,
                    failure.toString());
        }
    }

    /**
     * Moves a round's own locked point into the world, where the mathematical flight lives.
     *
     * <p>A top-attack round released off a launcher standing on a structure is spawned - and armed -
     * in that structure's <b>stored</b> coordinates, twenty million blocks from where the structure
     * is seen. {@code MathematicalMissileManager.capture} moves the round itself out to the world;
     * the point it was told to fly at has to make the same move, or the climb and the dive are aimed
     * at nothing. Done before the join event returns, which is before {@code capture} (at the lowest
     * priority) reads the position.</p>
     */
    private static void firecontrolcompat$worldSpaceTarget(Entity entity, net.minecraft.world.level.Level level) {
        try {
            Vec3 target = com.qkdream.firecontrolcompat.IrAtgm.targetOf(entity);
            if (target == null || !SubLevelPositions.inPlotSpace(level, target)) {
                return;
            }
            Vec3 world = SubLevelPositions.worldPosition(level, target);
            if (world == null || world.distanceToSqr(target) < 1.0E-6D) {
                return;
            }
            com.qkdream.firecontrolcompat.IrAtgm.retarget(entity, world);
            FireControlCompat.LOGGER.info(
                    "{}math-guided: target space=world stored={} world={} round={}",
                    PREFIX, target, world, nameOf(entity));
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}math-guided: target space skipped: {}", PREFIX,
                    failure.toString());
        }
    }

    /** Rounds already reported as mathematically flown, so the line appears once per round. */
    private static final Set<String> REPORTED = new LinkedHashSet<>();

    private static void reportJoined(Entity entity, net.minecraft.world.level.Level level) {
        String id = entity.getUUID().toString();
        if (!REPORTED.add(id)) {
            return;
        }
        if (REPORTED.size() > 256) {
            REPORTED.clear();
            REPORTED.add(id);
        }
        FireControlCompat.LOGGER.info(
                "{}math-guided: type={} eligible=true root={} pos={} speed={}",
                PREFIX, nameOf(entity), level.dimension().location(),
                String.format(Locale.ROOT, "%.1f,%.1f,%.1f", entity.getX(), entity.getY(), entity.getZ()),
                String.format(Locale.ROOT, "%.3f", entity.getDeltaMovement().length()));
    }

    private static String nameOf(Entity entity) {
        try {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return key == null ? entity.getType().toString() : key.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** Rounds already reported as driven, so the line appears once per round. */
    private static final Set<String> DRIVEN = new LinkedHashSet<>();

    /**
     * Once per round: says that this loop is flying it again, and which side adopted it.
     *
     * <p>Both answers matter. {@code adopted=ours} means this mod's join hook wrote the tag,
     * {@code adopted=pack} means 火控学's own launcher chain did - the case that used to fall out of
     * this loop entirely. The line is the difference between "the drive is running and covering this
     * round" and "the drive is not running at all", which is what cost the heavy airframes a whole
     * session of being invisible.</p>
     */
    private static void firecontrolcompat$reportDriven(Entity entity) {
        try {
            String id = entity.getUUID().toString();
            if (!DRIVEN.add(id)) {
                return;
            }
            if (DRIVEN.size() > 256) {
                DRIVEN.clear();
                DRIVEN.add(id);
            }
            FireControlCompat.LOGGER.info(
                    "{}math-guided: driving type={} adopted={} trail=per-tick bvr=per-tick pos={}",
                    PREFIX, nameOf(entity), isMathGuided(entity) ? "ours" : "pack",
                    String.format(Locale.ROOT, "%.1f,%.1f,%.1f", entity.getX(), entity.getY(), entity.getZ()));
        } catch (Throwable ignored) {
            // a diagnostic line never costs a tick
        }
    }

    /**
     * Steps every one of our rounds that is being flown mathematically.
     *
     * <p>Runs before {@code MathematicalMissileManager.sync} (which is {@code LOWEST}), so the state
     * the pack broadcasts to the clients is this tick's - the same relationship the pack's own
     * rounds have with their guidance. The guidance is called first and the advance second, because
     * advancing is what moves the round: the heading the guidance sets has to be the heading the
     * move uses.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onTick(ServerTickEvent.Post event) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }
            Set<ServerLevel> roots = new LinkedHashSet<>();
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                ServerLevel root = MathIntercept.rootOf(level);
                if (root != null) {
                    roots.add(root);
                }
            }
            for (ServerLevel root : roots) {
                firecontrolcompat$drive(root);
            }
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}math-guided: tick hook skipped: {}", PREFIX,
                    failure.toString());
        }
    }

    /**
     * Guidance then advance, for each of our rounds the pack is flying in this root level.
     *
     * <p>The gate is "this round is ours" ({@link #isMathCandidate}), <b>not</b> "this mod adopted
     * it" ({@link #isMathGuided}). A round 火控学's own launcher chain adopted - the placed Neva
     * block writes {@code CreateFireControlAaGuided} itself - is flown by the same manager, never
     * joins the level, and needs exactly the same trail and BVR payload. Gating on our own tag
     * silently dropped those rounds from this loop, which is why a super-heavy shot had no model on
     * the client at all: no payload, no proxy, nothing for any renderer to draw.</p>
     */
    private static void firecontrolcompat$drive(ServerLevel root) {
        List<Entity> flying;
        try {
            flying = MathematicalMissileManager.missiles(root);
        } catch (Throwable ignored) {
            return;
        }
        if (flying == null || flying.isEmpty()) {
            return;
        }
        int ours = 0;
        for (Entity entity : flying) {
            if (!isMathCandidate(entity)) {
                continue;
            }
            firecontrolcompat$reportDriven(entity);
            if (isMathBomb(entity)) {
                ours++;
                try {
                    if (!entity.isRemoved()) {
                        firecontrolcompat$bombTick(root, entity);
                    }
                    if (!entity.isRemoved()) {
                        MathematicalMissileManager.advance(entity);
                    }
                } catch (Throwable failure) {
                    FireControlCompat.LOGGER.info("{}math-guided: bomb {} skipped: {}", PREFIX,
                            entity.getUUID(), failure.toString());
                    entity.discard();
                }
                continue;
            }
            if (!(entity instanceof BeamRidingMissileEntity round)) {
                continue;
            }
            ours++;
            try {
                if (!entity.isRemoved() && entity.isAlive()) {
                    round.firecontrolcompat$mathTick(root);
                }
                // The safety fuse counts distance travelled, and a captured round's own tick never
                // runs - so the step is taken here as well as in mathTick. Both calls are guarded by
                // the round's tick number, so one flight step is counted once however this loop is
                // re-entered.
                round.firecontrolcompat$accumulateFlight();
                if (!entity.isRemoved()) {
                    MathematicalMissileManager.advance(entity);
                }
                // The motor plume, on the path that owns the round: in mathematical flight the
                // entity never joins the level, so its own tick - and the trail that tick would
                // build - never runs. Four particles a tick, laid behind the round along its
                // current velocity (see MissileTrailParticles).
                if (!entity.isRemoved()) {
                    try {
                        MissileTrailParticles.emit(root, round);
                    } catch (Throwable failure) {
                        FireControlCompat.LOGGER.info("{}math-guided: trail {} skipped: {}", PREFIX,
                                entity.getUUID(), failure.toString());
                    }
                }
                // The beyond-visual-range channel is fed here, not from tick(): a round in
                // mathematical mode never joins the level, so its own tick never runs and the
                // payload that makes fire control draw it at range would otherwise never be sent.
                // After advance(), so the position broadcast is the one this tick actually moved to
                // - the same order the pack uses for its own rounds.
                if (!entity.isRemoved()) {
                    round.firecontrolcompat$broadcastRemoteState();
                }
            } catch (Throwable failure) {
                // One round that throws must not cost the others their tick, and must not be left
                // sitting in the pack's table forever.
                FireControlCompat.LOGGER.info("{}math-guided: round {} skipped: {}", PREFIX,
                        entity.getUUID(), failure.toString());
                entity.discard();
            }
        }
        firecontrolcompat$reportWindow(root, ours);
    }

    /** {@code net.mcreator.myfirstmod.procedures.Glide_bomb_trailProcedure#execute}, found once. */
    private static java.lang.reflect.Method GLIDE_TRAIL;

    private static boolean glideTrailLooked;

    private static java.lang.reflect.Method firecontrolcompat$glideTrail() {
        if (!glideTrailLooked) {
            glideTrailLooked = true;
            try {
                Class<?> type = Class.forName("net.mcreator.myfirstmod.procedures.Glide_bomb_trailProcedure");
                for (java.lang.reflect.Method candidate : type.getMethods()) {
                    if ("execute".equals(candidate.getName()) && candidate.getParameterCount() == 5
                            && candidate.getParameterTypes()[4] == Entity.class) {
                        GLIDE_TRAIL = candidate;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                GLIDE_TRAIL = null;
            }
        }
        return GLIDE_TRAIL;
    }

    /**
     * One tick of a glide bomb's own flight, in mathematical space.
     *
     * <p>A captured bomb never joins the level, so {@code GlidebombtansheEntity#tick} never runs -
     * and that tick is where the glide lives: {@code Glide_bomb_trailProcedure} is what eases the
     * bomb's velocity onto its target and what lets go of gravity once the drop is over. Calling
     * that procedure is therefore the glide itself, not an imitation of it: the same numbers, in
     * the same order, on a record that {@code MathematicalMissileManager.advance} then moves and
     * collides swept-style.</p>
     *
     * <p>Reflective on purpose: the procedure is 面包学's, and a pack without it must cost this mod
     * the bomb's glide and nothing else.</p>
     */
    private static void firecontrolcompat$bombTick(ServerLevel root, Entity bomb) {
        java.lang.reflect.Method method = firecontrolcompat$glideTrail();
        if (method == null) {
            return;
        }
        firecontrolcompat$bombTargetToWorld(root, bomb);
        try {
            method.invoke(null, root, bomb.getX(), bomb.getY(), bomb.getZ(), bomb);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}math-guided: bomb glide failed: {}", PREFIX,
                    failure.toString());
        }
    }

    /** Once per bomb: the aim point it carries, expressed where the bomb is actually flying. */
    private static void firecontrolcompat$bombTargetToWorld(ServerLevel root, Entity bomb) {
        try {
            CompoundTag data = bomb.getPersistentData();
            if (data.getBoolean(BOMB_TARGET_WORLD_TAG)) {
                return;
            }
            data.putBoolean(BOMB_TARGET_WORLD_TAG, true);
            if (!data.getBoolean("坐标模式")) {
                return;
            }
            Vec3 stored = new Vec3(data.getDouble("target_x"), data.getDouble("target_y"),
                    data.getDouble("target_z"));
            if (!SubLevelPositions.inPlotSpace(root, stored)) {
                return;
            }
            Vec3 world = SubLevelPositions.worldPosition(root, stored);
            if (world == null || world.distanceToSqr(stored) < 1.0E-6D) {
                return;
            }
            data.putDouble("target_x", world.x);
            data.putDouble("target_y", world.y);
            data.putDouble("target_z", world.z);
            FireControlCompat.LOGGER.info("{}math-guided: bomb target space=world stored={} world={}",
                    PREFIX, stored, world);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}math-guided: bomb target space skipped: {}", PREFIX,
                    failure.toString());
        }
    }

    /** Quietest gap between two "one of ours is being flown" summaries, in ticks. */
    private static final long WINDOW_TICKS = 100L;

    private static long windowAt;
    private static int windowPeak;

    /**
     * One line every five seconds while any of our rounds is in mathematical flight.
     *
     * <p>Without it "the round is not being flown mathematically" and "the round is being flown but
     * nothing logs it" look identical, which is exactly the ambiguity that cost the earlier radar
     * fix a whole session. The seed is {@code 0} and is compared directly, never by subtraction from
     * {@code Long.MIN_VALUE}.</p>
     */
    private static void firecontrolcompat$reportWindow(ServerLevel root, int ours) {
        if (ours <= 0) {
            return;
        }
        long now;
        try {
            now = root.getGameTime();
        } catch (Throwable ignored) {
            return;
        }
        windowPeak = Math.max(windowPeak, ours);
        if (windowAt != 0L && now - windowAt < WINDOW_TICKS) {
            return;
        }
        windowAt = now;
        FireControlCompat.LOGGER.info("{}math-guided: flying={} root={} tick={}", PREFIX,
                windowPeak, root.dimension().location(), now);
        windowPeak = 0;
    }

    /** Startup cost probe: closes the static initialisation window opened at the top. */
    static {
        StartupProbe.staticEnd("MathGuidedMissiles");
    }
}
