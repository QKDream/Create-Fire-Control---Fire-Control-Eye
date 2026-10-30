package com.qkdream.firecontrolcompat.compat.yuushya;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.BlockEntityTypeAccess;
import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
import com.yuushya.modelling.registries.BlockEntityRegistry;
import com.yuushya.modelling.registries.BlockRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;

/**
 * Turns a Yuushya Modelling "entity block" ({code ShowBlock}) into a Create Big
 * Cannons nethersteel big cannon barrel.
 *
 * <p>The block itself stays a modelling block: the player builds the barrel out
 * of whatever model he likes and only the cannon data is bolted on. The cannon
 * type is picked in the block screen, the same way the medium cannon barrel of
 * the fire control mod is enabled, and the server converts the block in place
 * while keeping its stored model and transform data.</p>
 *
 * <p>The whole class is written so that nothing of Yuushya Modelling is touched
 * before {@link #isAvailable()} answers true: with the modelling mod absent the
 * field stays null, the register call does nothing and no modelling class is
 * ever loaded.</p>
 */
public final class YuushyaBigCannonCompat {

    /** Tag the hosted cannon behaviour is stored under inside the block entity. */
    public static final String CANNON_BEHAVIOR_TAG = "FireControlEyeBigCannonBehavior";

    /** Mod id of the modelling mod this is a soft dependency on. */
    public static final String YUUSHYA = "yuushya_modelling";

    /**
     * Set once when the first entry point notices the modelling mod is absent.
     *
     * <p>One line per game session, not one per registration attempt: the registration path is
     * walked from several places (the mod bus, the model bake, the block entity type) and a pack
     * without the modelling mod would otherwise write the same sentence three times.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean SKIP_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Writes {@code yuushya: compat skipped (mod absent)} once and returns.
     *
     * <p>Called from the first line of every entry point, before any modelling class is touched.
     * Safe with the mod present too: it then does nothing.
     */
    public static void logCompatSkippedOnce(String entry) {
        if (SKIP_LOGGED.compareAndSet(false, true)) {
            FireControlCompat.LOGGER.info("yuushya: compat skipped (mod absent) entry={}", entry);
        }
    }

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(FireControlCompat.MOD_ID);

    /** The modelling block that can act as a nethersteel big cannon barrel, or null. */
    public static final DeferredBlock<Block> SHOW_BIG_CANNON_BARREL = isAvailable()
            ? BLOCKS.register("yuushya_show_big_cannon_barrel", YuushyaBigCannonCompat::createBlock)
            : null;


    private YuushyaBigCannonCompat() {
    }

    /** True while the modelling mod is installed. Safe to call at any time. */
    public static boolean isAvailable() {
        return FireControlCompat.isModLoaded(YUUSHYA);
    }


    private static Block createBlock() {
        return new YuushyaShowBigCannonBarrelBlock(YuushyaShowBigCannonBarrelBlock.createProperties());
    }

    /**
     * Registers the barrel block on the mod bus.
     *
     * <p>The mod-loaded check is the first line and not merely implied by
     * {@link #SHOW_BIG_CANNON_BARREL} being null: with the modelling mod absent this whole package
     * is dead weight, and a single explicit guard is what keeps "register" from ever reaching
     * {@code BLOCKS.register} - and therefore the block, its block entity and the modelling
     * superclass - in a pack that does not have Yuushya Modelling installed.
     */
    public static void register(IEventBus bus) {
        if (!isAvailable()) {
            logCompatSkippedOnce("register");
            return;
        }
        if (SHOW_BIG_CANNON_BARREL == null) {
            return;
        }
        BLOCKS.register(bus);
    }

    /**
     * Hands the barrel block to the block entity type of the modelling blocks,
     * so a block entity can be built for it and the placed block is a proper
     * modelling block in every other way too.
     */
    public static void initialize() {
        if (!isAvailable()) {
            logCompatSkippedOnce("initialize");
            return;
        }
        if (SHOW_BIG_CANNON_BARREL == null) {
            return;
        }
        addValidBlock(BlockEntityRegistry.SHOW_BLOCK_ENTITY.get(), SHOW_BIG_CANNON_BARREL.get());
    }

    /** True for the modelling block while it carries the big cannon barrel. */
    public static boolean isBigCannonBarrel(BlockState state) {
        return SHOW_BIG_CANNON_BARREL != null
                && state.is(SHOW_BIG_CANNON_BARREL.get());
    }

