package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.AcceleratingMissileIntercept;
import com.hooya.stabilizedturret.content.controller.BeamRidingGuidance;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Turn authority of fire control's beam-riding round.
 *
 * <p>0.9.4 moved {@code guide} out of {@code OrdinaryAirDefenseGuidance} into
 * {@code BeamRidingGuidance}, so the redirect this mod used to hang on the old
 * owner matched nothing at all and the official four degrees per tick stayed in
 * place - the log line for it never printed. The hook now lives on the class
 * that actually flies the round.</p>
 *
 * <p>{@code BeamRidingGuidance.guide} turns the velocity by at most its static
 * {@code MAX_TURN_RADIANS} (four degrees), read with a GETSTATIC. A GETSTATIC
 * anchor is not a legal injection point specifier - it aborts mixin preparation
 * and with it the whole mod - so the cap is raised at the call it feeds instead:
 * the single {@code AcceleratingMissileIntercept.rotateTowards(current, desired,
 * maxRadians)} in that method, whose {@code maxRadians} argument is exactly that
 * field.</p>
 *
 * <p>The doubled speed needs a doubled angular rate to keep the turn
 * <em>radius</em> the round used to have (17.015 blocks/tick at 4 degrees and
 * 34.0 at 8 degrees both turn in about 244 blocks). The pack's own value is kept
 * when it is already wider, so nothing is ever narrowed.</p>
 */
@Mixin(BeamRidingGuidance.class)
public abstract class BeamRidingGuidanceMixin {

    /** Per-tick heading change, in degrees. Official is 4 at 17.015 blocks/tick. */
    private static final double FIRE_CONTROLCOMPAT_TURN_DEGREES = 8.0;

    private static boolean firecontrolcompat$reported;

    @Redirect(
            method = "guide",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/AcceleratingMissileIntercept;rotateTowards(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;D)Lnet/minecraft/world/phys/Vec3;"
            ),
            require = 0
    )
    private static Vec3 firecontrolcompat$turnLimit(Vec3 current, Vec3 desired, double maxRadians) {
        try {
            double wanted = Math.toRadians(FIRE_CONTROLCOMPAT_TURN_DEGREES);
            double limit = Math.max(maxRadians, wanted);
            if (!firecontrolcompat$reported) {
                firecontrolcompat$reported = true;
                FireControlCompat.LOGGER.info(
                        "beam turn limit applied: {} deg/tick via BeamRidingGuidance.guide (official 4.0, speed doubled)",
                        String.format(Locale.ROOT, "%.2f", Math.toDegrees(limit)));
            }
            return AcceleratingMissileIntercept.rotateTowards(current, desired, limit);
        } catch (Throwable ignored) {
            return AcceleratingMissileIntercept.rotateTowards(current, desired, maxRadians);
        }
    }
}
