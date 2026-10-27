package com.qkdream.firecontrolcompat.underwater;

import com.cainiao1053.cbcmoreshells.index.CBCMSMunitionPropertiesHandlers;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.CannonTorpedoProjectileRenderer;
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
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBlockEntity;
import rbasamoyai.createbigcannons.munitions.config.MunitionPropertiesHandler;

/**
 * The wire-guided torpedo: a round that is steered down the line it was aimed
 * down rather than hunting on its own ears.
 *
 * <p>It is put together exactly like the acoustic round - a block to lie in the
 * world, an item to load a tube with, a projectile to swim, and the data pack
 * entry that describes the swim - so both CBCMS's torpedo tube and 面包学's
 * throw it without either of them knowing the difference. It carries the same
 * fuze and the same tracer as any other torpedo, and it bursts the same way.</p>
 */
public final class WireGuidedTorpedoes {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredBlock<WireGuidedTorpedoBlock> WIRE_GUIDED_TORPEDO =
            BLOCKS.register("wire_guided_torpedo",
                    () -> new WireGuidedTorpedoBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_GRAY)
                            .strength(2.0F, 3.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final DeferredItem<WireGuidedTorpedoItem> WIRE_GUIDED_TORPEDO_ITEM =
            ITEMS.register("wire_guided_torpedo",
                    () -> new WireGuidedTorpedoItem(WIRE_GUIDED_TORPEDO.get(), new Item.Properties()));

    public static final DeferredHolder<EntityType<?>, EntityType<WireGuidedTorpedoProjectile>> WIRE_GUIDED_TORPEDO_ENTITY =
            ENTITY_TYPES.register("wire_guided_torpedo",
                    () -> EntityType.Builder.<WireGuidedTorpedoProjectile>of(WireGuidedTorpedoProjectile::new, MobCategory.MISC)
                            .sized(0.8F, 1.6F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("wire_guided_torpedo"));

    private WireGuidedTorpedoes() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITY_TYPES.register(bus);
        bus.addListener(WireGuidedTorpedoes::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(WIRE_GUIDED_TORPEDO_ENTITY.get(), CannonTorpedoProjectileRenderer::new);
    }

    /**
     * Lets the round join CBC's fuzed block entity type the same way the
     * acoustic round does, so a fuze can be fitted and the round can be placed.
     */
    public static void initialize() {
        BlockEntityType<FuzedBlockEntity> type = CBCBlockEntities.FUZED_BLOCK.get();
        Set<Block> validBlocks = new HashSet<>(type.getValidBlocks());
        if (!validBlocks.add(WIRE_GUIDED_TORPEDO.get())) {
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
     * Ties the round to its own data pack entry through CBCMS's torpedo handler,
     * which is where a tube reads the loading and the launch from.
     */
    public static void registerProjectileHandlers() {
        MunitionPropertiesHandler.registerProjectileHandler(WIRE_GUIDED_TORPEDO_ENTITY.get(),
                CBCMSMunitionPropertiesHandlers.TORPEDO_PROJECTILE);
    }
}
