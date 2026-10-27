package com.qkdream.firecontrolcompat.compat.yuushya;

import com.hooya.stabilizedturret.content.cannon.compat.YuushyaShowMediumCannonBarrelBlock;
import com.hooya.stabilizedturret.registry.ModCbcContent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.MediumcannonBlock;

/**
 * The cannon type the fire control mod adds to the modelling blocks: its
 * nethersteel medium cannon barrel.
 *
 * <p>Every mention of that block lives here. It belongs to the fire control mod
 * and needs the modern warfare mod for the medium cannon interface it
 * implements, so the lookups are written to answer "not there" instead of
 * failing when one of the two is missing. The merged cannon button then simply
 * leaves the medium barrel out of its list.</p>
 */
public final class YuushyaMediumCannonCompat {

    /** The tag the fire control mod stores its own cannon behaviour under. */
    public static final String BEHAVIOR_TAG = "StabilizedTurretCannonBehavior";

    private YuushyaMediumCannonCompat() {
    }

    /** True while the fire control mod registered its medium cannon barrel. */
    public static boolean isAvailable() {
        try {
            return mediumBlock() != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** True for a modelling block that is playing the medium cannon barrel. */
    public static boolean isBarrel(BlockState state) {
        Block block = mediumBlock();
        return block != null && state.is(block);
    }

    /** The axis the medium barrel points along. */
    public static Axis axisOf(BlockState state) {
        return state.getValue(YuushyaShowMediumCannonBarrelBlock.CANNON_AXIS);
    }

    /** The given medium barrel state with the wanted axis. */
    public static BlockState withAxis(BlockState state, Axis axis) {
        return state.setValue(YuushyaShowMediumCannonBarrelBlock.CANNON_AXIS, axis);
    }

    /** An empty medium barrel, or null while the fire control mod has none. */
    public static BlockState defaultState() {
        Block block = mediumBlock();
        return block == null ? null : block.defaultBlockState();
    }

    /** Lets the medium cannon of the freshly converted block connect around. */
    public static void onPlace(ServerLevel level, BlockPos pos) {
        MediumcannonBlock.onPlace(level, pos);
    }

    /**
     * True if the neighbouring block is a medium cannon that runs along
     * {@code direction}, the medium cannon counterpart of the check the big
     * cannon side does, used to pick the axis of a freshly converted block.
     */
    public static boolean runsAlong(BlockState neighborState, Direction direction) {
        try {
            if (!(neighborState.getBlock() instanceof MediumcannonBlock cannonBlock)) {
                return false;
            }
            return cannonBlock.getFacing(neighborState).getAxis() == direction.getAxis()
                    && cannonBlock.canConnectToSide(neighborState, direction.getOpposite());
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static Block mediumBlock() {
        DeferredBlock<YuushyaShowMediumCannonBarrelBlock> holder = ModCbcContent.YUUSHYA_SHOW_MEDIUM_CANNON_BARREL;
        return holder == null ? null : holder.get();
    }
}