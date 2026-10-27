package com.qkdream.firecontrolcompat.compat.maid;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.ControllerSeatOrder;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Finds the operator seat a fire control operator maid is posted to, walks her
 * to it and sits her down on it. Everything is read through the fire control
 * mod's own seat binding, so only a seat that was actually bound to a fire
 * control computer is ever used.
 */
final class MaidFireControlSeats {

    /** How far from her post a maid still walks to the bound seat. */
    static final double SEARCH_RANGE = 48.0;

    /** Distance at which she climbs onto the seat instead of walking closer. */
    private static final double REACH_SQR = 6.25;

    /** A seat bound to a fire control computer. */
    record SeatSpot(Level seatLevel, BlockPos pos, Vec3 worldCenter, UUID subLevelId) {
    }

    private MaidFireControlSeats() {
    }

    /**
     * The fire control computer the maid already operates, or null while she is
     * not sitting on a bound seat.
     */
    static StabilizerControllerBlockEntity boundController(Level level, Entity rider) {
        Entity vehicle = rider.getVehicle();
        if (vehicle == null) {
            return null;
        }
        try {
            StabilizerControllerBlockEntity controller = ControllerTracker.findForVehicle(level, vehicle);
            return controller != null && controller.isSeat(vehicle) ? controller : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The post the maid should take: the frontmost bound seat nobody else is
     * sitting on, or null when none is in reach.
     *
     * <p>A computer can carry a chain of posts, so every seat of it is looked
     * at and the ones that a player or another maid already holds are passed
     * over - a relief post only belongs to her while the posts in front of it
     * are vacant.</p>
     */
    static SeatSpot nearestSeat(Level level, Entity rider) {
        Vec3 maidWorld = worldPosition(level, rider.position());
        if (maidWorld == null) {
            return null;
        }
        double rangeSqr = SEARCH_RANGE * SEARCH_RANGE;
        SeatSpot best = null;
        int bestIndex = Integer.MAX_VALUE;
        double bestDistanceSqr = Double.MAX_VALUE;
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            Level controllerLevel = controller.getLevel();
            if (controllerLevel == null || controllerLevel.getServer() != level.getServer()) {
                continue;
            }
            if (!controllerLevel.dimension().equals(level.dimension())) {
                continue;
            }
            List<BindingRef> seats = orderedSeats(controller);
            for (int index = 0; index < seats.size(); index++) {
                BindingRef seat = seats.get(index);
                if (seat == null || seatOccupiedByOther(level, seat, rider)) {
                    continue;
                }
                Vec3 center = seatWorldCenter(level, seat);
                if (center == null) {
                    continue;
                }
                double distanceSqr = center.distanceToSqr(maidWorld);
                if (distanceSqr > rangeSqr) {
                    continue;
                }
                if (index < bestIndex || (index == bestIndex && distanceSqr < bestDistanceSqr)) {
                    bestIndex = index;
                    bestDistanceSqr = distanceSqr;
                    best = new SeatSpot(seatLevel(level, seat), seat.pos(), center, seat.subLevelId());
                }
            }
        }
        return best;
    }

    /**
     * Whether the maid holds the mount she is posted to. The first post of the
     * chain that has anyone on it is in charge, so a relief post only takes
     * over once every post in front of it is empty.
     */
    static boolean holdsCommand(Level level, Entity rider, StabilizerControllerBlockEntity controller) {
        if (!(controller instanceof ControllerSeatOrder order)) {
            return true;
        }
        List<BindingRef> seats = order.firecontrolcompat$orderedSeats();
        if (seats.size() <= 1) {
            return true;
        }
        int index = order.firecontrolcompat$seatIndex(rider);
        if (index < 0) {
            return false;
        }
        for (int ahead = 0; ahead < index; ahead++) {
            if (order.firecontrolcompat$seatManned(ahead)) {
                return false;
            }
        }
        return true;
    }

    /** Bound seats of a computer in command order, the lone seat as a fallback. */
    private static List<BindingRef> orderedSeats(StabilizerControllerBlockEntity controller) {
        if (controller instanceof ControllerSeatOrder order) {
            List<BindingRef> seats = order.firecontrolcompat$orderedSeats();
            if (!seats.isEmpty()) {
                return seats;
            }
        }
        BindingRef seat = controller.getBinding(BindingSlot.SEAT);
        return seat == null ? List.of() : List.of(seat);
    }

    /** Whether anybody but this maid already sits on the post. */
    private static boolean seatOccupiedByOther(Level level, BindingRef seat, Entity rider) {
        Level seatLevel = seatLevel(level, seat);
        if (seatLevel == null || !seatLevel.hasChunkAt(seat.pos())) {
            return false;
        }
        AABB box = new AABB(seat.pos()).inflate(1.5);
        for (Entity vehicle : seatLevel.getEntitiesOfClass(Entity.class, box)) {
            if (!seat.matchesEntityPosition(level, vehicle)) {
                continue;
            }
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger != rider && !passenger.isRemoved()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the maid stands close enough to climb onto the seat. */
    static boolean inReach(Level level, Entity rider, SeatSpot spot) {
        Vec3 maidWorld = worldPosition(level, rider.position());
        return maidWorld != null && maidWorld.distanceToSqr(spot.worldCenter()) <= REACH_SQR;
    }

    /** Sits the maid down on a bound seat, using the seat's own sit-down hook. */
    static boolean sit(Entity rider, SeatSpot spot) {
        Level seatLevel = spot.seatLevel();
        BlockPos pos = spot.pos();
        if (seatLevel == null || pos == null || !seatLevel.hasChunkAt(pos)) {
            return false;
        }
        if (!(seatLevel.getBlockState(pos).getBlock() instanceof SeatBlock)) {
            return false;
        }
        if (rider.isPassenger()) {
            rider.stopRiding();
        }
        if (spot.subLevelId() != null) {
            SubLevel subLevel = SubLevelContainer.getContainer(seatLevel).getSubLevel(spot.subLevelId());
            if (subLevel != null && !subLevel.isRemoved() && rider instanceof EntityMovementExtension movement) {
                movement.sable$setTrackingSubLevel(subLevel);
            }
        }
        for (SeatEntity seat : seatEntities(seatLevel, pos)) {
            if (seat.hasPassenger(rider)) {
                return true;
            }
            if (seat.getPassengers().isEmpty() && rider.startRiding(seat, true)) {
                return true;
            }
        }
        SeatBlock.sitDown(seatLevel, pos, rider);
        return rider.getVehicle() instanceof SeatEntity seat && seat.hasPassenger(rider);
    }

    /** Turns the maid's head toward a world position, sublevel aware. */
    static void lookAt(Mob rider, Vec3 worldTarget) {
        if (worldTarget == null) {
            return;
        }
        Vec3 localTarget = worldTarget;
        SubLevel containing = Sable.HELPER.getContaining(rider);
        if (containing != null && !containing.isRemoved()) {
            Vector3d local = containing.logicalPose()
                    .transformPositionInverse(new Vector3d(worldTarget.x, worldTarget.y, worldTarget.z));
            localTarget = new Vec3(local.x, local.y, local.z);
        }
        rider.getLookControl().setLookAt(localTarget);
    }

    /** World position of a position that may sit inside a sublevel. */
    static Vec3 worldPosition(Level level, Vec3 position) {
        if (level == null || position == null) {
            return null;
        }
        try {
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(level, position);
            return world != null && finite(world) ? world : position;
        } catch (Throwable ignored) {
            return position;
        }
    }

    private static Vec3 seatWorldCenter(Level level, BindingRef seat) {
        try {
            Vec3 center = seat.worldCenter(level);
            if (center != null && finite(center)) {
                return center;
            }
        } catch (Throwable ignored) {
        }
        if (seat.subLevelId() == null) {
            return null;
        }
        return null;
    }

    private static Level seatLevel(Level level, BindingRef seat) {
        if (seat.subLevelId() == null) {
            return level;
        }
        try {
            SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(seat.subLevelId());
            if (subLevel != null && !subLevel.isRemoved()) {
                return subLevel.getLevel();
            }
        } catch (Throwable ignored) {
        }
        return level;
    }

    private static List<SeatEntity> seatEntities(Level level, BlockPos pos) {
        AABB box = new AABB(pos).inflate(0.5);
        return level.getEntitiesOfClass(SeatEntity.class, box, seat -> seat.blockPosition().equals(pos));
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
