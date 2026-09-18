package com.qkdream.firecontrolcompat.iff;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block, item, block entity and menu registrations for the IFF transponder. */
public final class IffRegistry {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(FireControlCompat.MOD_ID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FireControlCompat.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, FireControlCompat.MOD_ID);

    public static final DeferredBlock<IffTransponderBlock> IFF_TRANSPONDER =
            BLOCKS.register("iff_transponder",
                    () -> new IffTransponderBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.GOLD)
                            .strength(2.0F)
                            .sound(SoundType.METAL)));

    public static final DeferredItem<BlockItem> IFF_TRANSPONDER_ITEM =
            ITEMS.registerSimpleBlockItem("iff_transponder", IFF_TRANSPONDER);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IffTransponderBlockEntity>>
            IFF_TRANSPONDER_BE = BLOCK_ENTITIES.register("iff_transponder",
                    () -> BlockEntityType.Builder.of(
                            IffTransponderBlockEntity::new, IFF_TRANSPONDER.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<IffTransponderMenu>> IFF_MENU =
            MENUS.register("iff_transponder",
                    () -> IMenuTypeExtension.create(IffTransponderMenu::new));

    private IffRegistry() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener(IffRegistry::addCreativeTabItems);
    }

    /** Makes the transponder findable in the Create tab and the vanilla functional tab. */
    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        ResourceLocation key = event.getTabKey().location();
        if (key.equals(ResourceLocation.withDefaultNamespace("functional_blocks"))
                || key.equals(ResourceLocation.fromNamespaceAndPath("create", "base"))
                || key.equals(ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "fire_control_eye"))) {
            event.accept(IFF_TRANSPONDER_ITEM.get());
        }
    }
}