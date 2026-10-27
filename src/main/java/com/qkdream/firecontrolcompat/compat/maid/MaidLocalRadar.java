package com.qkdream.firecontrolcompat.compat.maid;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.RadarEmitters;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Structure search the fire control operator maid runs herself while the
 * computer she is posted to has no working radar.
 *
 * <p>A post without a radar sweep sees nothing at all, so the crew had no work
 * even with a hostile rig a few hundred blocks away. The fallback sweep walks
 * the loaded Sable structures, keeps the ones inside {@link #SEARCH_RANGE} of the
 * computer and hands them over in the same shape the radar list uses, so the rest
 * of the crew logic - target picking, friend-or-foe, the lead solvers and the
 * release rules - runs unchanged.</p>
 *
 * <p>Only structures are looked at. Ordnance is what a radar paints, and without
 * one a round crossing at Mach speed cannot be tracked anyway, so the fallback
 * deliberately never reports it.</p>
 *
 * <p>The sweep also registers itself as a scan of the computer
 * ({@link RadarEmitters#rememberScan}), which is what lets the hand over point be
 * carried forward to the current tick while the platform moves, exactly the way a
 * radar sweep is.</p>
 */
final class MaidLocalRadar {

    /** Reach of the fallback search, in blocks. */
    static final double SEARCH_RANGE = 1500.0;

    /**
     * Ticks between two sweeps while something is on the scope. The same interval the
     * computer's own radar uses, so the carry forward window the aiming code was
     * built around still holds.
     */
    private static final int SCAN_INTERVAL_TICKS = 2;

    /**
     * Ticks between two sweeps while the scope is still empty.
     *
     * <p>Each sweep walks every loaded Sable structure and asks for its world bounds, so an empty
     * post used to pay that walk ten times a second for nothing - and a post with no radar is the
     * normal case for most of a session. A structure that comes into reach is found within half a
     * second, which is far quicker than the mount can slew onto it.</p>
     */
    private static final int IDLE_SCAN_INTERVAL_TICKS = 10;

    /** Most contacts one sweep hands over. */
    private static final int MAX_CONTACTS = 16;

    private MaidLocalRadar() {
    }

    /**
     * Structure contacts around the computer, rebuilt every few ticks and cached
     * in between. Empty when there is nothing to report, never null.
     */
    static List<StabilizerControllerBlockEntity.RadarContact> contacts(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            MaidFireControlOperator.State state,
            long gameTime) {
        List<StabilizerControllerBlockEntity.RadarContact> cached = state.localContacts;
        int interval = cached != null && !cached.isEmpty()
                ? SCAN_INTERVAL_TICKS
                : IDLE_SCAN_INTERVAL_TICKS;
        if (cached != null
                && state.localScanTick != Long.MIN_VALUE
                && gameTime - state.localScanTick < interval) {
            return cached;
        }
        List<StabilizerControllerBlockEntity.RadarContact> found =
                level == null ? List.of() : sweep(level, controller, state, gameTime);
        state.localContacts = found;
        return found;
    }

    /** Drops the cached sweep, so the next tick starts from a fresh one. */
    static void forget(MaidFireControlOperator.State state) {
        state.localContacts = List.of();
        state.localCenters.clear();
        state.localScanTick = Long.MIN_VALUE;
    }

    private static List<StabilizerControllerBlockEntity.RadarContact> sweep(
            ServerLevel level,
            StabilizerControllerBlockEntity controller,
            MaidFireControlOperator.State state,
            long gameTime) {
        long elapsed = state.localScanTick == Long.MIN_VALUE
                ? SCAN_INTERVAL_TICKS
                : Math.max(1L, gameTime - state.localScanTick);
        state.localScanTick = gameTime;
        Vec3 origin = origin(controller);
        Set<UUID> own = Set.of();
        try {
            own = controller.getOwnControlledSubLevelIds();
        } catch (Throwable ignored) {
        }
        List<StabilizerControllerBlockEntity.RadarContact> found = new ArrayList<>();
        Map<UUID, Vec3> centers = new HashMap<>();
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                double reachSqr = SEARCH_RANGE * SEARCH_RANGE;
                for (SubLevel subLevel : container.getAllSubLevels()) {
                    if (subLevel == null || subLevel.isRemoved()) {
                        continue;
                    }
                    UUID id = subLevel.getUniqueId();
                    if (id == null || own.contains(id)) {
                        continue;
                    }
                    AABB bounds = StabilizerControllerBlockEntity.subLevelWorldBounds(subLevel);
                    if (bounds == null || !finite(bounds)) {
                        continue;
                    }
                    if (distanceToBoundsSqr(origin, bounds) > reachSqr) {
                        continue;
                    }
                    Vec3 center = bounds.getCenter();
                    Vec3 previous = state.localCenters.get(id);
                    Vec3 velocity = previous == null
                            ? Vec3.ZERO
                            : center.subtract(previous).scale(1.0 / elapsed);
                    centers.put(id, center);
                    found.add(new StabilizerControllerBlockEntity.RadarContact(
                            id, center, velocity, bounds, Set.of(id)));
                }
            }
        } catch (Throwable ignored) {
        }
        state.localCenters.clear();
        state.localCenters.putAll(centers);
        if (found.size() > 1) {
            found.sort(Comparator.comparingDouble(contact -> contact.center().distanceToSqr(origin)));
        }
        if (found.size() > MAX_CONTACTS) {
            found = new ArrayList<>(found.subList(0, MAX_CONTACTS));
        }
        // Registered as a sweep of this computer so the rest of the mod carries
        // the hand over point forward the way it does for a radar contact.
        RadarEmitters.rememberScan(controller, gameTime);
        return List.copyOf(found);
    }

    /** The point the search measures from, never null. */
    private static Vec3 origin(StabilizerControllerBlockEntity controller) {
        if (controller != null) {
            try {
                Vec3 origin = controller.getRadarOrigin();
                if (origin != null) {
                    return origin;
                }
            } catch (Throwable ignored) {
            }
            try {
                return Vec3.atCenterOf(controller.getBlockPos());
            } catch (Throwable ignored) {
            }
        }
        return Vec3.ZERO;
    }

    private static boolean finite(AABB bounds) {
        return Double.isFinite(bounds.minX)
                && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ)
                && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY)
                && Double.isFinite(bounds.maxZ);
    }

    private static double distanceToBoundsSqr(Vec3 point, AABB bounds) {
        double x = Math.max(bounds.minX, Math.min(bounds.maxX, point.x));
        double y = Math.max(bounds.minY, Math.min(bounds.maxY, point.y));
        double z = Math.max(bounds.minZ, Math.min(bounds.maxZ, point.z));
        return point.distanceToSqr(x, y, z);
    }
}
