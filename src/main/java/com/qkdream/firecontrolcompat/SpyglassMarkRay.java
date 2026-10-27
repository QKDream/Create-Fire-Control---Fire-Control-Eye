package com.qkdream.firecontrolcompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

/**
 * The line a gunner marks along.
 *
 * <p>A structure is not where its blocks are read from. Sable keeps every hull
 * in a space of its own, parked in a reserved corner of the level, and a pose is
 * what carries it to the place the player sees it in. Asking a world position
 * which structure owns it therefore answers nothing at all - the world position
 * of a deck is not the position of a deck block - so a walk that only asked that
 * question came back empty for its whole length.</p>
 *
 * <p>What a hull does answer is the box it occupies in the world, and that box
 * is measured the same way the radar measures it: the corners of the plot it
 * lives on, carried out by its own pose. Every hull is boxed first and the sight
 * line is intersected with those boxes, nearest first; only then is the line
 * carried into a candidate's own space and walked block by block, the way
 * Sable's own raycast and the fire control computer's missile fuze walk one. A
 * hull whose own chunks are not loaded on this side cannot be walked at all,
 * and for that one the box it was found by is the answer.</p>
 */
public final class SpyglassMarkRay {

    /** How far the walk follows the sight line, in blocks. */
    public static final double REACH = 4096.0;

    /** How far below the eye the deck under the gunner is probed, in blocks. */
    private static final double FOOT_PROBE = 0.35D;

    /**
     * How much closer the main world has to stop the sight line before a
     * structure behind it counts as hidden, in blocks. Loose, because a hull
     * that the world's own walk also reports lands on the same place twice.
     */
    private static final double TERRAIN_MARGIN = 1.5D;

    private SpyglassMarkRay() {
    }

    /**
     * The structure on that bearing, and what the walk had to say about it.
     * {@code feet} is only used to recognise the deck the gunner is standing on,
     * which is never the structure she means to call fire onto.
     */
    public static Result find(Level level, Vec3 eye, Vec3 look, Vec3 feet, double reach) {
        if (level == null || eye == null || look == null || look.lengthSqr() < 1.0E-8) {
            return new Result(null, 0, 0, 0, false, false, reach);
        }
        SubLevelContainer container = container(level);
        if (container == null) {
            return new Result(null, 0, 0, 0, false, true, reach);
        }
        Vec3 direction = look.normalize();
        Vec3 end = eye.add(direction.scale(reach));
        SubLevel own = standingOn(container, feet);
        int hulls = 0;
        int candidates = 0;
        List<Entry> entries = new ArrayList<>();
        try {
            for (SubLevel sub : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                hulls++;
                AABB box = worldBounds(sub);
                if (box == null) {
                    continue;
                }
                double entry = entryDistance(direction, eye, box);
                if (entry < 0.0D) {
                    continue;
                }
                candidates++;
                entries.add(new Entry(sub, entry, sub == own));
            }
        } catch (Throwable ignored) {
            return new Result(null, hulls, candidates, 0, false, false, reach);
        }
        entries.sort(Comparator.comparingDouble(Entry::entryDistance));
        Hit confirmed = null;
        double confirmedDistance = Double.MAX_VALUE;
        Hit coarse = null;
        int blockHits = 0;
        boolean ownSkipped = false;
        for (Entry entry : entries) {
            if (confirmed != null && entry.entryDistance >= confirmedDistance) {
                break;
            }
            if (entry.own) {
                ownSkipped = true;
                continue;
            }
            Vec3 point = contact(entry.sub, eye, end);
            if (point == null) {
                // Not walkable from here: the hull's own chunks are elsewhere, so
                // the box it was found by is all this side can answer.
                if (coarse == null && !walkable(entry.sub, eye.add(direction.scale(entry.entryDistance)))) {
                    coarse = new Hit(entry.sub.getUniqueId(), eye.add(direction.scale(entry.entryDistance)));
                }
                continue;
            }
            blockHits++;
            double distance = point.distanceTo(eye);
            if (distance < confirmedDistance) {
                confirmedDistance = distance;
                confirmed = new Hit(entry.sub.getUniqueId(), point);
            }
        }
        Hit hit = confirmed != null ? confirmed : coarse;
        return new Result(hit, hulls, candidates, blockHits, ownSkipped, false, reach);
    }

