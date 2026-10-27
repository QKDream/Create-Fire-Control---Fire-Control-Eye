package com.qkdream.firecontrolcompat.mixin;

import com.cainiao1053.cbcmoreshells.munitions.big_cannon.AbstractCannonTorpedoProjectile;
import com.qkdream.firecontrolcompat.SonicPulse;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the sculk network hear a CBCMS torpedo swim.
 *
 * <p>The launch already goes out through the shared cannon projectile hook;
 * what is left is the part underneath the surface. Every torpedo in that mod
 * is a subclass of this projectile, so its tick covers the whole family, and
 * the pulse only goes out while the round is actually in the water.</p>
 */
@Mixin(AbstractCannonTorpedoProjectile.class)
public abstract class CbcmsTorpedoSonicMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$torpedoNoise(CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (self.tickCount == 0) {
                return;
            }
            SonicPulse.torpedo(self);
        } catch (Throwable ignored) {
        }
    }
}
