package com.qkdream.firecontrolcompat;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registrations for the Neva super-heavy air-defense missile launcher block,
 * its block entity and the launcher block item.
 */
public final class NevaLauncher {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredBlock<NevaLauncherBlock> NEVA_LAUNCHER = BLOCKS.register("neva_launcher",
            () -> new NevaLauncherBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final DeferredItem<NevaLauncherBlockItem> NEVA_LAUNCHER_ITEM =
            ITEMS.register("neva_launcher",
                    () -> new NevaLauncherBlockItem(NEVA_LAUNCHER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NevaLauncherBlockEntity>> NEVA_LAUNCHER_BE =
            BLOCK_ENTITIES.register("neva_launcher", () -> BlockEntityType.Builder
                    .of(NevaLauncherBlockEntity::new, NEVA_LAUNCHER.get())
                    .build(null));

    private NevaLauncher() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
