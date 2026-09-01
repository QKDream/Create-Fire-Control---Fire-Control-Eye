package com.qkdream.firecontrolcompat;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Missile item that renders the {@code description_N} lang entries of its
 * registry key as gray tooltip lines, keeping every description translatable.
 */
public final class FireControlMissileItem extends Item {

    private final String descriptionPrefix;

    public FireControlMissileItem(String itemKey) {
        super(new Item.Properties().stacksTo(6));
        this.descriptionPrefix = itemKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        for (int index = 0; index < 8; index++) {
            String key = this.descriptionPrefix + ".description_" + index;
            MutableComponent line = Component.translatable(key);
            if (key.equals(line.getString())) {
                break;
            }
            tooltip.add(line.withStyle(ChatFormatting.GRAY));
        }
    }
}
