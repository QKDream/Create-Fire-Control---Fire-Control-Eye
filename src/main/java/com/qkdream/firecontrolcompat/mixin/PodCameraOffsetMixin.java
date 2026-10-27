package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.PodCameraOffset;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shifts the pod's camera by the amount written in the config, if any.
 *
 * <p>With no line in the config this does nothing at all: the camera stays
 * exactly where fire control puts it.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class PodCameraOffsetMixin {

    @Inject(method = "getPodDesiredWorldDirection", at = @At("RETURN"), cancellable = true, require = 0)
    private void firecontrolcompat$turnPodView(CallbackInfoReturnable<Vec3> cir) {
        if (!PodCameraOffset.flips()
                || !com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()
                || !com.qkdream.firecontrolcompat.PodLinkSwitch.flipOnState()) {
            // Turning the pack's own direction is opt-in: it fixed the angle but
            // left every other entry showing the previous visit's view, because the
            // pack caches that direction and puts it back on entering again.
            return;
        }
        // Only while the pod view is the one being used. The pack reads this same
        // direction when it puts the gunner's view back on leaving the pod and
        // when it decides the view has come back; a direction turned all the time
        // never matches, which is what left the view stuck at one angle - or in
        // the pod at all.
        if (!((com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity) (Object) this)
                .isPodViewEnabled()) {
            return;
        }
        try {
            Vec3 turned = PodCameraOffset.flip(cir.getReturnValue());
            if (turned != null) {
                cir.setReturnValue(turned);
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    @Inject(method = "getPodCameraPosition", at = @At("RETURN"), cancellable = true, require = 0)
    private void firecontrolcompat$shiftPodCamera(CallbackInfoReturnable<Vec3> cir) {
        if (!PodCameraOffset.active()
                || !com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()) {
            return;
        }
        try {
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            // The half turn first - it is what puts the camera back on the lens
            // side - and then the fine shift, if one is configured.
            Vec3 shifted = PodCameraOffset.apply(
                    PodCameraOffset.spin(cir.getReturnValue(), self.getLevel(), self.getBlockPos()),
                    self.getBlockState());
            if (shifted != null) {
                cir.setReturnValue(shifted);
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}
