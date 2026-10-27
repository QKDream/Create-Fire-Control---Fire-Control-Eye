package com.qkdream.firecontrolcompat;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Block item of the Neva launcher that also renders the missile model as its
 * inventory icon through a registered {@code GeoItemRenderer}.
 */
public class NevaLauncherBlockItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache geckolibCache = GeckoLibUtil.createInstanceCache(this);

    public NevaLauncherBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Static icon; no animations.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geckolibCache;
    }
}
