package com.qkdream.firecontrolcompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps a loaded round visible on the rack it is loaded on.
 *
 * <p>A round loaded on a rack has to be drawn somewhere, and the rack block itself has no model of
 * a Sidewinder. The round is therefore spawned as an entity at the rack's own position and pinned
 * there every tick - the "spawn it at a relative position" arrangement - so the missile on the rail
 * is a real entity with the real model, while the block keeps deciding whether the rack is loaded
 * and which round it carries. Nothing of the entity is simulated: no gravity, no collision, no
 * flight, it is put back where the rack is every tick. Firing discards it and spawns the live round
 * in its place, so a rail never has two rounds on it.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class RackMountedRounds {

    /** Where the round hangs on the rail, in blocks from the rack block's centre. */
    private static final Vec3 RAIL_OFFSET = new Vec3(0.0D, 0.0D, 0.0D);

    /**
     * How far above the rack block's centre each model's own bottom sits.
     *
     * <p>Taken from the models themselves, not guessed: the aim9 mesh starts at +0.031 blocks above
     * its origin and the R-40 at +0.057, scaled by the factor it is drawn at. Placing the origin at
     * "block top minus that" puts the model's underside on the rack's top face, so the round rests
     * on the rail instead of floating over it or sinking into the block - and it stays right for a
     * model whose origin is elsewhere, because the number comes from the mesh.</p>
     */
    private static final double INFRARED_LIFT = 0.02D;
    // TEMP-DISABLED(semi-active): only the infrared round is mounted any more, so the second
    // offset can never be selected. Kept for the restore.
    private static final double SEMI_ACTIVE_LIFT = -0.03D;

    /**
     * The semi-active round hangs lower than the infrared one.
     *
     * <p>Their models are authored around different origins - the Sidewinder sits on its own midline
     * and the R-40 sits high - so one shared offset cannot put both on the rail: raising it to fit
     * one floats the other, and the other way round. Two offsets keep both sitting on the rail.</p>
     */


    private static final Map<Entity, BlockPos> MOUNTED = new WeakHashMap<>();

    /** Marks a round as "sitting on a rail": frozen, unguided, only kept in place. */
    public static final String MOUNTED_KEY = "rackMountedRound";

    /**
     * Whether an entity is one of the display rounds pinned to a rail.
     *
     * <p>The persistent flag is only known to the server, so the client answers from the two synced
     * values {@link #mount} writes as well: no gravity, which a rail round has, plus a visible-name
     * flag with no name behind it, which draws nothing and marks nothing else in this pack.</p>
     */
    public static boolean isDisplayRound(Entity round) {
        if (round == null) {
            return false;
        }
        try {
            if (round.getPersistentData().getBoolean(MOUNTED_KEY)) {
                return true;
            }
            return round.isNoGravity() && round.isCustomNameVisible();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private RackMountedRounds() {
    }

    /**
     * The GeckoLib marker interface, kept as a name so this class loads in a pack without GeckoLib.
     *
     * <p>Reading it as a {@link Class} constant would tie the whole mod to GeckoLib being present at
     * class-init time, and this test is only ever interesting when it is.</p>
     */
    private static final String GEO_ENTITY = "software.bernie.geckolib.animatable.GeoEntity";

    /**
     * Whether a round's model is a GeckoLib model, which an entity renderer has to draw.
     *
     * <p>This is the test that decides whether a loaded rail needs a display round standing on it.
     * 面包学 draws its anti-radiation round from the loaded rack block's own model
     * ({@code models/custom/antiradiation_missile_rack.json}), and that round's entity model is a
     * plain {@code EntityModel}, so its rail needs nothing. A GeckoLib round has no block model to
     * hang on the rack and has to be spawned as an entity instead. This mod's own two rail rounds are
     * neither - they are built for the rack and take the explicit {@link #mount} path.</p>
     */
    public static boolean isGeckoLibModel(Entity round) {
        if (round == null) {
            return false;
        }
        try {
            for (Class<?> type = round.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Class<?> implemented : type.getInterfaces()) {
                    if (implementsGeoEntity(implemented)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean implementsGeoEntity(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (GEO_ENTITY.equals(type.getName())) {
            return true;
        }
        for (Class<?> parent : type.getInterfaces()) {
            if (implementsGeoEntity(parent)) {
                return true;
            }
        }
        return false;
    }

    /** Puts a display round on the given rack, replacing whatever stood there before. */
    public static void mount(ServerLevel level, BlockPos pos, Entity round) {
        unmount(level, pos);
        round.setNoGravity(true);
        round.noPhysics = true;
        // The two synced values below are how the client recognises a display round: persistent
        // data never leaves the server. Showing a name that does not exist draws nothing, and no
        // gravity is what a rail round has anyway.
        round.setCustomNameVisible(true);
        round.setDeltaMovement(Vec3.ZERO);
        if (round instanceof com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity missile) {
            missile.firecontrolcompat$setMounted(true);
        }
        round.getPersistentData().putBoolean(MOUNTED_KEY, true);
        Vec3 at = railSpot(level, pos, round);
        round.setPos(at.x, at.y, at.z);
        faceTheRail(level, pos, round);
        // The interpolation baseline is moved with the round: without this the client lerps from
        // whatever old position/rotation it kept, which reads as the round drifting and turning on
        // the rail even though the server writes the same place and the same yaw every tick.
        round.setOldPosAndRot();
        round.setBoundingBox(net.minecraft.world.phys.AABB.ofSize(round.position(), 4.0D, 4.0D, 4.0D));
        boolean added = level.addFreshEntity(round);
        MOUNTED.put(round, pos.immutable());
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] rack round mounted added={} rack={} rail={} stored={} level={}",
                added, pos, at, Vec3.atCenterOf(pos), level.dimension().location());
    }

    /**
     * Where one particular round sits on a rail, in the space the player sees it in.
     *
     * <p>An aircraft's rack is a block of a Sable sub-level, and a sub-level's blocks are stored in
     * the plot region its structure owns - the coordinates are millions of blocks away from where
     * the aircraft is drawn (the rack in the last report stood at {@code 20481031, 983, 20507656}).
     * A round spawned at those stored coordinates is in a chunk no one ever sees: the rail looks
     * empty and the load reads as "it cannot be loaded", even though the flag and the entity are
     * both there. The stored position is therefore projected out of the sub-level, exactly like the
     * muzzle of a round that is about to be fired, and re-projected every tick so the round stays on
     * a moving aircraft.</p>
     */
    private static Vec3 railSpot(ServerLevel level, BlockPos pos, Entity round) {
        double lift = round instanceof com.qkdream.firecontrolcompat.entity.SemiActiveMissileEntity
                ? SEMI_ACTIVE_LIFT : INFRARED_LIFT;
        Vec3 stored = Vec3.atCenterOf(pos).add(RAIL_OFFSET).add(0.0D, lift, 0.0D);
        return SubLevelPositions.worldPosition(level, stored);
    }

    /**
     * Lays the round along the rail, nose out.
     *
     * <p>The block's facing is a direction in the sub-level's own space; it is projected with the
     * same helper as the rail's position, so a structure rotated away from the world axes does not
     * leave the round lying across its own rail.</p>
     */
    private static void faceTheRail(ServerLevel level, BlockPos pos, Entity round) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        net.minecraft.core.Direction facing =
                state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                        ? state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                        : net.minecraft.core.Direction.NORTH;
        Vec3 storedCenter = Vec3.atCenterOf(pos);
        Vec3 storedForward = storedCenter.add(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vec3 direction = SubLevelPositions.worldPosition(level, storedForward)
                .subtract(SubLevelPositions.worldPosition(level, storedCenter));
        if (direction.lengthSqr() < 1.0E-8D) {
            direction = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        }
        // Vanilla yaw convention: 0 faces +Z (south), 180 faces -Z (north), 90 faces -X (west),
        // so the yaw of a horizontal direction is the arc tangent of minus x over z.
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        round.setYRot((float) yaw);
        round.yRotO = (float) yaw;
        round.setXRot(0.0F);
        round.xRotO = 0.0F;
        // No turn rate left over: the round is a picture on the rail, not something turning.
        round.setDeltaMovement(Vec3.ZERO);
    }

    /** Removes the display round from the given rack, if one is standing on it. */
    public static void unmount(ServerLevel level, BlockPos pos) {
        for (Map.Entry<Entity, BlockPos> entry : new ArrayList<>(MOUNTED.entrySet())) {
            if (!entry.getValue().equals(pos)) {
                continue;
            }
            MOUNTED.remove(entry.getKey());
            entry.getKey().discard();
        }
    }

    /** Pins every display round to its rack, and drops the ones whose rack is gone. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (MOUNTED.isEmpty()) {
            return;
        }
        List<Entity> lost = new ArrayList<>();
        for (Map.Entry<Entity, BlockPos> entry : MOUNTED.entrySet()) {
            Entity round = entry.getKey();
            BlockPos pos = entry.getValue();
            if (round == null || round.isRemoved() || !(round.level() instanceof ServerLevel level)) {
                lost.add(round);
                continue;
            }
            if (!BeamMissileCompat.rackKeepsItsRound(level, pos)) {
                // The rack was emptied or replaced: the display round goes with it.
                lost.add(round);
                continue;
            }
            Vec3 at = railSpot(level, pos, round);
            round.setPos(at.x, at.y, at.z);
            round.setDeltaMovement(Vec3.ZERO);
            round.setNoGravity(true);
            // Pointed the way the rail points, every tick: the block's facing is the only source
            // of truth for which way the round lies.
            faceTheRail(level, pos, round);
            round.setOldPosAndRot();
            // A model drawn larger than authored sticks out of the box the game culls by, and its
            // front can be clipped away - a missing nose. The box is grown to cover the model.
            round.setBoundingBox(net.minecraft.world.phys.AABB.ofSize(round.position(), 4.0D, 4.0D, 4.0D));
        }
        for (Entity round : lost) {
            MOUNTED.remove(round);
            if (round != null && !round.isRemoved()) {
                round.discard();
            }
        }
    }
}