    /**
     * The box a structure occupies in the world right now, or null when the
     * level no longer holds it. Read fresh from the structure's own pose, so a
     * frame drawn from it follows a hull that is under way instead of sitting
     * where the mark was made.
     */
    public static AABB bounds(Level level, UUID structureId) {
        if (level == null || structureId == null) {
            return null;
        }
        SubLevelContainer container = container(level);
        if (container == null) {
            return null;
        }
        try {
            SubLevel sub = container.getSubLevel(structureId);
            return sub == null || sub.isRemoved() ? null : worldBounds(sub);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether that structure is one the level still holds. */
    public static boolean live(Level level, UUID structureId) {
        if (level == null || structureId == null) {
            return false;
        }
        SubLevelContainer container = container(level);
        if (container == null) {
            return false;
        }
        try {
            return container.getSubLevel(structureId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether the world the structures are seen in stops the sight line before
     * it reaches that one: a hill, a wall, the ground. The main world is walked
     * on its own, because a structure's own blocks are not in it - they answer
     * only through their pose, which is what {@link #find} asks them.
     */
    public static boolean blocked(Level level, Vec3 eye, Vec3 look, Hit hit) {
        if (level == null || eye == null || look == null || hit == null || hit.point() == null
                || look.lengthSqr() < 1.0E-8) {
            return false;
        }
        double distance = eye.distanceTo(hit.point());
        BlockHitResult clip;
        try {
            clip = level.clip(new ClipContext(eye,
                    eye.add(look.normalize().scale(distance + TERRAIN_MARGIN + 1.0D)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (Entity) null));
        } catch (Throwable ignored) {
            return false;
        }
        if (clip == null || clip.getType() == HitResult.Type.MISS) {
            return false;
        }
        return eye.distanceTo(clip.getLocation()) + TERRAIN_MARGIN < distance;
    }

    /**
     * Where the sight line meets this structure's blocks, or null when it passes
     * clear of them. The line is carried into the structure's own space, walked
     * there against the blocks themselves, and the meeting point carried back
     * out, which is how a hull is asked where it is without reading a world
     * position that was never the hull's.
     */
    private static Vec3 contact(SubLevel sub, Vec3 from, Vec3 to) {
        Vec3 localFrom = toLocal(sub, from);
        Vec3 localTo = toLocal(sub, to);
        if (localFrom == null || localTo == null) {
            return null;
        }
        BlockGetter blocks = sub.getLevel();
        if (blocks == null) {
            return null;
        }
        Vec3 local;
        try {
            local = BlockGetter.traverseBlocks(localFrom, localTo, blocks,
                    (getter, pos) -> shapeContact(getter, pos, localFrom, localTo), getter -> null);
        } catch (Throwable ignored) {
            return null;
        }
        return local == null ? null : toWorld(sub, local);
    }

    /** The first block of that structure the segment enters, in that structure's space. */
    private static Vec3 shapeContact(BlockGetter blocks, BlockPos pos, Vec3 localFrom, Vec3 localTo) {
        BlockState state = blocks.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        VoxelShape shape = state.getCollisionShape(blocks, pos, CollisionContext.empty());
        if (shape.isEmpty()) {
            return null;
        }
        BlockHitResult hit = shape.clip(localFrom, localTo, pos);
        return hit == null ? null : hit.getLocation();
    }

    /**
     * The box a structure occupies in the world: the corners of its plot,
     * carried out by its own pose. Read fresh on every walk rather than from the
     * bounding box the structure caches, so a hull that has just moved is
     * measured where the player sees it.
     */
    private static AABB worldBounds(SubLevel sub) {
        try {
            Pose3dc pose = sub.logicalPose();
            if (pose == null || sub.getPlot() == null) {
                return null;
            }
            BoundingBox3ic plot = sub.getPlot().getBoundingBox();
            if (plot == null) {
                return null;
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (int ix = 0; ix < 2; ix++) {
                for (int iy = 0; iy < 2; iy++) {
                    for (int iz = 0; iz < 2; iz++) {
                        Vector3d corner = new Vector3d(
                                ix == 0 ? plot.minX() : plot.maxX() + 1.0D,
                                iy == 0 ? plot.minY() : plot.maxY() + 1.0D,
                                iz == 0 ? plot.minZ() : plot.maxZ() + 1.0D);
                        pose.transformPosition(corner);
                        minX = Math.min(minX, corner.x);
                        minY = Math.min(minY, corner.y);
                        minZ = Math.min(minZ, corner.z);
                        maxX = Math.max(maxX, corner.x);
                        maxY = Math.max(maxY, corner.y);
                        maxZ = Math.max(maxZ, corner.z);
                    }
                }
            }
            if (!finite(minX) || !finite(minY) || !finite(minZ)
                    || !finite(maxX) || !finite(maxY) || !finite(maxZ)) {
                return null;
            }
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Distance along the sight line at which it enters that box, or -1 when it misses. */
    private static double entryDistance(Vec3 direction, Vec3 from, AABB box) {
        double near = 0.0D;
        double far = Double.MAX_VALUE;
        double[] origin = {from.x, from.y, from.z};
        double[] step = {direction.x, direction.y, direction.z};
        double[] low = {box.minX, box.minY, box.minZ};
        double[] high = {box.maxX, box.maxY, box.maxZ};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(step[axis]) < 1.0E-9D) {
                if (origin[axis] < low[axis] || origin[axis] > high[axis]) {
                    return -1.0D;
                }
                continue;
            }
            double first = (low[axis] - origin[axis]) / step[axis];
            double second = (high[axis] - origin[axis]) / step[axis];
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) {
                return -1.0D;
            }
        }
        return near;
    }

    /**
     * Whether the structure's own blocks can be read from here. A hull is only
     * ever walked where its chunks are loaded; anywhere else the walk returns
     * nothing for a reason that has nothing to do with the aim.
     */
    private static boolean walkable(SubLevel sub, Vec3 world) {
        Vec3 local = toLocal(sub, world);
        BlockGetter blocks = sub.getLevel();
        if (local == null || blocks == null) {
            return false;
        }
        return loaded(blocks, local);
    }

    /**
     * The structure the gunner is standing on, so a sight line that crosses her
     * own deck is not read as a bearing on somebody else's. The deck under her
     * boots is what answers, not whether she is inside a box: two hulls moored
     * side by side have boxes that overlap.
     */
    private static SubLevel standingOn(SubLevelContainer container, Vec3 feet) {
        if (feet == null) {
            return null;
        }
        Vec3 probe = feet.add(0.0D, -FOOT_PROBE, 0.0D);
        try {
            for (SubLevel sub : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                AABB box = worldBounds(sub);
                if (box == null || !box.contains(probe)) {
                    continue;
                }
                Vec3 local = toLocal(sub, probe);
                BlockGetter blocks = sub.getLevel();
                if (local == null || blocks == null) {
                    continue;
                }
                if (!blocks.getBlockState(BlockPos.containing(local)).isAir()) {
                    return sub;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static Vec3 toLocal(SubLevel sub, Vec3 world) {
        try {
            Pose3dc pose = sub == null ? null : sub.logicalPose();
            return pose == null ? null : pose.transformPositionInverse(world);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Vec3 toWorld(SubLevel sub, Vec3 local) {
        try {
            Pose3dc pose = sub == null ? null : sub.logicalPose();
            return pose == null ? null : pose.transformPosition(local);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether the level actually holds the chunk that position sits in. */
    private static boolean loaded(BlockGetter blocks, Vec3 local) {
        if (blocks instanceof Level level) {
            return level.hasChunkAt(BlockPos.containing(local));
        }
        return true;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static SubLevelContainer container(Level level) {
        try {
            return SubLevelContainer.getContainer(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A structure the sight line landed on, and the point where it did. */
    public record Hit(UUID structureId, Vec3 point) {
    }

    /** What the walk found, and enough of what it saw to explain a miss. */
    public record Result(Hit hit, int hulls, int candidates, int blockHits, boolean ownSkipped,
            boolean noContainer, double reach) {
    }

    /** One candidate, with the distance at which the sight line meets its box. */
    private record Entry(SubLevel sub, double entryDistance, boolean own) {
    }
}