    /** The cannon type the modelling block at {@code pos} currently is. */
    public static YuushyaCannonType currentType(BlockState state) {
        if (isBigCannonBarrel(state)) {
            return YuushyaCannonType.BIG;
        }
        return YuushyaMediumCannonCompat.isBarrel(state)
                ? YuushyaCannonType.MEDIUM
                : YuushyaCannonType.NONE;
    }

    /**
     * The cannon types the block can be switched to, in the order the button in
     * the block screen cycles through them. The medium barrel of the fire
     * control mod is left out while that mod has none, so the button never
     * offers a barrel that cannot be built.
     */
    public static List<YuushyaCannonType> selectableTypes() {
        if (SHOW_BIG_CANNON_BARREL == null) {
            return List.of();
        }
        List<YuushyaCannonType> types = new ArrayList<>(3);
        types.add(YuushyaCannonType.NONE);
        if (YuushyaMediumCannonCompat.isAvailable()) {
            types.add(YuushyaCannonType.MEDIUM);
        }
        types.add(YuushyaCannonType.BIG);
        // The single-cannon families are left out for now: the modelling block can hold
        // their model, but the pack does not accept it as a cannon part yet, so
        // offering them would only produce a barrel that cannot be built.
        return List.copyOf(types);
    }

    /**
     * Switches the modelling block at {@code pos} over to the requested cannon
     * type, keeping the stored model, its layers and every shared block state
     * property.
     *
     * <p>Both cannon types go through the same steps here, which are the steps
     * the fire control mod takes for its medium barrel alone: the block is
     * swapped in place, the stored data is carried over to the new block
     * entity, and the cannon that was built is told about the fresh block so it
     * hooks up to the breech next to it.</p>
     */
    public static void setCannonType(ServerLevel level, BlockPos pos, YuushyaCannonType requested) {
        if (SHOW_BIG_CANNON_BARREL == null || requested == null) {
            return;
        }
        if (requested == YuushyaCannonType.MEDIUM && !YuushyaMediumCannonCompat.isAvailable()) {
            return;
        }
        BlockState sourceState = level.getBlockState(pos);
        BlockEntity sourceEntity = level.getBlockEntity(pos);
        if (!(sourceEntity instanceof ShowBlockEntity)) {
            return;
        }
        YuushyaCannonType current = currentType(sourceState);
        if (current == requested) {
            return;
        }
        BlockState targetState = targetState(level, pos, sourceState, current, requested);
        if (targetState == null) {
            return;
        }
        CompoundTag data = sourceEntity.saveWithoutMetadata(level.registryAccess());
        data.remove(CANNON_BEHAVIOR_TAG);
        data.remove(YuushyaMediumCannonCompat.BEHAVIOR_TAG);
        level.setBlock(pos, targetState, 3);
        BlockEntity targetEntity = level.getBlockEntity(pos);
        if (targetEntity instanceof ShowBlockEntity) {
            targetEntity.loadWithComponents(data, level.registryAccess());
            targetEntity.setChanged();
            level.sendBlockUpdated(pos, targetState, targetState, 3);
            if (requested == YuushyaCannonType.BIG) {
                BigCannonBlock.onPlace(level, pos);
            } else if (requested == YuushyaCannonType.MEDIUM) {
                YuushyaMediumCannonCompat.onPlace(level, pos);
            }
        }
    }

