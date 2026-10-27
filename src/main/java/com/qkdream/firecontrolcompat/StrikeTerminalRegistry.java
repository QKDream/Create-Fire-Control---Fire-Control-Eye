package com.qkdream.firecontrolcompat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The airborne strike terminal, the plotting board of the aircraft weapon branch. */
public final class StrikeTerminalRegistry {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);

    public static final DeferredItem<Item> STRIKE_TERMINAL = ITEMS.register("strike_terminal",
            () -> new StrikeTerminalItem(new Item.Properties().stacksTo(1)));

    private StrikeTerminalRegistry() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }

    /** The tab the board is shown in, next to the mod's other aircraft equipment. */
    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(STRIKE_TERMINAL.get());
    }

    /** The name shown in the tab and the tooltip. */
    public static Component name() {
        return Component.translatable("item.firecontrolcompat.strike_terminal");
    }
}
