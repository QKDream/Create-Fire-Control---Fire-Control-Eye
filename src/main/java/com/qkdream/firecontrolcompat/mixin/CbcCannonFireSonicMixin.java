package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.SonicPulse;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Lets the sculk network hear every cannon shot.
 *
 * <p>Every CBC round, from an autocannon shell to a big cannon shell, is a
 * subclass of this projectile, so the start of its flight is the shot itself;
 * one vibration there covers the whole family without touching the guns
 * themselves. It is filed once per round, keyed by the round, because the
 * autocannon payload bridge announces its own shot at the muzzle at the same
 * instant and the two reports have to come out as the one bang.</p>
 *
 * <p>Which tick the round is first seen on cannot be trusted - a round ticked
 * once by whatever launched it, or spawned straight into a plot, arrives with
 * a tick already on the clock - so a window of the flight is taken instead and
 * the once-per-round filing sorts out the rest.</p>
 */
@Mixin(AbstractCannonProjectile.class)
public abstract class CbcCannonFireSonicMixin {

    /** Ticks at the start of the flight that still count as the shot itself. */
    private static final int FIRST_MOMENTS_TICKS = 4;

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$gunfire(CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (self.tickCount > FIRST_MOMENTS_TICKS) {
                return;
            }
            Level level = self.level();
            if (level == null || level.isClientSide()) {
                return;
            }
            SonicPulse.gunfireOnce(level, self.position(), self.getUUID());
        } catch (Throwable ignored) {
        }
    }
}
