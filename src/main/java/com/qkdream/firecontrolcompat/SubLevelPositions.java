package com.qkdream.firecontrolcompat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Block lookups that survive Sable sub-levels.
 *
 * <p>A structure's blocks are stored in the plot region its sub-level owns,
 * far away from where the structure appears, and the level a caller happens to
 * hold is not always the level the block can be read from. These helpers ask
 * every level that could hold the block, and turn a stored block position into
 * the world position the structure is actually seen at, so walking and
 * measuring happen where the player sees the ship instead of in the plot.</p>
 */
public final class SubLevelPositions {

    /** Startup cost probe: this class has no static fields, so its initialisation is a marker. */
    static {
        StartupProbe.staticBegin("SubLevelPositions");
    }

    private SubLevelPositions() {
    }

    /** The block entity at the position, from the first level that has it. */
    public static BlockEntity blockEntity(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockEntity found = blockEntityIn(level, pos);
        if (found != null) {
            return found;
        }
        Level plot = plotLevel(level, pos);
        if (plot != null) {
            found = blockEntityIn(plot, pos);
            if (found != null) {
                return found;
            }
        }
        Level root = rootLevel(level);
        if (root != null && root != level && root != plot) {
            found = blockEntityIn(root, pos);
        }
        return found;
    }

