package com.qkdream.firecontrolcompat.underwater;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** The decoy round: a grenade body loaded with a noise maker, and the two lines saying so. */
public class TorpedoDecoyItem extends Item {

    public TorpedoDecoyItem() {
        super(new Item.Properties().stacksTo(16));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.firecontrolcompat.torpedo_decoy.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.firecontrolcompat.torpedo_decoy.lure").withStyle(ChatFormatting.DARK_GRAY));
    }
}
