package com.qkdream.firecontrolcompat;

import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Missile item whose Blockbench model also renders as the inventory icon
 * through a registered {@code GeoItemRenderer}. The renderer needs the item
 * itself to be the animatable, so this subclass carries an empty animation
 * controller set and the usual GeckoLib instance cache.
 */
public class GeoMissileIconItem extends FireControlMissileItem implements GeoItem {

    private final AnimatableInstanceCache geckolibCache = GeckoLibUtil.createInstanceCache(this);

    public GeoMissileIconItem(String itemKey) {
        super(itemKey);
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