    /** The block state at the position, from the first level that has one. */
    public static BlockState blockState(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state != null && !state.isAir()) {
            return state;
        }
        Level plot = plotLevel(level, pos);
        if (plot != null) {
            state = plot.getBlockState(pos);
            if (state != null && !state.isAir()) {
                return state;
            }
        }
        Level root = rootLevel(level);
        if (root != null && root != level && root != plot) {
            state = root.getBlockState(pos);
        }
        return state;
    }

    /** The level the block at the position lives in, best effort. */
    public static Level level(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return level;
        }
        if (blockEntityIn(level, pos) != null || !level.getBlockState(pos).isAir()) {
            return level;
        }
        Level plot = plotLevel(level, pos);
        if (plot != null && (blockEntityIn(plot, pos) != null || !plot.getBlockState(pos).isAir())) {
            return plot;
        }
        Level root = rootLevel(level);
        if (root != null && root != level && !root.getBlockState(pos).isAir()) {
            return root;
        }
        return plot != null ? plot : level;
    }

    /** World position of a position that may sit inside a sub-level. */
    public static Vec3 worldPosition(Level level, Vec3 position) {
        if (level == null || position == null) {
            return position;
        }
        try {
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(level, position);
            if (world != null && finite(world)) {
                return world;
            }
        } catch (Throwable ignored) {
        }
        return position;
    }

    /** World position of a block position, wherever the block is stored. */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        return worldPosition(level(level, pos), Vec3.atCenterOf(pos));
    }

    /**
     * A world position expressed in the stored space of the sub-level seen at {@code anchor}.
     *
     * <p>The exact inverse of {@link #worldPosition}: that one turns a stored point into the place
     * the structure is rendered, this one turns a rendered point back into the storage region. It is
     * needed when the entity and its aim point end up on opposite sides of the split - a round whose
     * body stayed in a structure's plot while its lock was reported in world coordinates (or the
     * other way round). Without it such a pair is twenty million blocks apart and the guidance maths
     * normalises that into a random heading, which is exactly what "it launched but it does not fly
     * at the target" looks like in the log.</p>
     *
     * <p>Answers {@code world} unchanged, never {@code null}, whenever no sub-level can be resolved or
     * the pose cannot be inverted: every caller compares the answer against the input and keeps the
     * nearer of the two, so a failed inversion is harmless instead of fatal.</p>
     */
    public static Vec3 plotPosition(Level level, Vec3 world, Vec3 anchor) {
        if (level == null || world == null || anchor == null) {
            return world;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(level, BlockPos.containing(anchor));
            if (subLevel == null || subLevel.isRemoved()) {
                Level root = rootLevel(level);
                if (root != null && root != level) {
                    subLevel = Sable.HELPER.getContaining(root, BlockPos.containing(anchor));
                }
            }
            if (subLevel == null || subLevel.isRemoved()) {
                return world;
            }
            org.joml.Vector3d local = new org.joml.Vector3d(world.x, world.y, world.z);
            subLevel.logicalPose().transformPositionInverse(local);
            if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)) {
                return world;
            }
            return new Vec3(local.x, local.y, local.z);
        } catch (Throwable ignored) {
            return world;
        }
    }

    /**
     * One level a line of sight can be cast in, plus the mapping between the world's coordinates
     * and that level's own block coordinates.
     *
     * <p>This exists because Sable <b>overwrites {@code BlockGetter#clip}</b> (its
     * {@code BlockGetterMixin}, priority 1100): when the ray starts inside a sub-level it
     * re-expresses the ray in the plot's stored coordinates, clips the plot, and returns that
     * result <b>without projecting the hit back</b>. A caller that casts a world-space ray and then
     * measures the returned hit against its own world-space origin therefore reads a distance of
     * twenty million blocks - and concludes "nothing in the way". That is exactly how a terrain
     * screen silently stopped screening anything. Calling {@link #clipIn(net.minecraft.world.level.Level,
     * Vec3, Vec3)} instead disables that rewrite, so every hit comes back in the space the ray was
     * given in, and the distance is the distance.</p>
     */
    public static final class BlockSpace {

        /** Where the blocks are. */
        public final Level level;

        /** {@code root} or {@code plot}; for the log only. */
        public final String name;

        private final SubLevel subLevel;

        private BlockSpace(Level level, String name, SubLevel subLevel) {
            this.level = level;
            this.name = name;
            this.subLevel = subLevel;
        }

        /** Whether this space is a sub-level's plot rather than the main level. */
        public boolean isPlot() {
            return subLevel != null;
        }

        /** {@code name:dimension}, for the log. */
        public String id() {
            String dimension = "-";
            try {
                dimension = level == null ? "-" : level.dimension().location().toString();
            } catch (Throwable ignored) {
            }
            return name + ":" + dimension;
        }

        /** A world point expressed in this space. */
        public Vec3 toLocal(Vec3 world) {
            if (world == null || subLevel == null) {
                return world;
            }
            try {
                Vec3 local = subLevel.logicalPose().transformPositionInverse(world);
                if (local != null && finite(local)) {
                    return local;
                }
            } catch (Throwable ignored) {
            }
            return world;
        }

        /** A point of this space expressed in the world. */
        public Vec3 toWorld(Vec3 local) {
            if (local == null || subLevel == null) {
                return local;
            }
            try {
                Vec3 world = subLevel.logicalPose().transformPosition(local);
                if (world != null && finite(world)) {
                    return world;
                }
            } catch (Throwable ignored) {
            }
            return local;
        }
    }

    /** The main level as a coordinate space; never {@code null}. */
    public static BlockSpace rootSpace(Level level) {        Level root = rootLevel(level);
        if (root == null) {
            root = level;
        }
        return new BlockSpace(root, "root", null);
    }

    /**
     * A block clip that stays in one coordinate space.
     *
     * <p>Sable's {@code BlockGetter#clip} overwrite re-expresses the ray in a sub-level's stored
     * coordinates when the ray starts inside one, and hands back the plot-space hit unprojected.
     * Setting {@code sable$doNotProject} turns that off, so the answer is a plain clip against the
     * level that was asked, with the hit where the caller's own coordinates are. Everything that
     * measures a distance to the hit needs this; a raw {@code level.clip} gives a distance of
     * twenty million blocks on a structure and reads as "nothing in the way".</p>
     *
     * <p>{@code null} when the clip cannot run; callers count that, never silently accept it.</p>
     */
    public static net.minecraft.world.phys.BlockHitResult clipIn(Level level, Vec3 from, Vec3 to) {
        if (level == null || from == null || to == null) {
            return null;
        }
        net.minecraft.world.level.ClipContext clip = new net.minecraft.world.level.ClipContext(
                from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY,
                (net.minecraft.world.entity.Entity) null);
        try {
            if (clip instanceof dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension extension) {
                extension.sable$setDoNotProject(true);
            }
        } catch (Throwable ignored) {
            // No Sable on the classpath: the clip is already a plain one.
        }
        return level.clip(clip);
    }

    /** The space of the sub-level seen at a world position, or {@code null} when there is none. */
    public static BlockSpace plotSpace(Level level, Vec3 world) {
        if (level == null || world == null) {
            return null;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(level, world);
            if (subLevel == null || subLevel.isRemoved()) {
                Level root = rootLevel(level);
                if (root != null && root != level) {
                    subLevel = Sable.HELPER.getContaining(root, world);
                }
            }
            if (subLevel == null || subLevel.isRemoved()) {
                return null;
            }
            Level plot = subLevel.getLevel();
            if (plot == null) {
                return null;
            }
            return new BlockSpace(plot, "plot", subLevel);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Whether a position is stored in plot space instead of world space. */
    public static boolean inPlotSpace(Level level, Vec3 position) {
        if (level == null || position == null) {
            return false;
        }
        Vec3 projected = worldPosition(level, position);
        return projected.distanceToSqr(position) > 1.0E-6;
    }

    private static BlockEntity blockEntityIn(Level level, BlockPos pos) {
        try {
            return level == null ? null : level.getBlockEntity(pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The plot level of the sub-level that contains the position, if any. */
    private static Level plotLevel(Level level, BlockPos pos) {
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
            if (subLevel == null || subLevel.isRemoved()) {
                Level root = rootLevel(level);
                if (root != null && root != level) {
                    subLevel = Sable.HELPER.getContaining(root, pos);
                }
            }
            if (subLevel != null && !subLevel.isRemoved()) {
                return subLevel.getLevel();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** The main level behind a plot level. */
    private static Level rootLevel(Level level) {
        try {
            Level root = SubLevelContainer.getContainer(level).getLevel();
            return root != null ? root : level;
        } catch (Throwable ignored) {
            return level;
        }
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    /** Startup cost probe: closes the static initialisation window opened at the top. */
    static {
        StartupProbe.staticEnd("SubLevelPositions");
    }
}
