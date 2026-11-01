package com.qkdream.firecontrolcompat.mixin;

import com.cbc_terminal_ballistics.ballistics.ProjectileClassifier;
import com.qkdream.firecontrolcompat.MetalJetSpall;
import com.qkdream.firecontrolcompat.TrafficConeSpall;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the traffic cone round a fragment profile of its own, and the shaped-charge jet a count
 * that matches what a shaped charge actually throws.
 *
 * <p>The classifier reads the entity id and the class name of the projectile,
 * so the cone - which is a discarding sabot round under the hood, but carries
 * neither marker in its name - fell through to the high explosive branch and
 * spawned no fragments. These three hooks answer for that round only, so every
 * other shell keeps the classification the pack gave it.</p>
 *
 * <p>The metal jet does not fall through to the wrong branch - it is classified as APDS, which is
 * what it is built on - but the APDS profile is a sabot's, and a shaped charge throws far more
 * spall than a sabot of the same mass. Its three answers live beside the cone's and are read from
 * {@link MetalJetSpall}, so the two rounds are the only ones this class speaks for.</p>
 */
@Mixin(ProjectileClassifier.class)
public abstract class CtbSpallClassifierMixin {

    @Inject(method = "shellSpallCountModifier", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$coneFragmentCount(Entity projectile, CallbackInfoReturnable<Double> cir) {
        if (TrafficConeSpall.isTrafficCone(projectile)) {
            cir.setReturnValue(TrafficConeSpall.SPALL_COUNT_MODIFIER);
            return;
        }
        if (MetalJetSpall.isMetalJet(projectile)) {
            cir.setReturnValue(MetalJetSpall.SPALL_COUNT_MODIFIER);
        }
    }

    @Inject(method = "shellSpallDamageModifier", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$coneFragmentDamage(Entity projectile, CallbackInfoReturnable<Double> cir) {
        if (TrafficConeSpall.isTrafficCone(projectile)) {
            cir.setReturnValue(TrafficConeSpall.SPALL_DAMAGE_MODIFIER);
            return;
        }
        if (MetalJetSpall.isMetalJet(projectile)) {
            cir.setReturnValue(MetalJetSpall.SPALL_DAMAGE_MODIFIER);
        }
    }

    @Inject(method = "shellSpallConeModifier", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$coneFragmentSpread(Entity projectile, CallbackInfoReturnable<Double> cir) {
        if (TrafficConeSpall.isTrafficCone(projectile)) {
            cir.setReturnValue(TrafficConeSpall.SPALL_CONE_MODIFIER);
            return;
        }
        if (MetalJetSpall.isMetalJet(projectile)) {
            cir.setReturnValue(MetalJetSpall.SPALL_CONE_MODIFIER);
        }
    }
}
