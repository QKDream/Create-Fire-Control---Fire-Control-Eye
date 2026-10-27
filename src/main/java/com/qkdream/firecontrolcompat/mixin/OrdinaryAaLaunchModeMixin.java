package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.AcceleratingMissileIntercept;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamLaunchModeStore;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the fire control computer pick how its beam-riding round leaves the tube.
 *
 * <p>The mode is filed per computer in {@link BeamLaunchModeStore} (saved with the
 * world, edited on the computer's settings page, mirrored to the client through
 * {@code BeamLaunchModeSyncPayload}). {@code OrdinaryAirDefenseGuidance.adopt} is
 * the moment the pack takes a freshly fired ordinary anti-air round over, and it
 * reads the round's velocity right there to seed its speed profile - so the
 * launch heading is set here, before that read.</p>
 *
 * <ul>
 *   <li><b>HORIZONTAL</b> - the pack's own behaviour: the round flies along the
 *       tube it was fired from.</li>
 *   <li><b>VERTICAL</b> - "vertical launch" is <em>not</em> a straight-up kick
 *       speed (the old {@code (0, 0.3, 0)} here, now removed). It is a
 *       <b>0.05 s (one tick) window after launch in which the round may turn more
 *       than 120 degrees</b>, i.e. it can swing almost instantly onto the beam
 *       and only after that falls back to the ordinary per-tick turn limit
 *       ({@link BeamRidingGuidanceMixin}: 8 deg/tick, official 4).</li>
 * </ul>
 *
 * <p>The window is honoured by doing the one big turn at adoption time: the
 * heading is rotated toward the computer's measured beam with a
 * {@value #FIRE_CONTROLCOMPAT_SNAP_DEGREES} degree allowance - well over the
 * 120 degrees the user asked for - and the speed magnitude is left untouched so
 * the pack's own acceleration profile still starts where it used to. Because
 * {@code OrdinaryAirDefenseGuidance.tick} only starts beam guidance at
 * {@code age >= 3}, the round is already on the beam before the 8 deg/tick cap
 * can ever bite, which is exactly "turn >120 degrees inside the first tick,
 * then normal limits".</p>
 *
 * <p>{@code require = 0} and every failure swallowed: an update that moves
 * {@code adopt} leaves the official launch in place.</p>
 */
@Mixin(OrdinaryAirDefenseGuidance.class)
public abstract class OrdinaryAaLaunchModeMixin {

    /**
     * Heading change the round is allowed to make during its one-tick vertical
     * launch window. The user's floor is 120 degrees; this is rounded up so a
     * round fired from a tube pointing the "wrong" way still lands on the beam
     * in that single tick instead of stopping just short of it.
     */
    private static final double FIRE_CONTROLCOMPAT_SNAP_DEGREES = 180.0;

    /** Length of the steep-turn window, in ticks. 0.05 s of game time. */
    private static final int FIRE_CONTROLCOMPAT_SNAP_WINDOW_TICKS = 1;

    @Inject(method = "adopt", at = @At("HEAD"), require = 0)
    private static void firecontrolcompat$launchMode(Entity missile, StabilizerControllerBlockEntity controller,
            CallbackInfo ci) {
        try {
            if (missile == null) {
                return;
            }
            Level level = missile.level();
            boolean vertical = controller != null
                    ? BeamLaunchModeStore.isVertical(level, controller)
                    : FireControlLeadSettings.beamVerticalLaunch();
            if (!vertical) {
                FireControlCompat.LOGGER.info(
                        "beam launch mode=HORIZONTAL applied to native missile id={} controller={} speed={}",
                        missile.getId(), controller == null ? "-" : controller.getBlockPos(),
                        String.format(Locale.ROOT, "%.3f",
                                MianbaoMissileCompat.getWorldVelocity(missile).length()));
                return;
            }
            firecontrolcompat$snapToBeam(missile, controller);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("beam launch mode hook skipped: {}", failure.toString());
        }
    }

    /**
     * One-shot large-angle turn at launch. No vertical velocity is added: the
     * round keeps the speed the tube gave it and only its direction moves.
     */
    private static void firecontrolcompat$snapToBeam(Entity missile, StabilizerControllerBlockEntity controller) {
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
        double speed = velocity.length();
        Vec3 beam = controller == null ? null : controller.getMeasuredAimDirection();
        if (beam == null || beam.lengthSqr() < 1.0E-8) {
            // Nothing to snap onto: keep the tube heading rather than inventing
            // a straight-up kick speed.
            FireControlCompat.LOGGER.info(
                    "beam launch mode=VERTICAL snap=skipped reason=no-beam window={}tick missile={}",
                    FIRE_CONTROLCOMPAT_SNAP_WINDOW_TICKS, missile.getId());
            return;
        }
        Vec3 to = beam.normalize();
        Vec3 from = speed > 1.0E-8 ? velocity.scale(1.0 / speed) : to;
        double asked = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, from.dot(to)))));
        Vec3 turned = AcceleratingMissileIntercept.rotateTowards(from, to,
                Math.toRadians(FIRE_CONTROLCOMPAT_SNAP_DEGREES));
        double kept = speed > 1.0E-3 ? speed : 2.0;
        MianbaoMissileCompat.setWorldVelocity(missile, turned.scale(kept));
        FireControlCompat.LOGGER.info(
                "beam launch mode=VERTICAL snap=120deg window={}tick missile={} controller={} turn={}deg kept_speed={}",
                FIRE_CONTROLCOMPAT_SNAP_WINDOW_TICKS, missile.getId(),
                controller == null ? "-" : controller.getBlockPos(),
                String.format(Locale.ROOT, "%.1f", asked),
                String.format(Locale.ROOT, "%.3f", kept));
    }
}
