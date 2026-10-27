package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.GuidedMissileMode;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.missile.MathGuidedMissiles;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets this mod's own rounds take part in fire control's mathematical flight.
 *
 * <p>{@code GuidedMissileMode.eligible(Entity)} is the pack's own answer to "may this round be flown
 * as a record instead of an entity", and it lists only the pack's families: its high speed
 * anti-air round, whatever {@code MianbaoAircraftCompat.typeOf} recognises as a non-optical guided
 * weapon, and its cruise missiles. This mod's rounds - the infrared round, the heavy anti-air round,
 * the super-heavy one and the infrared anti-tank round - are none of those, and neither is the glide
 * bomb this mod releases off 面包学's own racks, so {@code MathematicalMissileManager.capture} would
 * never take them however the launch is marked.</p>
 *
 * <p>The answer is widened here rather than by giving the rounds a fake pack family: a fake family
 * would also hand them that family's flight profile, its fuse and its damage, none of which is
 * theirs. {@link MathGuidedMissiles#isMathEligible} is the single place that says which rounds this
 * covers, and the same test is what the join hook uses to mark them adopted, so eligibility and
 * adoption can never disagree about which rounds are in.</p>
 *
 * <p><b>Bombs are widened only for the ones this mod released.</b> {@code MathGuidedMissiles}
 * answers {@code true} for a 面包学 bomb only after it has adopted it, and it adopts one only when
 * this mod's own rack release threw it. The pack already flies its own bombs mathematically through
 * {@code MianbaoBombProjectileHandler}; a wider answer would let both drives claim the same bomb.</p>
 *
 * <p>{@code require = 0}, and a throw leaves the pack's own answer untouched: an update that moves
 * {@code eligible} costs this mod the mathematical mode and nothing else.</p>
 */
@Mixin(GuidedMissileMode.class)
public abstract class GuidedMissileModeEligibleMixin {

    @Inject(method = "eligible", at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$eligible(Entity missile, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (MathGuidedMissiles.isMathEligible(missile)) {
                cir.setReturnValue(Boolean.TRUE);
            }
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("math-guided: eligibility hook skipped: {}", failure.toString());
        }
    }
}
