package com.qkdream.firecontrolcompat.crate;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The ammunition store and the loading table.
 *
 * <p>The store is 面包学's own ammunition box (the "caisson" block) rather than
 * a block of this mod: the box is what players already build with, and the
 * block entity type below is what gives it the eight round store. Mods register
 * their blocks before block entity types, so the box can be looked up here
 * while the type is built.</p>
 */
public final class AmmoCrateRegistry {

    /** 面包学's ammunition box. */
    private static final ResourceLocation CRATE_BLOCK =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "caisson");

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredItem<com.qkdream.firecontrolcompat.AmmoClipboardItem> AMMO_LOADING_TABLE =
            ITEMS.register("ammo_loading_table",
                    () -> new com.qkdream.firecontrolcompat.AmmoClipboardItem(
                            new net.minecraft.world.item.Item.Properties().stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AmmoCrateBlockEntity>> AMMO_CRATE_BE =
            BLOCK_ENTITIES.register("ammo_crate", () -> BlockEntityType.Builder
                    .of(AmmoCrateBlockEntity::new, crateBlock())
                    .build(null));

    private AmmoCrateRegistry() {
    }

    /**
     * The ammunition box, or a barrier when 面包学 is not the pack's mod. A
     * barrier never asks for a block entity, so the type stays harmless.
     */
    private static Block crateBlock() {
        Block crate = BuiltInRegistries.BLOCK.get(CRATE_BLOCK);
        if (crate == null || crate == Blocks.AIR) {
            FireControlCompat.LOGGER.warn("[firecontrolcompat] {} is missing: the ammunition store has no block",
                    CRATE_BLOCK);
            return Blocks.BARRIER;
        }
        return crate;
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        bus.addListener(AmmoCrateRegistry::addCreativeTabItems);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        ResourceLocation key = event.getTabKey().location();
        if (key.equals(ResourceLocation.withDefaultNamespace("functional_blocks"))
                || key.equals(ResourceLocation.withDefaultNamespace("tools"))
                || key.equals(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "mianbaos_modern_warfare"))) {
            event.accept(AMMO_LOADING_TABLE.get());
        }
    }
}
