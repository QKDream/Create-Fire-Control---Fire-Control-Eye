package com.qkdream.firecontrolcompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Block entity of the Neva launcher. Holds the loaded missile count in its
 * persistent data and acts as the GeckoLib animatable for the block renderer.
 */
public class NevaLauncherBlockEntity extends BlockEntity implements GeoAnimatable {

    private final AnimatableInstanceCache geckolibCache = GeckoLibUtil.createInstanceCache(this);

    public NevaLauncherBlockEntity(BlockPos pos, BlockState state) {
        super(NevaLauncher.NEVA_LAUNCHER_BE.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Static missile model; no block animations.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geckolibCache;
    }

    @Override
    public double getTick(Object animatable) {
        return this.level == null ? 0.0 : this.level.getGameTime();
    }
}
