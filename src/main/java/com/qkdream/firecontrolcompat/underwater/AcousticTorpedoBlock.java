package com.qkdream.firecontrolcompat.underwater;

import com.cainiao1053.cbcmoreshells.index.CBCMSMunitionPropertiesHandlers;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.AbstractCannonTorpedoProjectile;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.FuzedTorpedoProjectileBlock;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.config.TorpedoProperties;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.index.CBCBlockEntities;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBlockEntity;

/**
 * The torpedo as it lies in the world, in a torpedo tube and in the rack.
 *
 * <p>It is one of CBCMS's own torpedo rounds - the tube family demands that, a
 * tube only ever loads a block that says it is a torpedo - so the block is
 * built on that mod's fuzed torpedo block: the same waterlogged body, the same
 * fuze and tracer fitting, the same data pack entry describing the swim that
 * the rest of their torpedoes read. The fuze still goes through CBC's own fuze
 * block entity, which is the one this mod already opens up to the round, so
 * everything a player could do to a torpedo before still works.</p>
 *
 * <p>One thing is read here rather than left to their reader: the fuze and the
 * tracer tip. A round pushed into a gun out of the world arrives as the block's
 * own saved data, where the fuze sits under its own key, while the components
 * beside it are the ones the block had when it was placed - so a round standing
 * in the world with a fuze fitted to it would be pushed in without one, and a
 * round with no fuze never bursts. Both keys are read on the way in, the way
 * CBC reads its own shells.</p>
 */
public class AcousticTorpedoBlock extends FuzedTorpedoProjectileBlock<FuzedBlockEntity, AcousticTorpedoProjectile> {

    private static final MapCodec<AcousticTorpedoBlock> CODEC = simpleCodec(AcousticTorpedoBlock::new);

    public AcousticTorpedoBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<FuzedBlockEntity> getBlockEntityClass() {
        return FuzedBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FuzedBlockEntity> getBlockEntityType() {
        return CBCBlockEntities.FUZED_BLOCK.get();
    }

    @Override
    public boolean isBaseFuze() {
        return propertiesOf(this.getAssociatedEntityType()).fuze().baseFuze();
    }

    @Override
    public EntityType<? extends AcousticTorpedoProjectile> getAssociatedEntityType() {
        return AcousticTorpedoes.ACOUSTIC_TORPEDO_ENTITY.get();
    }

    /**
     * The round that comes out of a gun the round was pushed into as a block.
     *
     * <p>CBCMS reads the fuze and the tracer off the block's components; the
     * keys written when the block is saved are the live ones, so what was
     * fitted to a round that was standing in the world is read back here and
     * takes the place of what the components said.</p>
     */
    @Override
    public AbstractCannonTorpedoProjectile getProjectile(Level level, List<StructureBlockInfo> projectileBlocks) {
        AbstractCannonTorpedoProjectile projectile = super.getProjectile(level, projectileBlocks);
        if (projectile instanceof AcousticTorpedoProjectile torpedo) {
            TorpedoDesignation.apply(torpedo);
            if (!projectileBlocks.isEmpty()) {
                readFittings(torpedo, projectileBlocks.get(0).nbt(), level.registryAccess());
            }
        }
        return projectile;
    }

    /**
     * The round that comes out of a block blown up where it stands, which is
     * how a torpedo lying in the open ends up in the water: CBCMS builds it
     * bare, so the fuze is read off the block entity the same way.
     */
    @Override
    public AbstractCannonTorpedoProjectile getProjectile(Level level, BlockPos pos, BlockState state) {
        AbstractCannonTorpedoProjectile projectile = super.getProjectile(level, pos, state);
        if (projectile instanceof AcousticTorpedoProjectile torpedo) {
            TorpedoDesignation.apply(torpedo);
            if (level.getBlockEntity(pos) instanceof FuzedBlockEntity blockEntity) {
                torpedo.setFuze(blockEntity.getFuze());
                torpedo.setTracer(blockEntity.getTracer());
            }
        }
        return projectile;
    }

    /** Hands a round the fuze and the tracer tip the saved block carries. */
    private static void readFittings(AcousticTorpedoProjectile torpedo, CompoundTag nbt, Provider registries) {
        if (nbt == null) {
            return;
        }
        ItemStack fuze = ItemStack.parseOptional(registries, nbt.getCompound("Fuze"));
        if (!fuze.isEmpty()) {
            torpedo.setFuze(fuze);
        }
        ItemStack tracer = ItemStack.parseOptional(registries, nbt.getCompound("Tracer"));
        if (!tracer.isEmpty()) {
            torpedo.setTracer(tracer);
        }
    }

    private static TorpedoProperties propertiesOf(EntityType<?> type) {
        return (TorpedoProperties) CBCMSMunitionPropertiesHandlers.TORPEDO_PROJECTILE.getPropertiesOf(type);
    }
}
