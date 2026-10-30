package com.qkdream.firecontrolcompat.sonar;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The sonar set itself. Mount it on a hull, point it with the wrench, and wire
 * it to a fire control computer: whatever the water is carrying inside its
 * window shows up on the set's picture.
 *
 * <p>The dish listens every way out of the box. Turning it to a bearing and
 * narrowing the window is done on the fire control screen, so a set that has
 * not been wired to anything yet still hears the whole horizon.</p>
 */
public class SonarBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<SonarBlock> CODEC = simpleCodec(SonarBlock::new);

    public SonarBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SonarBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : SonarBlockEntity::serverTick;
    }

    /** Right-clicking the dish reports who it answers to. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof SonarBlockEntity sonar)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            player.displayClientMessage(
                    Component.translatable(sonar.isBound()
                            ? "message.firecontrolcompat.sonar.status_bound"
                            : "message.firecontrolcompat.sonar.status_unbound"),
                    true);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The binding the item is carrying, when it was taken off a set that had one.
     *
     * <p>Kept in the item's own custom data rather than in {@code minecraft:block_entity_data}, so
     * nothing but this block ever reads it back: a set that is taken off one hull and bolted to the
     * same place of another keeps answering to the computer it was pointed at, which is the same
     * promise a blueprint copy makes. A set carried somewhere its computer is not simply comes up
     * bound to nothing that answers - a wrench on the computer clears it as always.</p>
     */
    public static final String BINDING_TAG = "firecontrolcompat:sonar_binding";

    /** Puts the set's own data on what it drops, so taking it off and putting it back keeps it. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        try {
            BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
            if (!(blockEntity instanceof SonarBlockEntity sonar) || drops.isEmpty()) {
                return drops;
            }
            CompoundTag data = sonar.saveWithoutMetadata(params.getLevel().registryAccess());
            if (data.isEmpty()) {
                return drops;
            }
            for (ItemStack stack : drops) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(BINDING_TAG, data));
            }
        } catch (Throwable ignored) {
            // A drop that loses the binding is a set to point at the computer again, not a crash.
        }
        return drops;
    }

    /** Gives the dropped binding back to the set that has just been put down. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level == null || level.isClientSide || stack == null || stack.isEmpty()) {
            return;
        }
        try {
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            if (custom == null) {
                return;
            }
            CompoundTag ours = custom.copyTag().getCompound(BINDING_TAG);
            if (ours.isEmpty()) {
                return;
            }
            if (level.getBlockEntity(pos) instanceof SonarBlockEntity sonar) {
                sonar.loadWithComponents(ours, level.registryAccess());
                sonar.setChanged();
                com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                        "sonar-binding: placed with the binding it was taken off with be={}",
                        pos.toShortString());
            }
        } catch (Throwable ignored) {
        }
    }
}
