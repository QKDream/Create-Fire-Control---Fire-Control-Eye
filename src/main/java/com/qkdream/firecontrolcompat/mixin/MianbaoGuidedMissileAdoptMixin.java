package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets Create Fire Control's native beam-riding guidance adopt the
 * firecontrolcompat beam rider: its admission checks are hardcoded to the
 * five Mianbao ATGM entity types, so we widen them to include ours.
 */
@Mixin(MianbaoMissileCompat.class)
public abstract class MianbaoGuidedMissileAdoptMixin {

    @Inject(method = "isGuidedMissile", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$adoptBeamRider(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && entity.getType() == BeamMissileRegistry.BEAMRIDER_TANSHE.get()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isGuidedMissileType", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$adoptBeamRiderType(EntityType<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && type == BeamMissileRegistry.BEAMRIDER_TANSHE.get()) {
            cir.setReturnValue(true);
        }
    }
}
