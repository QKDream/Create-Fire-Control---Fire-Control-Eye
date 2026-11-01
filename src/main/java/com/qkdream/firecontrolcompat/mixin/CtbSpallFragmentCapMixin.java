package com.qkdream.firecontrolcompat.mixin;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.MetalJetSpall;
import com.qkdream.firecontrolcompat.TrafficConeSpall;
import java.util.Locale;
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
 * Raises the fragment cap while the traffic cone or the shaped-charge jet goes through a wall.
 *
 * <p>The pack caps how many fragments one impact may throw, which is a sane
 * guard for every other round. The cone asks for far more than the default cap
 * can hand out, so the cap is lifted to a floor for that one impact and left
 * exactly as configured for everything else - a player who raised the cap gets
 * the higher number.</p>
 *
 * <p>The metal jet needs the same lift for a different reason: its fragment count is doubled (see
 * {@link MetalJetSpall}), and a doubled count against a cap of 28 would come out as exactly the
 * number it produced before. Its lift is bounded on both sides - a per-impact ceiling and a per-tick
 * budget shared by every jet in the world - so the extra fragments can never turn a salvo into a
 * lag bomb. Every jet impact then says how many it threw:</p>
 *
 * <pre>jet-spall: fragments=&lt;n&gt; cap=&lt;n&gt; modifier=&lt;x&gt; budget=&lt;spent&gt;/&lt;total&gt;</pre>
 */
@Mixin(TBImpactService.class)
public abstract class CtbSpallFragmentCapMixin {

    @Unique
    private static boolean firecontrolcompat$coneImpact;

    /** Whether the impact being measured is the shaped-charge jet. */
    @Unique
    private static boolean firecontrolcompat$jetImpact;

    /** Game time of the impact being measured, so the fragment budget is spent per tick. */
    @Unique
    private static long firecontrolcompat$spallTick;

    /** Cap this jet impact was handed, for the log line on the way out. */
    @Unique
    private static int firecontrolcompat$jetCap;

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
        firecontrolcompat$jetImpact = MetalJetSpall.isMetalJet(projectile);
        firecontrolcompat$jetCap = 0;
        firecontrolcompat$spallTick = level == null ? 0L : level.getGameTime();
    }

    @Redirect(
            method = "spawnSpall",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/ModConfigSpec$IntValue;get()Ljava/lang/Object;"))
    private static Object firecontrolcompat$coneFragmentCap(ModConfigSpec.IntValue configured) {
        Object value = configured.get();
        if (!firecontrolcompat$coneImpact && !firecontrolcompat$jetImpact) {
            return value;
        }
        int cap = value instanceof Integer number ? number : 0;
        if (firecontrolcompat$jetImpact) {
            int effective = MetalJetSpall.capFor(cap, firecontrolcompat$spallTick);
            firecontrolcompat$jetCap = effective;
            return effective;
        }
        return Math.max(cap, TrafficConeSpall.SPALL_FRAGMENT_FLOOR);
    }

    /**
     * One line per jet impact: how many fragments the hit threw and what cap it was allowed.
     *
     * <p>Thrown fragments are entities, so this is the number a crew report is graded on and the
     * number the budget is checked against. Only the jet speaks here; every other impact in the pack
     * stays silent.</p>
     */
    @Inject(method = "spawnSpall", at = @At("RETURN"))
    private static void firecontrolcompat$reportJetSpall(
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
        if (!firecontrolcompat$jetImpact) {
            return;
        }
        try {
            Integer thrown = cir.getReturnValue();
            FireControlCompat.LOGGER.info(
                    "{}jet-spall: fragments={} cap={} modifier={} budget={}/{} speed={} mass={}",
                    "[firecontrolcompat] ", thrown == null ? 0 : thrown, firecontrolcompat$jetCap,
                    String.format(Locale.ROOT, "%.2f", MetalJetSpall.SPALL_COUNT_MODIFIER),
                    MetalJetSpall.budgetSpent(), MetalJetSpall.TICK_FRAGMENT_BUDGET,
                    String.format(Locale.ROOT, "%.1f", velMag),
                    String.format(Locale.ROOT, "%.1f", massAfter));
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }
}
