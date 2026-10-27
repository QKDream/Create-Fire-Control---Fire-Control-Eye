package com.qkdream.firecontrolcompat;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The Neva (S-125) missile as a directly placeable block item: placing it
 * stands the missile on the ground, the fire control computer binds that
 * block like a launcher and firing it launches the missile itself. The
 * Blockbench model also renders as the inventory icon through the
 * registered {@code GeoItemRenderer}, like the other missile icons.
 */
public class NevaMissileBlockItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache geckolibCache = GeckoLibUtil.createInstanceCache(this);

    public NevaMissileBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        for (int index = 0; index < 8; index++) {
            String key = "item.firecontrolcompat.neva_missile.description_" + index;
            MutableComponent line = Component.translatable(key);
            if (key.equals(line.getString())) {
                break;
            }
            tooltip.add(line.withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Static model; the icon does not play animations.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geckolibCache;
    }
}
