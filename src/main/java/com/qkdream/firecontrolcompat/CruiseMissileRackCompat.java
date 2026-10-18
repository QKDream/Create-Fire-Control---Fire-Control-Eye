package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileRemoteSync;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.mcreator.myfirstmod.procedures.CruisemissilegoProcedure;
import net.mcreator.myfirstmod.procedures.CruisemissilelowgoProcedure;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 面包学's cruise missiles on the aircraft weapon rack, the standard round and
 * the low-altitude one alike, flown by the fire control computer.
 *
 * <p>The fire control keeps aircraft weapons as one of its own types, and there
 * is no type of its own for a cruise missile carried on a rail - so the rail is
 * filed under the air-to-ground type, which is the one whose flight it wants
 * anyway: fired only with a ground target under the computer's cursor, steered
 * by the computer, and bursting with the computer's own heavier warhead. The
 * rail itself is untouched - their own launch procedure throws the round, so the
 * round, its motor, its trail and its sixty seconds of flight are all the mod's
 * own.</p>
 *
 * <p>The one thing fire control would change is the speed: an aircraft missile
 * is flown up to a fixed aircraft speed, and a cruise missile is not an aircraft
 * missile. Every round this mod has launched off a rail is remembered here and
 * held back to the speed its own flight procedure runs at, so what the computer
 * takes over is where the round goes and not how fast it gets there. A round that
 * has hit, been shot down or run out of fuel simply drops off the list.</p>
 */
public final class CruiseMissileRackCompat {

