package com.qkdream.firecontrolcompat.compat.yuushya;

import com.simibubi.create.api.contraption.transformable.TransformableBlock;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.yuushya.modelling.block.blockstate.YuushyaBlockStates;
import com.yuushya.modelling.blockentity.showblock.ShowBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
import rbasamoyai.createbigcannons.cannons.big_cannons.cannon_end.BigCannonEnd;
import rbasamoyai.createbigcannons.cannons.big_cannons.material.BigCannonMaterial;
import rbasamoyai.createbigcannons.crafting.casting.CannonCastShape;
import rbasamoyai.createbigcannons.index.CBCBlocks;

/**
 * A modelling block that counts as a Create Big Cannons nethersteel big cannon
 * barrel. Material, cast shape and opening are read straight off the real
 * nethersteel barrel, so the block behaves exactly like the barrel it copies:
 * it connects to nethersteel chambers and breeches of the same axis line and
 * assembles on a cannon mount.
 */
public final class YuushyaShowBigCannonBarrelBlock extends ShowBlock implements BigCannonBlock, TransformableBlock {

    /** Axis the barrel points along, the big cannon counterpart of a facing. */
    public static final EnumProperty<Axis> CANNON_AXIS = EnumProperty.create("cannon_axis", Axis.class);

    public YuushyaShowBigCannonBarrelBlock(Properties properties) {
        super(properties, 1);
        this.registerDefaultState(this.defaultBlockState().setValue(CANNON_AXIS, Axis.Z));
    }

    /** Properties of the modelling block, matching the stock entity block. */
    public static Properties createProperties() {
        return Properties.of()
                .noOcclusion()
                .forceSolidOn()
                .strength(4.0F)
                .lightLevel(state -> state.getValue(YuushyaBlockStates.LIT));
    }

    /** The nethersteel big cannon barrel every value is read from. */
    private static BigCannonBlock nethersteelBarrel() {
        return (BigCannonBlock) CBCBlocks.NETHERSTEEL_CANNON_BARREL.get();
    }

    @Override
    public BigCannonMaterial getCannonMaterial() {
        return nethersteelBarrel().getCannonMaterial();
    }

    @Override
    public CannonCastShape getCannonShape() {
        return nethersteelBarrel().getCannonShape();
    }

    @Override
    public BigCannonEnd getDefaultOpeningType() {
        return nethersteelBarrel().getDefaultOpeningType();
    }

    @Override
    public Direction getFacing(BlockState state) {
        return Direction.get(AxisDirection.POSITIVE, state.getValue(CANNON_AXIS));
    }

    @Override
    public boolean isComplete(BlockState state) {
        return true;
    }

    /**
     * The block entity with the cannon interface on it, so a freshly placed
     * barrel is a cannon block right away. Saved worlds come in through
     * {@code BlockEntityType#create} instead, which is covered by the mixin of
     * this mod.
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new YuushyaBigCannonBlockEntity(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CANNON_AXIS);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!state.is(oldState.getBlock())) {
            BigCannonBlock.onPlace(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            this.onRemoveCannon(state, level, pos, newState, movedByPiston);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState transform(BlockState state, StructureTransform transform) {
        BlockState transformed = state.setValue(CANNON_AXIS, transform.rotateAxis(state.getValue(CANNON_AXIS)));
        if (transform.mirror != null) {
            transformed = this.mirror(transformed, transform.mirror);
        }
        if (transform.rotationAxis == Axis.Y) {
            transformed = this.rotate(transformed, transform.rotation);
        }
        return transformed;
    }
}