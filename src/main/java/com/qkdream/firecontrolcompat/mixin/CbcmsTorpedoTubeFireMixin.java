package com.qkdream.firecontrolcompat.mixin;

import com.cainiao1053.cbcmoreshells.cannon_control.contraption.MountedTorpedoTubeContraption;
import com.qkdream.firecontrolcompat.underwater.TorpedoDesignation;
import com.qkdream.firecontrolcompat.underwater.VisualCarrierMark;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Reads the aiming computer at the head of a torpedo tube's shot, so the round
 * that shot builds can be laid on the hull that computer has locked. The mark is
 * dropped again at the end of the shot, whether the shot went off or not, so a
 * later round out of an unwired tube is not given somebody else's target.
 *
 * <p>Optional: without it the tube still fires, and the wire simply searches the
 * water on its own the way it did before.</p>
 */
@Mixin(MountedTorpedoTubeContraption.class)
public abstract class CbcmsTorpedoTubeFireMixin {

    @Inject(method = "fireShot", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$layTheWire(
            ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) {
        try {
            TorpedoDesignation.arm(level, entity);
            VisualCarrierMark.arm(entity.getControllingPassenger());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "fireShot", at = @At("RETURN"), require = 0)
    private void firecontrolcompat$dropTheWire(
            ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) {
        TorpedoDesignation.clear();
        VisualCarrierMark.clear();
    }
}
