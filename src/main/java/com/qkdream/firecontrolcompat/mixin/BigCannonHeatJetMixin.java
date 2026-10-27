package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.MetalJetTrigger;

import net.minecraft.core.Position;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same molten jet for CBC Modern Warfare's big cannon HEAT shell.
 *
 * <p>A big cannon shaped charge is the round a shaped charge is really for, so
 * it throws the jet too, from its own detonation and nowhere else: without a
 * fuze the shell is a solid lump of high explosive that never goes off, and this
 * mod does not change that.</p>
 */
@Mixin(targets = "riftyboi.cbcmodernwarfare.munitions.big_cannon.heap_shell.HEAPShellProjectile")
public abstract class BigCannonHeatJetMixin {

    @Inject(method = "detonate", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$throwMetalJet(Position position, CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            Vec3 at = position == null ? null : new Vec3(position.x(), position.y(), position.z());
            MetalJetTrigger.throwJet(self, self.level(), at);
        } catch (Throwable ignored) {
            // A missing jet must never stop the shell's own blast.
        }
    }
}
