package com.qkdream.firecontrolcompat.underwater;

import com.cainiao1053.cbcmoreshells.munitions.big_cannon.FuzedTorpedoProjectileBlockItem;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.level.block.Block;

/** The torpedo item: CBCMS's own torpedo round, plus a line on what the swim does. */
public class AcousticTorpedoItem extends FuzedTorpedoProjectileBlockItem {

    public AcousticTorpedoItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.firecontrolcompat.acoustic_torpedo.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.firecontrolcompat.acoustic_torpedo.swim").withStyle(ChatFormatting.DARK_GRAY));
    }
}
