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
 * Descriptions for this mod's medium cannon rounds.
 *
 * <p>One place rather than a method on each of the nine items: the rounds, their
 * cartridges and the submunition all share a name in their registry path, so a single
 * pass over the tooltip covers them and a round added later is described the moment it
 * follows the same naming.</p>
 *
 * <p>The traffic cone round carries a warning in purple. It is built on the discarding
 * sabot shell and punches a road cone through armour; it is a joke round, and the warning
 * says so - it is meant for a creative-mode world of one's own, and firing it at other
 * players tends to arrange a fight away from the keyboard.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class MediumcannonRoundTooltips {

    /** Colour of the traffic cone's warning. */
    private static final ChatFormatting WARNING_COLOUR = ChatFormatting.LIGHT_PURPLE;

    private MediumcannonRoundTooltips() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!FireControlCompat.MOD_ID.equals(id.getNamespace())) {
            return;
        }
        String path = id.getPath();
        if (path.equals("sfm_submunition")) {
            add(event, "tooltip.firecontrolcompat.sfm_submunition", null);
            return;
        }
        if (!path.contains("mediumcannon")) {
            return;
        }
        if (path.startsWith("traffic_cone")) {
            add(event, "tooltip.firecontrolcompat.traffic_cone_round",
                    new String[] {
                            "tooltip.firecontrolcompat.traffic_cone_warn_creative",
                            "tooltip.firecontrolcompat.traffic_cone_warn_pvp"
                    });
        } else if (path.startsWith("atgm")) {
            add(event, "tooltip.firecontrolcompat.atgm_round", null);
        } else if (path.startsWith("sfm")) {
            add(event, "tooltip.firecontrolcompat.sfm_round", null);
        } else if (path.startsWith("sap")) {
            add(event, "tooltip.firecontrolcompat.sap_round", null);
        }
    }

    /** Writes the description, then any warnings in the warning colour. */
    private static void add(ItemTooltipEvent event, String descriptionKey, String[] warningKeys) {
        event.getToolTip().add(Component.translatable(descriptionKey).withStyle(ChatFormatting.GRAY));
        if (warningKeys == null) {
            return;
        }
        for (String key : warningKeys) {
            event.getToolTip().add(Component.translatable(key).withStyle(WARNING_COLOUR));
        }
    }
}
