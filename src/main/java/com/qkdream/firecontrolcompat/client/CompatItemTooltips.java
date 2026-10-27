package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Descriptions for the items that carry none of their own.
 *
 * <p>Most of this mod's items are their own class and write their tooltip in
 * {@code appendHoverText} ({@code FireControlMissileItem} reads the
 * {@code description_N} lang entries of its own key, the torpedoes and the
 * silencer tiles do the same thing). The sonar sets are plain {@code BlockItem}s
 * registered through the block registry, so there is no class to put a method
 * on - and a player holding one had a name and nothing else: no statement of
 * what it does and no hint that it has to be wired to a computer first.</p>
 *
 * <p>They are described here instead, from the same lang files, so Chinese and
 * English stay side by side and a future item only needs its keys added.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class CompatItemTooltips {

    private CompatItemTooltips() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ResourceLocation id;
        try {
            id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        } catch (Throwable ignored) {
            return;
        }
        if (id == null || !FireControlCompat.MOD_ID.equals(id.getNamespace())) {
            return;
        }
        String path = id.getPath();
        if ("sonar".equals(path)) {
            add(event, "tooltip.firecontrolcompat.sonar", "tooltip.firecontrolcompat.sonar_hint");
        } else if ("manportable_sonar".equals(path)) {
            add(event, "tooltip.firecontrolcompat.manportable_sonar",
                    "tooltip.firecontrolcompat.manportable_sonar_hint");
        }
    }

    /** One grey purpose line and one darker "how to use it" line. */
    private static void add(ItemTooltipEvent event, String purposeKey, String hintKey) {
        event.getToolTip().add(Component.translatable(purposeKey).withStyle(ChatFormatting.GRAY));
        if (hintKey != null) {
            event.getToolTip().add(Component.translatable(hintKey).withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
