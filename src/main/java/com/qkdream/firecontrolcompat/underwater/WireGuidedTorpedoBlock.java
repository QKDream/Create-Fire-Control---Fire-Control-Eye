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
 * The wire-guided round as it lies in the world, in a tube and in the rack.
 *
 * <p>It is one of CBCMS's own torpedo rounds, so the block is built on that
 * mod's fuzed torpedo block exactly as the acoustic round is: the same fuzed
 * body, the same fuze and tracer fitting, the same data pack entry describing
 * the swim. The fuze and the tracer are read off the saved block on the way
 * into a gun, so a round that stood in the world with a fuze fitted to it still
 * bursts when it is thrown.</p>
 */
public class WireGuidedTorpedoBlock extends FuzedTorpedoProjectileBlock<FuzedBlockEntity, WireGuidedTorpedoProjectile> {

    private static final MapCodec<WireGuidedTorpedoBlock> CODEC = simpleCodec(WireGuidedTorpedoBlock::new);

    public WireGuidedTorpedoBlock(Properties properties) {
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
    public EntityType<? extends WireGuidedTorpedoProjectile> getAssociatedEntityType() {
        return WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO_ENTITY.get();
    }

    @Override
    public AbstractCannonTorpedoProjectile getProjectile(Level level, List<StructureBlockInfo> projectileBlocks) {
        AbstractCannonTorpedoProjectile projectile = super.getProjectile(level, projectileBlocks);
        if (projectile instanceof WireGuidedTorpedoProjectile torpedo && !projectileBlocks.isEmpty()) {
            CompoundTag nbt = projectileBlocks.get(0).nbt();
            readFittings(torpedo, nbt, level.registryAccess());
            readVisualRound(torpedo, nbt);
            TorpedoDesignation.apply(torpedo);
        }
        return projectile;
    }

    @Override
    public AbstractCannonTorpedoProjectile getProjectile(Level level, BlockPos pos, BlockState state) {
        AbstractCannonTorpedoProjectile projectile = super.getProjectile(level, pos, state);
        if (projectile instanceof WireGuidedTorpedoProjectile torpedo
                && level.getBlockEntity(pos) instanceof FuzedBlockEntity blockEntity) {
            torpedo.setFuze(blockEntity.getFuze());
            torpedo.setTracer(blockEntity.getTracer());
            TorpedoDesignation.apply(torpedo);
        }
        return projectile;
    }

    /**
     * Hands a round the mark that says it is standing in for 面包学's own visually
     * guided round, and the eyes that round is to swim towards. The gunner who
     * fired the tube is preferred over the layer who loaded it: the round goes
     * where the sights are pointing, and the sights are the shooter's.
     */
    private static void readVisualRound(WireGuidedTorpedoProjectile torpedo, CompoundTag nbt) {
        if (nbt == null || !nbt.getBoolean(WireGuidedTorpedoProjectile.VISUAL_TAG)) {
            return;
        }
        java.util.UUID shooter = VisualCarrierMark.shooter();
        torpedo.carryVisualRound(shooter != null
                ? shooter.toString()
                : nbt.getString(WireGuidedTorpedoProjectile.VISUAL_OWNER_TAG));
    }

    /**
     * A round that stands in for 面包学's own is handed back as the round it went
     * in as, so a round pulled out of a tube is the round that was put in.
     */
    @Override
    public ItemStack getExtractedItem(StructureBlockInfo info, Provider registries) {
        if (info != null && info.nbt() != null
                && info.nbt().getBoolean(WireGuidedTorpedoProjectile.VISUAL_TAG)) {
            return VisualTorpedoTube.visualRoundItem();
        }
        return super.getExtractedItem(info, registries);
    }

    /** Hands a round the fuze and the tracer tip the saved block carries. */
    private static void readFittings(WireGuidedTorpedoProjectile torpedo, CompoundTag nbt, Provider registries) {
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
