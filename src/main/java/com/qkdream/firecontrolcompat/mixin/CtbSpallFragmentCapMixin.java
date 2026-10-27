package com.qkdream.firecontrolcompat.mixin;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.qkdream.firecontrolcompat.TrafficConeSpall;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Raises the fragment cap while the traffic cone goes through a wall.
 *
 * <p>The pack caps how many fragments one impact may throw, which is a sane
 * guard for every other round. The cone asks for far more than the default cap
 * can hand out, so the cap is lifted to a floor for that one impact and left
 * exactly as configured for everything else - a player who raised the cap gets
 * the higher number.</p>
 */
@Mixin(TBImpactService.class)
public abstract class CtbSpallFragmentCapMixin {

    @Unique
    private static boolean firecontrolcompat$coneImpact;

    @Inject(method = "spawnSpall", at = @At("HEAD"))
    private static void firecontrolcompat$markConeImpact(
            ServerLevel level,
            Entity projectile,
            Vec3 origin,
            Vec3 dir,
            double velMag,
            TBCaliber caliber,
            double massBefore,
            double massAfter,
            MaterialStats material,
            double armorToughness,
            CallbackInfoReturnable<Integer> cir) {
        firecontrolcompat$coneImpact = TrafficConeSpall.isTrafficCone(projectile);
    }

    @Redirect(
            method = "spawnSpall",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/ModConfigSpec$IntValue;get()Ljava/lang/Object;"))
    private static Object firecontrolcompat$coneFragmentCap(ModConfigSpec.IntValue configured) {
        Object value = configured.get();
        if (!firecontrolcompat$coneImpact) {
            return value;
        }
        int cap = value instanceof Integer number ? number : 0;
        return Math.max(cap, TrafficConeSpall.SPALL_FRAGMENT_FLOOR);
    }
}