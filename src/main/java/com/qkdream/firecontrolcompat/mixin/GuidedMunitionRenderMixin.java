package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.FireControlMunitions;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a round fire control is flying in sight for the whole of its flight.
 *
 * <p>An entity leaves the frame once it is past its own render distance, which
 * for a missile is a few dozen blocks, so a guided round disappears shortly
 * after the launch and is only seen again when it goes off. Fire control lifts
 * its own missiles over that check; this does the same for every round
 * {@link FireControlMunitions} knows about, so the round and the trail behind
 * it run unbroken from the launcher to the target.</p>
 *
 * <p>This class is only ever applied on the client: a dedicated server has no
 * entity render dispatcher at all.</p>
 */
@Mixin(value = EntityRenderDispatcher.class, priority = 1500)
public abstract class GuidedMunitionRenderMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$keepGuidedMunitionVisible(Entity entity, Frustum frustum,
            double cameraX, double cameraY, double cameraZ, CallbackInfoReturnable<Boolean> cir) {
        if (com.qkdream.firecontrolcompat.NativeMissileMode.mathematical(entity)) {
            // Fire control 0.9.3 flies this round by its own flight record; adding our copy
            // would draw the same shot twice and let it drift out of step.
            return;
        }

        if (FireControlMunitions.isTakenOver(entity)) {
            cir.setReturnValue(true);
        }
    }
}