    /** The rail 面包学 hangs its cruise missile on. */
    private static final ResourceLocation CRUISE_RACK =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "cruisemissilerack");
    /** The rail that throws the low-altitude round of the same family. */
    private static final ResourceLocation CRUISE_LOW_RACK =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "cruisemissilelowrack");
    /** The round that rail lets go. */
    private static final ResourceLocation CRUISE_MISSILE =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "cruisemissiletanshe");
    /** The round the low-altitude rail lets go. */
    private static final ResourceLocation CRUISE_LOW_MISSILE =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "cruisemissilelowtanshe");
    /** The aim point each round of ours is flying to, in world coordinates, by round id. */
    private static final Map<UUID, Vec3> AIM = new ConcurrentHashMap<>();

    /** How much of the way to the aim the velocity is turned each tick. */
    private static final double TURN = 0.08D;

    /** Whether the rail's aim is written before the launch (see the A/B note at the call). */
    private static final boolean WRITE_AIM = true;

    /** The speed the round's own flight holds, in blocks a tick. */
    private static final double CRUISE_SPEED = 5.0D;
    /** Where the rail's muzzle sits, used to pick out the round that just left. */
    private static final double MUZZLE_DROP = 2.0D;
    private static final double MUZZLE_SEARCH = 8.0D;
    /** The mark fire control leaves on a cruise missile it is flying. */
    private static final String CRUISE_GUIDED_KEY = "CreateFireControlCruiseGuided";
    /** Rounds of ours that are flying, so their speed can be held. */
    private static final Set<Entity> FLYING = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Ticks a rail keeps her just-thrown round in mind, in case she is thrown a beat late. */
    private static final long PICKUP_TICKS = 40L;

    /** How close to a rail's muzzle a round has to come to be called hers. */
    private static final double CLAIM_SEARCH = 24.0D;

    /** How far ahead of a round the chunks under her are held ticking, in blocks. */
    private static final double TICKING_AHEAD = 24.0D;

    /** How far ahead of a round the chunks she flies over are held loaded, in blocks. */
    private static final double LOADED_AHEAD = 160.0D;

    /** How far apart the samples along a round's path are, in blocks. */
    private static final double PROBE_STEP = 8.0D;

    /** Ticket levels: the chunks under her tick, the ones in front only load. */
    private static final int TICKING_DISTANCE = 2;
    private static final int LOADED_DISTANCE = 0;

    /**
     * The ticket this mod holds on the chunks of a round's path. Held under the
     * round's own name, so two rounds crossing the same chunk do not lift each
     * other's chunk when one of them goes off.
     */
    private static final TicketType<UUID> TICKET =
            TicketType.create("firecontrolcompat_cruise_round", UUID::compareTo);

    /** Rails that have just fired and whose round has not been caught yet. */
    private static final List<Pending> PENDING = new ArrayList<>();

    /** The chunks each round in the air is holding. */
    private static final Map<UUID, Path> PATHS = new HashMap<>();

    /** When the last line about the rounds in the air was said. */
    private static long lastReport;

    /** A rail that has just fired, and the tick her round stops being waited for. */
    private static final class Pending {

        private final Level level;
        private final BlockPos pos;
        private final long until;
        private boolean claimed;

        private Pending(Level level, BlockPos pos, long until) {
            this.level = level;
            this.pos = pos;
            this.until = until;
        }
    }

    /** The chunks one round holds, and the level they belong to. */
    private static final class Path {

        private UUID owner;
        private ServerLevel level;
        private final Set<ChunkPos> ticking = new HashSet<>();
        private final Set<ChunkPos> loaded = new HashSet<>();
    }

    /**
     * Written once at class load, so a build can be told apart in the log.
     *
     * <p>This file was restored to the arrangement the cruise lock was first written in:
     * the rail's own launch, the computer's own ground target, the round kept alive. Nothing
     * of the later aiming experiments remains.</p>
     */
    static {
        FireControlCompat.LOGGER.info("[firecontrolcompat] cruise rail support: lock-only build");
    }

    private CruiseMissileRackCompat() {
    }

    /**
     * Lifts a round that came out of its rail already touching the ground.
     *
     * <p>面包学's cruise missile discards itself on the first tick its "stuck" flag is set, so
     * a round that leaves the rail inside the ground or the aircraft is gone before it is seen -
     * which is exactly "it vanishes the instant it leaves the rack". The flag is cleared and the
     * round lifted clear, so it gets the flight its rail meant it to have.</p>
     */
    private static void liftOutOfTheGround(Entity missile) {
        if (!(missile instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow)) {
            return;
        }
        if (!((com.qkdream.firecontrolcompat.mixin.ArrowInGroundAccess) arrow)
                .firecontrolcompat$isInGround()) {
            return;
        }
        try {
            Vec3 at = arrow.position();
            arrow.setPos(at.x, at.y + 1.6D, at.z);
            Vec3 motion = arrow.getDeltaMovement();
            Vec3 forward = motion.lengthSqr() < 1.0E-4D ? Vec3.ZERO : motion.normalize();
            if (forward == Vec3.ZERO) {
                forward = new Vec3(arrow.getLookAngle().x, 0.0D, arrow.getLookAngle().z);
            }
            arrow.setDeltaMovement(forward.scale(CRUISE_SPEED));
            ((com.qkdream.firecontrolcompat.mixin.ArrowInGroundAccess) arrow)
                    .firecontrolcompat$setInGround(false);
            arrow.hurtMarked = true;
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] cruise lifted out of the ground round={} from={}", arrow.getUUID(), at);
        } catch (RuntimeException | LinkageError failure) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] cruise lift failed round={} error={}", arrow.getUUID(), failure.toString());
        }
    }

    /** Whether the block is one of 面包学's cruise missile rails. */
    public static boolean isCruiseRack(BlockState state) {
        if (state == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return CRUISE_RACK.equals(id) || CRUISE_LOW_RACK.equals(id);
    }

    /** Whether the block in that binding is 面包学's cruise missile rail. */
    public static boolean isCruiseRack(Level level, BindingRef ref) {
        BlockEntity blockEntity = ref == null || level == null ? null : ref.resolve(level);
        return blockEntity != null && isCruiseRack(blockEntity.getBlockState());
    }

    /** Whether a rail of any kind stands at that block id, ours included. */
    public static boolean isRackId(ResourceLocation id) {
        return id != null && MianbaoAircraftCompat.isRack(BuiltInRegistries.BLOCK.get(id).defaultBlockState());
    }

    /** Whether the entity is one of 面包学's cruise missiles. */
    public static boolean isCruiseMissile(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return CRUISE_MISSILE.equals(id) || CRUISE_LOW_MISSILE.equals(id);
    }

    /** Whether the entity is the low-altitude round of the cruise family. */
    public static boolean isLowCruiseMissile(Entity entity) {
        return entity != null && CRUISE_LOW_MISSILE.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    /**
     * Fires the rail the way the mod's own launch does, with the computer's
     * ground target written on it first, then remembers the round that came out
     * of it.
     *
     * <p>The rail's own launch copies the aim off the block it stood on, so the
     * round flies 面包学's own route - the boost, the climb onto its cruise
     * altitude and its own run-in - only at the target the computer picked, and
     * leaves the rail only when the block is told what that target is. A rail
     * whose computer holds no ground target is told so as well, and the round
     * then makes the plain run its mod gives it.</p>
     */
    public static boolean fire(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || pos == null) {
            return false;
        }
        BlockState state = server.getBlockState(pos);
        // Aimed exactly the way a bomb rack is: the glide bombs receive their point through
        // that writer and lock correctly from it, so this rail uses it too.
        // A/B switch: with this off the rail is launched exactly as 面包学 launches it, with no
        // aim written at all. If the round still vanishes the instant it leaves the rail, then
        // the aim was never the cause and the launch itself is; if it flies, the aim is what
        // turned it into the rail and destroyed it, and the aim will be written after the round
        // is clear of the aircraft instead.
        if (WRITE_AIM) {
            aimTheRail(server, pos);
        }
        if (isLowRack(state)) {
            CruisemissilelowgoProcedure.execute(server, pos.getX(), pos.getY(), pos.getZ());
        } else {
            CruisemissilegoProcedure.execute(server, pos.getX(), pos.getY(), pos.getZ());
        }
        Vec3 aim = WRITE_AIM ? BombRackCompat.groundPointOf(server, pos) : null;
        if (aim != null) {
            LAST_AIM.put(pos.hashCode(), aim);
            FireControlCompat.LOGGER.info("[firecontrolcompat] cruise aim filed rail={} point={}", pos, aim);
        }
        boolean caught = rememberLaunchedRound(server, pos);
        if (!caught) {
            synchronized (PENDING) {
                PENDING.add(new Pending(server, pos.immutable(), server.getGameTime() + PICKUP_TICKS));
            }
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] cruise rail fired rail={} round={}",
                pos, caught ? "caught" : "waiting");
        return true;
    }

    /**
     * Ran once a server tick: catches the rounds fresh rails have thrown, keeps
     * the ones in the air flying, holds the speed of the rounds in the air, and
     * says once a second what is flying.
     */
    public static void tick() {
        collectLaunchedRounds();
        flyRounds();
        reportFlight();
    }

    /** Looks again for the rounds of rails that have just fired. */
    private static void collectLaunchedRounds() {
        List<Pending> waiting;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            waiting = List.copyOf(PENDING);
        }
        for (Pending pending : waiting) {
            Level level = pending.level;
            boolean spent = level == null;
            boolean caught = false;
            if (!spent) {
                caught = rememberLaunchedRound(level, pending.pos);
                spent = caught || level.getGameTime() > pending.until;
            }
            if (spent) {
                synchronized (PENDING) {
                    PENDING.remove(pending);
                }
            }
        }
    }

    /** Says once a second what this mod has flying off a cruise rail. */
    private static void reportFlight() {
        long now = System.currentTimeMillis();
        if (now - lastReport < 1000L) {
            return;
        }
        lastReport = now;
        int rounds = 0;
        StringBuilder line = new StringBuilder();
        synchronized (FLYING) {
            for (Entity missile : FLYING) {
                if (missile == null || missile.isRemoved()) {
                    continue;
                }
                rounds++;
                if (rounds > 3) {
                    continue;
                }
                Vec3 world = MianbaoMissileCompat.getWorldPosition(missile);
                Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
                ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
                Vec3 at = world;
                BlockPos block = BlockPos.containing(world);
                long ticked = root == null ? -1L
                        : root.getGameTime() - missile.getPersistentData().getLong("CreateFireControlNativeTick");
                String chunk = root == null ? "no-level"
                        : !root.hasChunkAt(block) ? "unloaded"
                        : root.isPositionEntityTicking(block) ? "ticking"
                        : "loaded";
                if (rounds > 1) {
                    line.append(' ');
                }
                line.append('(').append((int) at.x).append(',').append((int) at.y).append(',').append((int) at.z)
                        .append(" world=").append((int) world.x).append(',').append((int) world.y)
                        .append(',').append((int) world.z)
                        .append(" age=").append(missile.tickCount)
                        .append(" speed=").append(Math.round(velocity.length() * 100.0D) / 100.0D)
                        .append(" ticked=").append(ticked)
                        .append(" chunk=").append(chunk)
                        .append(')');
            }
        }
        if (rounds > 0) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] cruise flight rounds={} {}", rounds, line);
        }
    }

    /** Whether the block is the rail that throws the low altitude round. */
    private static boolean isLowRack(BlockState state) {
        return state != null && CRUISE_LOW_RACK.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Writes the computer's ground target onto the rail before its own launch runs. */
    /**
     * Writes the computer's ground target onto the rail, which is where the rail's own launch
     * reads it. This is the arrangement that locked correctly before this mod's selection work,
     * and it is restored untouched: the point is the computer's own air-to-ground target, in
     * world space, written under the keys 面包学's launch reads.
     */
    private static void aimTheRail(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        Vec3 aim = aimedTarget(level, pos);
        CompoundTag data = blockEntity.getPersistentData();
        data.putBoolean("坐标模式", aim != null);
        if (aim != null) {
            data.putDouble("target_x", aim.x);
            data.putDouble("target_y", aim.y);
            data.putDouble("target_z", aim.z);
        }
        blockEntity.setChanged();
        FireControlCompat.LOGGER.info("[firecontrolcompat] cruise rail aimed rail={} point={}", pos, aim);
    }



    /** The computer of the last cruise launch, and when it happened. */
    private static final Map<Level, StabilizerControllerBlockEntity> LAST_LAUNCH = new ConcurrentHashMap<>();
    private static final Map<Level, Long> LAST_LAUNCH_TICK = new ConcurrentHashMap<>();

    /** How long a launch is remembered for a round that is picked up late. */
    private static final long LAUNCH_MEMORY_TICKS = 100L;











    /**
     * The ground target the computer holding this rail is locked on, in world
     * space, or null while it holds none.
     */
    private static Vec3 aimedTarget(ServerLevel level, BlockPos pos) {
        BlockEntity rail = level.getBlockEntity(pos);
        if (rail == null) {
            return null;
        }
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            if (controller == null || controller.getLevel() == null) {
                continue;
            }
            StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                    controller.getAirToGroundTargetSnapshot();
            if (snapshot == null || snapshot.center() == null) {
                continue;
            }
            for (BindingRef rack : controller.getAircraftMissileRacks()) {
                // The rail is answered either by holding the same block entity or,
                // when the computer sits in another piece of the structure, by
                // pointing at the same block on the same body.
                if (rack.resolve(controller.getLevel()) == rail || rack.matches(level, pos)) {
                    return snapshot.center();
                }
            }
        }
        return null;
    }

    /**
     * Whether the air-to-ground slot of a computer is the cruise rail rather
     * than a plain air-to-ground one: the selection reads 巡航导弹 then, and
     * the air-to-ground name while a normal rail is bound beside it.
     */
    public static boolean namesAirToGroundSlot(Level level, List<BindingRef> racks) {
        boolean cruise = false;
        for (BindingRef rack : racks) {
            if (isCruiseRack(level, rack)) {
                cruise = true;
            } else if (MianbaoAircraftCompat.weaponAt(level, rack)
                    == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                return false;
            }
        }
        return cruise;
    }

    private static boolean rememberLaunchedRound(Level level, BlockPos pos) {
        Vec3 muzzle = Vec3.atCenterOf(pos).add(0.0D, -MUZZLE_DROP, 0.0D);
        Entity nearest = null;
        double nearestDistance = MUZZLE_SEARCH * MUZZLE_SEARCH;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(muzzle, muzzle).inflate(MUZZLE_SEARCH))) {
            if (!isCruiseMissile(entity)) {
                continue;
            }
            double distance = entity.position().distanceToSqr(muzzle);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = entity;
            }
        }
        if (nearest == null) {
            return false;
        }
        return claim(nearest, "rail");
    }

    /**
     * Takes a round that has just come into the world as the one a rail threw.
     *
     * <p>A rail standing on a welded structure throws her round into the plot of
     * that structure rather than into the world the rail is in, and a rail whose
     * round is thrown a beat late throws it after the search around her muzzle
     * has already run - either way the rail's own look around the muzzle finds
     * nothing, and an unclaimed round is a round nothing keeps in the air. The
     * round is matched to the rail by the place she comes into the world at,
     * which is the muzzle, whichever level it turns out to be.</p>
     */
    public static void adopt(Entity entity) {
        if (entity == null || !isCruiseMissile(entity)) {
            return;
        }
        Vec3 at = entity.position();
        boolean matched = false;
        synchronized (PENDING) {
            for (Pending pending : PENDING) {
                if (pending.claimed) {
                    continue;
                }
                Vec3 muzzle = Vec3.atCenterOf(pending.pos).add(0.0D, -MUZZLE_DROP, 0.0D);
                if (at.distanceToSqr(muzzle) <= CLAIM_SEARCH * CLAIM_SEARCH) {
                    pending.claimed = true;
                    matched = true;
                    break;
                }
            }
        }
        if (matched) {
            claim(entity, "rail-spawn", newestAim());
        } else if (!entity.getPersistentData().getBoolean(CRUISE_GUIDED_KEY)) {
            claim(entity, "stray", newestAim());
        }
    }

    /** Flags the fire control sets on a round it guides itself. */
    private static final String GUIDED_TAG = "CreateFireControlGuided";
    private static final String CRUISE_GUIDED_TAG = "CreateFireControlCruiseGuided";
    private static final String CONTROLLER_TAG = "CreateFireControlGuidedController";
    private static final String SUBLEVEL_TAG = "CreateFireControlGuidedControllerSubLevel";
    private static final String SUBLEVEL_KNOWN_TAG = "CreateFireControlGuidedControllerSubLevelKnown";



    /** Takes a round as one of ours: marked, remembered, and kept in the air. */
    private static boolean claim(Entity missile, String how) {
        return claim(missile, how, null);
    }

    /** Remembers the aim a round was launched with, so its speed pass can steer to it. */
    private static final Map<Integer, Vec3> LAST_AIM = new ConcurrentHashMap<>();

    /** The aim of the most recent launch, for a round picked up a tick late. */
    private static Vec3 newestAim() {
        return LAST_AIM.isEmpty() ? null : LAST_AIM.values().iterator().next();
    }

    private static boolean claim(Entity missile, String how, Vec3 aim) {
        if (missile == null) {
            return false;
        }
        markTakenOver(missile);
        liftOutOfTheGround(missile);
        Vec3 bound = aim != null ? aim : newestAim();
        if (bound != null) {
            AIM.put(missile.getUUID(), bound);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] cruise aim bound round={} aim={}", missile.getUUID(), bound);
        }
        // guidance left to the pack's own cruise flight
        boolean fresh;
        synchronized (FLYING) {
            fresh = FLYING.add(missile);
        }
        if (fresh) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] cruise round caught via {} type={} pos={} guided={}",
                    how,
                    BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()),
                    missile.position(),
                    missile.getPersistentData().getBoolean(CRUISE_GUIDED_KEY));
        }
        return true;
    }

    /**
     * Marks the round as one the computer is flying, which is what keeps her in
     * sight for the whole of her path.
     *
     * <p>A round drops out of the frame a few dozen blocks out, once she is past
     * the range the game tracks an entity over, and is not heard from again
     * until she goes off. Fire control lifts that range for every round carrying
     * her own cruise mark, so the round, her motor and the trail behind her run
     * unbroken from the rail all the way to the target. It is the same mark fire
     * control puts on the cruise missiles it throws itself, so nothing else
     * about the round changes: she is still 面包学's own flight, at 面包学's own
     * speed.</p>
     */
    private static void markTakenOver(Entity missile) {
        try {
            missile.getPersistentData().putBoolean(CRUISE_GUIDED_KEY, true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Keeps every round of ours flying.
     *
     * <p>A round is ticked by the game only while the chunk she stands in is
     * being ticked, which is true near a player and nowhere else: a few hundred
     * blocks out she stops moving altogether and hangs in the air until someone
     * walks over. Fire control flies its own cruise missiles by holding the
     * chunks of their path and carrying them forward by hand whenever their own
     * tick was skipped; this is the same two things for ours, with the chunks
     * under her held ticking so her own flight - the boost, the climb, the
     * steering, the trail - keeps running rather than being dragged along blind.</p>
     */
    private static void flyRounds() {
        synchronized (FLYING) {
            if (FLYING.isEmpty()) {
                return;
            }
            Iterator<Entity> iterator = FLYING.iterator();
            while (iterator.hasNext()) {
                Entity missile = iterator.next();
                if (missile == null || missile.isRemoved() || !missile.isAlive()) {
                    release(missile);
                    iterator.remove();
                    continue;
                }
                keepLoaded(missile);
                MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
                if (isLowCruiseMissile(missile)) {
                    MianbaoMissileRemoteSync.broadcast(missile);
                }
                holdSpeed(missile);
            }
        }
    }

    /**
     * Holds every round of ours to the speed its own flight runs at. Fire
     * control has just written the velocity it wants; only the length of it is
     * corrected, so where the round is going stays the computer's business.
     */
    /**
     * Holds the round at its own cruise speed and turns it gently towards its aim.
     *
     * <p>The speed used to be capped only from above, so the drag of an unpowered fall pulled
     * a round down towards a standstill and nothing ever put it back - which is exactly "it
     * keeps slowing until it stops". The speed is now held from both sides: the length of the
     * velocity is set to the cruise figure every tick, and a round that has already been
     * dragged down to nothing is given its own speed again along the direction of its aim.</p>
     */
    /**
     * Holds every round of ours to the speed its own flight runs at. Only the length is
     * capped: where the round is going is the computer's business - and, while this mod's own
     * steering was running here, it was this mod's business instead, which fought the lock.
     */
    /**
     * Holds the round at its own cruise speed and turns it gently towards its aim.
     *
     * <p>Both parts matter: the speed is held from below as well as above, because an unpowered
     * round drags down to a standstill and nothing used to put it back - a crew watched it slow
     * to a halt in mid air - and the direction is turned a little each tick so the round reaches
     * the computer's target while keeping its own speed and its wide, cruise-like turns.</p>
     */
    private static void holdSpeed(Entity missile) {
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
        double speed = velocity.length();
        Vec3 aim = AIM.get(missile.getUUID());
        if (aim != null) {
            Vec3 to = aim.subtract(missile.position());
            if (to.lengthSqr() < 16.0D) {
                AIM.remove(missile.getUUID());
            } else {
                Vec3 desired = to.normalize().scale(CRUISE_SPEED);
                Vec3 base = speed < 0.05D ? desired : velocity;
                Vec3 turned = base.add(desired.subtract(base).scale(TURN));
                double length = turned.length();
                if (length > 1.0E-6D) {
                    MianbaoMissileCompat.setWorldVelocity(missile, turned.scale(CRUISE_SPEED / length));
                }
                return;
            }
        }
        if (speed > 1.0E-4D) {
            MianbaoMissileCompat.setWorldVelocity(missile, velocity.scale(CRUISE_SPEED / speed));
        }
    }

    /**
     * Holds the chunks of a round's path: the ones under her kept ticking, so
     * her own flight keeps running while she is far from anyone, and a stretch
     * in front kept loaded, so the ground she is flying over is there.
     */
    private static void keepLoaded(Entity missile) {
        try {
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
            if (root == null) {
                return;
            }
            UUID owner = missile.getUUID();
            Path path = PATHS.computeIfAbsent(owner, key -> new Path());
            if (path.level != root) {
                release(path);
                path.level = root;
            }
            path.owner = owner;
            Vec3 worldPosition = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 worldVelocity = MianbaoMissileCompat.getWorldVelocity(missile);
            Set<ChunkPos> ticking = new HashSet<>();
            ahead(ticking, worldPosition, worldVelocity, TICKING_AHEAD);
            Set<ChunkPos> loaded = new HashSet<>();
            ahead(loaded, worldPosition, worldVelocity, LOADED_AHEAD);
            hold(root, path.ticking, ticking, TICKING_DISTANCE, owner);
            hold(root, path.loaded, loaded, LOADED_DISTANCE, owner);
        } catch (Throwable ignored) {
        }
    }

    /** Writes the chunks a round covers over the next stretch into the set. */
    private static void ahead(Set<ChunkPos> destination, Vec3 from, Vec3 velocity, double reach) {
        destination.add(chunkOf(from.x, from.z));
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (!(speed > 1.0E-4D) || !(reach > 0.0D)) {
            return;
        }
        double stepX = velocity.x / speed * PROBE_STEP;
        double stepZ = velocity.z / speed * PROBE_STEP;
        double x = from.x;
        double z = from.z;
        for (double travelled = 0.0D; travelled < reach; travelled += PROBE_STEP) {
            x += stepX;
            z += stepZ;
            destination.add(chunkOf(x, z));
        }
    }

    private static ChunkPos chunkOf(double x, double z) {
        return new ChunkPos(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    /** Swaps the chunks one set of tickets holds for another. */
    private static void hold(ServerLevel root, Set<ChunkPos> current, Set<ChunkPos> wanted,
            int distance, UUID owner) {
        for (ChunkPos chunk : current) {
            if (!wanted.contains(chunk)) {
                root.getChunkSource().removeRegionTicket(TICKET, chunk, distance, owner, true);
            }
        }
        for (ChunkPos chunk : wanted) {
            if (!current.contains(chunk)) {
                root.getChunkSource().addRegionTicket(TICKET, chunk, distance, owner, true);
            }
        }
        current.clear();
        current.addAll(wanted);
    }

    /** Lets go of every chunk a round was holding. */
    private static void release(Entity missile) {
        if (missile == null) {
            return;
        }
        release(PATHS.remove(missile.getUUID()));
    }

    private static void release(Path path) {
        if (path == null || path.level == null || path.owner == null) {
            return;
        }
        try {
            for (ChunkPos chunk : path.ticking) {
                path.level.getChunkSource().removeRegionTicket(TICKET, chunk, TICKING_DISTANCE, path.owner, true);
            }
            for (ChunkPos chunk : path.loaded) {
                path.level.getChunkSource().removeRegionTicket(TICKET, chunk, LOADED_DISTANCE, path.owner, true);
            }
        } catch (Throwable ignored) {
        }
        path.ticking.clear();
        path.loaded.clear();
        path.level = null;
    }

    /** Forgets a round by hand, for a shot that came to nothing. */
    public static void forget(Entity missile) {
        synchronized (FLYING) {
            FLYING.remove(missile);
        }
        release(missile);
    }
}

