package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.SonicPulse;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.verr1.gearwork.content.track.TrackMountBlockEntity;
import com.verr1.gearwork.content.wheel.WheelMountBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the sculk network hear gearwork's wheels and tracks roll.
 *
 * <p>Both mounts are kinetic block entities and do their work in
 * {@code serverTick}, so a shaft that is actually turning is the same thing as
 * running gear. A stationary mount keeps its speed at zero and stays quiet.</p>
 */
@Mixin({WheelMountBlockEntity.class, TrackMountBlockEntity.class})
public abstract class GearworkRunningGearMixin {

    @Inject(method = "serverTick", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$runningGear(CallbackInfo ci) {
        try {
            Object self = this;
            if (self instanceof KineticBlockEntity kinetic
                    && kinetic.getSpeed() != 0.0F
                    && self instanceof BlockEntity blockEntity) {
                SonicPulse.gear(blockEntity);
            }
        } catch (Throwable ignored) {
        }
    }
}
