package com.qkdream.firecontrolcompat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Acoustics of the silencer tiles: what a noise has to get past to be heard.
 *
 * <p>The tiles themselves are the camouflage mod's blocks cut to a shape of
 * their own, so they are registered by the support in
 * {@code compat.copycats} while this class stays clear of that mod entirely.
 * The support hands over one predicate that says whether a block state is a
 * tile, and the listening side of the game asks this class about it.</p>
 *
 * <p>A noise is swallowed while a tile is hugging the spot it was made at: the
 * panelling the engine was boxed in with, the plate the gun was bolted behind.
 * It is swallowed just the same while tiles stand between the noise and the ear
 * listening for it, which is what a hull wrapped in them does - the way wool
 * hides a machine from the sculk. A swallowed noise is never handed to the sculk
 * network, so a sensor in a quiet engine room stays dark and a warden never
 * turns towards it, and it is never painted by a listening sonar set either.</p>
 *
 * <p>Pinging a hull lined with tiles still finds her, only later: each tile the
 * echo has to come back through shortens the reach of the ping, so the same
 * ship answers at half, a quarter or an eighth of the distance depending on how
 * much of her carries them.</p>
 *
 * <p>Both walks happen where the blocks actually are. Sable keeps a structure's
 * blocks in the plot its sub-level owns, far from where the hull is seen, so a
 * spot read where a structure appears would only ever answer with the ground
 * behind her. A noise is therefore looked up as it was handed over, and a path
 * is walked a second time in each structure's own space.</p>
 */
public final class SilencerShield {

    /** Grid around a source that a tile still covers, in blocks. */
    private static final int HUG = 1;

    /** Distance between two samples of a path, in blocks. */
    private static final double STEP = 0.5D;

    /** How far along the path from the noise tiles are looked for, in blocks. */
    private static final double PATH = 32.0D;

    /** Most samples taken along one path. */
    private static final int MAX_SAMPLES = 64;

    /** Reach left once the echo has come back through one tile. */
    private static final double PER_TILE = 0.5D;

    /** However well a hull is lined, a ping still reaches this far. */
    private static final double FLOOR = 0.125D;

    /** Most tiles the walk counts, which is all the floor needs. */
    private static final int MAX_COUNTED = 3;

    /** Told by the tile support which block states are tiles. */
    private static volatile Predicate<BlockState> tiles = state -> false;

    private SilencerShield() {
    }

    /** Called by the tile support once the tiles exist. */
    public static void install(Predicate<BlockState> detector) {
        if (detector != null) {
            tiles = detector;
        }
    }

    /** True when this state is one of the tiles. */
    public static boolean isTile(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        try {
            return tiles.test(state);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * True when a tile is hugging the spot a noise was made at. The spot is
     * read where it was handed over, and again where the structure it belongs
     * to is seen, so a noise made inside a sub-level is judged against the
     * tiles stored around it.
     */
    public static boolean muffled(Level level, Vec3 position) {
        if (level == null || position == null) {
            return false;
        }
        try {
            if (around(level, position)) {
                return true;
            }
            Vec3 world = SubLevelPositions.worldPosition(level, position);
            return world != null && world.distanceToSqr(position) > 1.0E-6D && around(level, world);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * How many tiles stand between a noise and the ear listening for it, along
     * the straight line the two are joined by. The walk starts at the noise,
     * because that is where tiles are put to box a source in, and stops after
     * {@link #PATH} blocks so a far off set costs no more than a near one.
     *
     * <p>The line is walked in the main world - skipping whatever a structure
     * covers, where the main world holds only the ground behind her - and then
     * once more inside every structure it passes through, carried into that
     * structure's own space where her blocks really live.</p>
     */
    public static int crossings(Level level, Vec3 from, Vec3 to) {
        if (level == null || from == null || to == null) {
            return 0;
        }
        int crossed = 0;
        try {
            Vec3 span = to.subtract(from);
            double length = span.length();
            if (length < STEP) {
                return 0;
            }
            Vec3 towards = span.scale(1.0D / length);
            double walked = Math.min(length, PATH);
            List<SubLevel> structures = structures(level, from, from.add(towards.scale(walked)));
            List<AABB> covered = new ArrayList<>(structures.size());
            for (SubLevel sub : structures) {
                try {
                    AABB box = sub.boundingBox().toMojang();
                    if (box != null) {
                        covered.add(box.inflate(0.5D));
                    }
                } catch (Throwable ignored) {
                }
            }
            crossed += walk(level, from, to, covered);
            for (SubLevel sub : structures) {
                BlockGetter blocks = sub.getLevel();
                Vec3 localFrom = local(sub, from);
                Vec3 localTo = local(sub, to);
                if (blocks != null && localFrom != null && localTo != null) {
                    crossed += walk(blocks, localFrom, localTo, null);
                }
            }
        } catch (Throwable ignored) {
        }
        return crossed;
    }

    /**
     * How much of a ping's reach is left when its echo has to come back out
     * through whatever the target is lined with.
     */
    public static double echoReach(Level level, Vec3 from, Vec3 to) {
        int crossed = crossings(level, from, to);
        if (crossed <= 0) {
            return 1.0D;
        }
        return Math.max(FLOOR, Math.pow(PER_TILE, Math.min(crossed, MAX_COUNTED)));
    }

    /** True when a tile sits within {@link #HUG} blocks of the spot. */
    private static boolean around(Level level, Vec3 position) {
        BlockPos centre = BlockPos.containing(position);
        for (int dx = -HUG; dx <= HUG; dx++) {
            for (int dy = -HUG; dy <= HUG; dy++) {
                for (int dz = -HUG; dz <= HUG; dz++) {
                    if (isTile(level.getBlockState(centre.offset(dx, dy, dz)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * One walk of the line the two spots are joined by, up to {@link #PATH}
     * blocks from the near end. A spot covered by a structure is skipped when
     * that list is given, because the main world does not hold her blocks.
     */
    private static int walk(BlockGetter blocks, Vec3 from, Vec3 to, List<AABB> covered) {
        if (blocks == null || from == null || to == null) {
            return 0;
        }
        int crossed = 0;
        try {
            Vec3 span = to.subtract(from);
            double length = span.length();
            if (length < STEP) {
                return 0;
            }
            Vec3 towards = span.scale(1.0D / length);
            int samples = (int) Math.min(MAX_SAMPLES, Math.floor(Math.min(length, PATH) / STEP));
            for (int step = 1; step <= samples; step++) {
                Vec3 point = from.add(towards.scale(step * STEP));
                if (covered != null && inside(covered, point)) {
                    continue;
                }
                if (isTile(blocks.getBlockState(BlockPos.containing(point)))) {
                    crossed++;
                }
            }
        } catch (Throwable ignored) {
        }
        return crossed;
    }

    /** The structures the line passes through, read from where they are seen. */
    private static List<SubLevel> structures(Level level, Vec3 from, Vec3 to) {
        List<SubLevel> found = new ArrayList<>(2);
        try {
            BoundingBox3d bounds = new BoundingBox3d(
                    Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z),
                    Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z));
            for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, bounds)) {
                if (sub != null && !sub.isRemoved()) {
                    found.add(sub);
                }
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    /** A world spot carried into a structure's own space. */
    private static Vec3 local(SubLevel sub, Vec3 world) {
        try {
            Pose3dc pose = sub == null ? null : sub.logicalPose();
            return pose == null ? null : pose.transformPositionInverse(world);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean inside(List<AABB> boxes, Vec3 point) {
        for (AABB box : boxes) {
            if (box != null && box.contains(point)) {
                return true;
            }
        }
        return false;
    }
}
