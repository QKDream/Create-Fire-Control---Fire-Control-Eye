package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.BlockEntityTypeAccess;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import rbasamoyai.createbigcannons.index.CBCBlockEntities;
import com.cainiao1053.cbcmoreshells.index.CBCMSMunitionPropertiesHandlers;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.CannonTorpedoProjectileRenderer;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBlockEntity;
import rbasamoyai.createbigcannons.munitions.config.MunitionPropertiesHandler;

/**
 * The underwater family: the passive acoustic homing torpedo.
 *
 * <p>It is a torpedo in every mechanical way - fuzed, tracer-able, loaded and
 * fired by CBCMS's own torpedo tube, and thrown by 面包学's tube as well - so
 * what is needed here is the block, its item, the projectile and the data pack
 * entry that describes the swim, the ballistics and the burst, plus the one
 * call that lets the round join CBC's own fuzed block entity type, which is
 * what a fuze is fitted through.</p>
 */
public final class AcousticTorpedoes {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredBlock<AcousticTorpedoBlock> ACOUSTIC_TORPEDO = BLOCKS.register("acoustic_torpedo",
            () -> new AcousticTorpedoBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(2.0F, 3.0F)
                    .sound(SoundType.STONE)
                    .noOcclusion()));

    public static final DeferredItem<AcousticTorpedoItem> ACOUSTIC_TORPEDO_ITEM = ITEMS.register("acoustic_torpedo",
            () -> new AcousticTorpedoItem(ACOUSTIC_TORPEDO.get(), new Item.Properties()));

    /**
     * The same round as it hangs on an aircraft rail, wearing the model of
     * 面包学's own visually guided round: same block, same item, same swim, with
     * its own body and its own entity because a rail holds two of them.
     */
    public static final DeferredBlock<AirdroppedAcousticTorpedoBlock> AIRDROPPED_ACOUSTIC_TORPEDO =
            BLOCKS.register("airdropped_acoustic_torpedo",
                    () -> new AirdroppedAcousticTorpedoBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_GRAY)
                            .strength(2.0F, 3.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final DeferredItem<AcousticTorpedoItem> AIRDROPPED_ACOUSTIC_TORPEDO_ITEM =
            ITEMS.register("airdropped_acoustic_torpedo",
                    () -> new AcousticTorpedoItem(AIRDROPPED_ACOUSTIC_TORPEDO.get(), new Item.Properties()));

    public static final DeferredHolder<EntityType<?>, EntityType<AcousticTorpedoProjectile>> ACOUSTIC_TORPEDO_ENTITY =
            ENTITY_TYPES.register("acoustic_torpedo",
                    () -> EntityType.Builder.<AcousticTorpedoProjectile>of(AcousticTorpedoProjectile::new, MobCategory.MISC)
                            .sized(0.8F, 1.6F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("acoustic_torpedo"));

    public static final DeferredHolder<EntityType<?>, EntityType<AirdroppedAcousticTorpedoProjectile>>
            AIRDROPPED_ACOUSTIC_TORPEDO_ENTITY = ENTITY_TYPES.register("airdropped_acoustic_torpedo",
                    () -> EntityType.Builder
                            .<AirdroppedAcousticTorpedoProjectile>of(AirdroppedAcousticTorpedoProjectile::new, MobCategory.MISC)
                            .sized(0.8F, 1.6F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("airdropped_acoustic_torpedo"));

    private AcousticTorpedoes() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITY_TYPES.register(bus);
        bus.addListener(AcousticTorpedoes::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ACOUSTIC_TORPEDO_ENTITY.get(), CannonTorpedoProjectileRenderer::new);
        event.registerEntityRenderer(AIRDROPPED_ACOUSTIC_TORPEDO_ENTITY.get(), CannonTorpedoProjectileRenderer::new);
    }

    /**
     * Lets the round join CBC's fuzed block entity type, the same way the EMP
     * shell does: without it a placed round throws and no fuze could be fitted.
     */
    public static void initialize() {
        BlockEntityType<FuzedBlockEntity> type = CBCBlockEntities.FUZED_BLOCK.get();
        Set<Block> validBlocks = new HashSet<>(type.getValidBlocks());
        boolean added = validBlocks.add(ACOUSTIC_TORPEDO.get());
        added |= validBlocks.add(AIRDROPPED_ACOUSTIC_TORPEDO.get());
        if (!added) {
            return;
        }
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
     * Ties the round to the data pack entry in
     * {@code munition_properties/projectiles}, read through CBCMS's own torpedo
     * handler: that is where the swim - buoyancy, swimming speed, spread, run
     * time - is described, and it is also the entry the torpedo tube sizes its
     * loading and its launch from.
     */
    public static void registerProjectileHandlers() {
        MunitionPropertiesHandler.registerProjectileHandler(ACOUSTIC_TORPEDO_ENTITY.get(),
                CBCMSMunitionPropertiesHandlers.TORPEDO_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(AIRDROPPED_ACOUSTIC_TORPEDO_ENTITY.get(),
                CBCMSMunitionPropertiesHandlers.TORPEDO_PROJECTILE);
    }
}
