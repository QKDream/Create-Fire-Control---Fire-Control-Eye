package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.SonicPulse;
import net.mcreator.myfirstmod.entity.LegacyProjectileArrow;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the sculk network hear 面包学's own munitions.
 *
 * <p>Every missile and every torpedo that mod fires is a subclass of this
 * projectile, so its tick is the one place that sees all of them: the first
 * tick is the launch, and every tick after it is either a missile in the air
 * or a torpedo in the water, which {@link SonicPulse#projectile(Entity)}
 * tells apart on its own.</p>
 */
@Mixin(LegacyProjectileArrow.class)
public abstract class MianbaoMissileSonicMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$munitionNoise(CallbackInfo ci) {
        try {
            SonicPulse.projectile((Entity) (Object) this);
        } catch (Throwable ignored) {
        }
    }
}
