package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.AcceleratingMissileIntercept;
import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance;
import com.qkdream.firecontrolcompat.FireControlCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives fire control's own beam-riding round the flight envelope this mod used
 * to fly its launcher-family missiles at.
 *
 * <p>0.9.4 keeps the ordinary (beam-riding) anti-air round in
 * {@code OrdinaryAirDefenseGuidance}: it owns only the man-portable launcher's
 * missile ({@code mianbaos_modernwarfare:portableantiairmissiletanshe}) carrying
 * the {@code CreateFireControlOrdinaryAaGuided} mark, and steers it with
 * {@code BeamRidingGuidance.guide}. Nothing else in the pack reads these three
 * numbers, so the change cannot reach another missile family.</p>
 *
 * <p>Official envelope to this mod's old one, item by item:</p>
 * <ul>
 *   <li>top speed {@code 17.015} -> {@code 34.0} blocks/tick (2x, Mach 2),</li>
 *   <li>acceleration window {@code 200} -> {@code 100} ticks (5 s, like our own),</li>
 *   <li>lifetime {@code 400} -> {@code 600} ticks,</li>
 *   <li>turn limit {@code 4 deg} -> {@code 8 deg} per tick, which keeps the turn
 *       <em>radius</em> the round had before the speed was doubled (the angular
 *       rate has to scale with the speed or the faster round circles wider than
 *       the slow one did).</li>
 * </ul>
 *
 * <p>Every hook is {@code require = 0} and swallows its own failures: an update
 * that moves these members leaves the official numbers in place instead of
 * taking the missile out of the sky.</p>
 */
@Mixin(OrdinaryAirDefenseGuidance.class)
public abstract class OrdinaryAaPhysicsMixin {

    /** Top speed of our own beam rider, in blocks per tick (Mach 2). */
    private static final double FIRE_CONTROLCOMPAT_MAX_SPEED = 34.0;
    /** Ticks from launch to top speed: five seconds. */
    private static final int FIRE_CONTROLCOMPAT_ACCELERATION_TICKS = 100;
    /** Flight time after which the round gives up. */
    private static final int FIRE_CONTROLCOMPAT_LIFETIME_TICKS = 600;

    @Inject(method = "speedProfile", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$profile(double launchSpeed,
            CallbackInfoReturnable<AcceleratingMissileIntercept.SpeedProfile> cir) {
        try {
            AcceleratingMissileIntercept.SpeedProfile original = cir.getReturnValue();
            double initial = original != null ? original.initialSpeed() : launchSpeed;
            if (!Double.isFinite(initial)) {
                initial = 2.0;
            }
            initial = Math.max(0.1, Math.min(FIRE_CONTROLCOMPAT_MAX_SPEED, initial));
            AcceleratingMissileIntercept.SpeedProfile profile =
                    new AcceleratingMissileIntercept.SpeedProfile(
                            initial, FIRE_CONTROLCOMPAT_MAX_SPEED, FIRE_CONTROLCOMPAT_ACCELERATION_TICKS);
            cir.setReturnValue(profile);
            FireControlCompat.LOGGER.info(
                    "beam-velocity profile applied: initial={} accel={} max={}",
                    String.format(java.util.Locale.ROOT, "%.3f", initial),
                    FIRE_CONTROLCOMPAT_ACCELERATION_TICKS,
                    String.format(java.util.Locale.ROOT, "%.3f", FIRE_CONTROLCOMPAT_MAX_SPEED));
        } catch (Throwable ignored) {
            // Keep the pack's own profile.
        }
    }

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 400), require = 0)
    private static int firecontrolcompat$lifetime(int original) {
        return FIRE_CONTROLCOMPAT_LIFETIME_TICKS;
    }

    /*
     * Turn authority now lives in {@link BeamRidingGuidanceMixin}: 0.9.4 moved
     * {@code guide} from this class to {@code BeamRidingGuidance}, and a redirect
     * on the old owner matched nothing, so the lock stayed at the official four
     * degrees per tick. The envelope below is unchanged.
     */
}