    /**
     * The name of the barrel a type stands for, taken from the pack's own block.
     *
     * <p>Asked of the block itself so the button reads exactly like the part the player would
     * otherwise place - a made up label here would just be one more thing that drifts from the
     * pack.</p>
     */
    public static net.minecraft.network.chat.Component barrelDisplayName(YuushyaCannonType type) {
        try {
            String name = singleBarrelName(type);
            if (!name.isEmpty()) {
                net.minecraft.world.level.block.Block barrel =
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cbcmoreshells", name));
                if (barrel != null && barrel != net.minecraft.world.level.block.Blocks.AIR) {
                    return barrel.getName();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** The pack's block name of the barrel for one single-cannon family. */
    private static String singleBarrelName(YuushyaCannonType type) {
        return switch (type) {
            case SINGLE_SLATE_ALLOY -> "slate_alloy_single_cannon_barrel";
            case SINGLE_BRASS -> "brass_single_cannon_barrel";
            case SINGLE_STEEL -> "steel_single_cannon_barrel";
            case LARGE_SINGLE_SLATE_ALLOY -> "large_slate_alloy_single_cannon_barrel";
            case LARGE_SINGLE_BRASS -> "large_brass_single_cannon_barrel";
            case LARGE_SINGLE_STEEL -> "large_steel_single_cannon_barrel";
            case MILITARY_SLATE_ALLOY -> "military_slate_alloy_single_cannon_barrel";
            case LARGE_MILITARY_SLATE_ALLOY -> "large_military_slate_alloy_single_cannon_barrel";
            default -> "";
        };
    }

    /** The state of the requested cannon type, or null while it cannot be built. */
    private static BlockState targetState(ServerLevel level, BlockPos pos, BlockState sourceState,
            YuushyaCannonType current, YuushyaCannonType requested) {
        Axis axis = axisOf(level, pos, sourceState, current);
        switch (requested) {
            case BIG:
                return copySharedProperties(sourceState, SHOW_BIG_CANNON_BARREL.get().defaultBlockState())
                        .setValue(YuushyaShowBigCannonBarrelBlock.CANNON_AXIS, axis);
            case MEDIUM:
                BlockState medium = YuushyaMediumCannonCompat.defaultState();
                return medium == null
                        ? null
                        : YuushyaMediumCannonCompat.withAxis(copySharedProperties(sourceState, medium), axis);
            case SINGLE_SLATE_ALLOY:
            case SINGLE_BRASS:
            case SINGLE_STEEL:
            case LARGE_SINGLE_SLATE_ALLOY:
            case LARGE_SINGLE_BRASS:
            case LARGE_SINGLE_STEEL:
            case MILITARY_SLATE_ALLOY:
            case LARGE_MILITARY_SLATE_ALLOY: {
                // The pack's own barrel for that family: the modelled block becomes a real part of
                // that cannon, with the barrel's block state and its facing carried over.
                // Single cannon families are not offered (see selectableTypes).
                return null;
            }
            case NONE:
            default:
                return copySharedProperties(sourceState, BlockRegistry.SHOW_BLOCK.get().defaultBlockState());
        }
    }

    /** The axis the barrel keeps while the block changes its cannon type. */
    private static Axis axisOf(ServerLevel level, BlockPos pos, BlockState state, YuushyaCannonType current) {
        switch (current) {
            case BIG:
                return state.getValue(YuushyaShowBigCannonBarrelBlock.CANNON_AXIS);
            case MEDIUM:
                return YuushyaMediumCannonCompat.axisOf(state);
            case NONE:
            default:
                return inferCannonAxis(level, pos, state);
        }
    }
    public static void addValidBlock(BlockEntityType<?> type, Block block) {
        Set<Block> validBlocks = new HashSet<>(type.getValidBlocks());
        validBlocks.add(block);
        if (type instanceof BlockEntityTypeAccess access) {
            access.firecontrolcompat$setValidBlocks(validBlocks);
        } else {
            // The type is only an accessor when this mod\'s mixin reached it. With
            // the mixin switched off, or applied late, there is nothing to widen
            // and the setup must carry on rather than die on a cast.
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] block entity type left as it is (accessor mixin not applied)");
        }
    }

    /**
     * Cannon axis of a freshly converted block: it takes the axis of the cannon
     * block it was placed against, and falls back to the direction the player
     * faced when he placed the modelling block.
     */
    private static Axis inferCannonAxis(ServerLevel level, BlockPos pos, BlockState sourceState) {
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == Axis.Y) {
                continue;
            }
            BlockState neighborState = level.getBlockState(pos.relative(direction));
            if (neighborState.getBlock() instanceof BigCannonBlock cannonBlock
                    && cannonBlock.getFacing(neighborState).getAxis() == direction.getAxis()
                    && cannonBlock.canConnectToSide(neighborState, direction.getOpposite())) {
                return direction.getAxis();
            }
            if (YuushyaMediumCannonCompat.runsAlong(neighborState, direction)) {
                return direction.getAxis();
            }
        }
        return sourceState.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis();
    }

    private static BlockState copySharedProperties(BlockState source, BlockState target) {
        BlockState result = target;
        for (Property<?> property : source.getProperties()) {
            if (result.hasProperty(property)) {
                result = copyProperty(source, result, property);
            }
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState source, BlockState target,
            Property<T> property) {
        return target.setValue(property, source.getValue(property));
    }


}
