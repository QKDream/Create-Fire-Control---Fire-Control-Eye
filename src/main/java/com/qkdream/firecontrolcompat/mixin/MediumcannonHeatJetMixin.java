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
 * Gives CBC Modern Warfare's medium cannon HEAT round a molten jet of this mod's
 * making, on top of the burst its own author wrote.
 *
 * <p>The pack's round already throws a cone of its own heat fragments at the
 * point of detonation. What it does not do is set anything behind that point
 * alight, or send a single fast dart down the nose the way a shaped charge
 * does; that is what {@code MetalJetProjectile} adds, and it is attached here so
 * the round that breaks the armour is the round that starts the fire.</p>
 *
 * <p>The hook is on the round's own {@code detonate}, so a HEAT round with no
 * fuze fitted simply does not go off and therefore makes no jet - the warhead
 * stays a warhead. The call is guarded inside {@link MetalJetTrigger}, so a round
 * that detonates twice, or the gun launched missile that extends this class,
 * still throws exactly one jet.</p>
 */
@Mixin(targets = "riftyboi.cbcmodernwarfare.munitions.medium_cannon.heap.HEAPMediumcannonProjectile")
public abstract class MediumcannonHeatJetMixin {

    @Inject(method = "detonate", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$throwMetalJet(Position position, CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            Vec3 at = position == null ? null : new Vec3(position.x(), position.y(), position.z());
            MetalJetTrigger.throwJet(self, self.level(), at);
        } catch (Throwable ignored) {
            // A missing jet must never stop the round's own blast.
        }
    }
}
