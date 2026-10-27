package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.SonicPulse;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same idea for the tracks+ track mount, which ticks like any other kinetic
 * block entity: while the shaft turns, the track is grinding over the ground
 * and the vibration goes out from the mount.</p>
 */
@Mixin(SableTrackBlockEntity.class)
public abstract class TracksRunningGearMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
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
