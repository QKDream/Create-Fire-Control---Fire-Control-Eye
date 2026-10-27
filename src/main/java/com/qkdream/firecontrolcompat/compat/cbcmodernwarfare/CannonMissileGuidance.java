package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonMountCompat;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.SubLevelPositions;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Beam riding guidance for the gun launched missile. The shell is kept on the
 * fire control computer aim beam, which is exactly what fire control does for
 * the Mianbao anti tank missile, so the constants here mirror
 * {@code MianbaoMissileGuidanceHandler}.
 */
public final class CannonMissileGuidance {

    /** Ticks the shell flies free before the beam takes over, so it clears the muzzle. */
    private static final int MIN_AGE_TICKS = 3;
    private static final double CROSS_TRACK_GAIN = 0.08;
    private static final double MAX_LATERAL_SPEED_RATIO = 0.22;
    private static final double MAX_STEER_RADIANS_PER_TICK = Math.toRadians(4.0);
    private static final double MAX_LAUNCH_DISTANCE_SQR = 1024.0;

    private CannonMissileGuidance() {
    }

    /**
     * Finds the fire control computer whose bound cannon mount sits closest to
     * the shell, so a shell fired by a bound medium cannon can pick up its own
     * guidance beam without any extra player action.
     */
    public static StabilizerControllerBlockEntity findController(Level level, Vec3 shellWorldPosition) {
        if (level == null || shellWorldPosition == null || level.isClientSide) {
            return null;
        }
        StabilizerControllerBlockEntity best = null;
        double bestDistance = MAX_LAUNCH_DISTANCE_SQR;
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            if (controller == null || controller.isRemoved()) {
                continue;
            }
            Level controllerLevel = controller.getLevel();
            if (controllerLevel == null || controllerLevel.getServer() != level.getServer()
                    || !controllerLevel.dimension().equals(level.dimension())) {
                continue;
            }
            for (BindingRef mount : controller.getCannonMountBindings()) {
                if (!CannonMountCompat.hasAssembledCannon(controllerLevel, mount)) {
                    continue;
                }
                Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(controllerLevel, mount);
                if (muzzle == null) {
                    continue;
                }
                double distance = muzzle.distanceToSqr(shellWorldPosition);
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = controller;
                }
            }
        }
        return best;
    }

    /**
     * Velocity that keeps the shell on the aim beam, or {@code null} while the
     * computer cannot offer a beam yet.
     */
    public static Vec3 guidedVelocity(AbstractCannonProjectile shell, StabilizerControllerBlockEntity controller,
            int ageTicks, Vec3 shellWorldPosition) {
        if (controller == null || controller.isRemoved() || ageTicks < MIN_AGE_TICKS) {
            return null;
        }
        Vec3 origin = controller.getMeasuredAimOrigin();
        Vec3 direction = controller.getMeasuredAimDirection();
        if (origin == null || direction == null || direction.lengthSqr() < 1.0E-8) {
            return null;
        }
        Vec3 beam = direction.normalize();
        Vec3 current = shell.getDeltaMovement();
        double speed = current.length();
        if (speed < 1.0E-4) {
            speed = 1.0;
        }

        double alongBeam = Math.max(0.0, shellWorldPosition.subtract(origin).dot(beam));
        Vec3 closestOnBeam = origin.add(beam.scale(alongBeam));
        Vec3 crossTrack = closestOnBeam.subtract(shellWorldPosition);
        double crossTrackDistance = crossTrack.length();
        double lateralSpeed = Math.min(speed * MAX_LATERAL_SPEED_RATIO, crossTrackDistance * CROSS_TRACK_GAIN);
        Vec3 desired = beam.scale(speed);
        if (crossTrackDistance > 1.0E-6) {
            desired = desired.add(crossTrack.scale(lateralSpeed / crossTrackDistance));
        }
        Vec3 desiredDirection = desired.normalize();
        Vec3 currentDirection = current.lengthSqr() < 1.0E-8 ? beam : current.normalize();
        return rotateTowards(currentDirection, desiredDirection, MAX_STEER_RADIANS_PER_TICK).scale(speed);
    }

    /** World position of the shell, projected out of its Sable sub-level. */
    public static Vec3 worldPosition(Level level, Vec3 position) {
        return level == null ? position : SubLevelPositions.worldPosition(level, position);
    }

    private static Vec3 rotateTowards(Vec3 current, Vec3 desired, double maxRadians) {
        double dot = Math.max(-1.0, Math.min(1.0, current.dot(desired)));
        double angle = Math.acos(dot);
        if (angle <= maxRadians || angle < 1.0E-6) {
            return desired;
        }
        double blend = maxRadians / angle;
        Vec3 blended = current.scale(1.0 - blend).add(desired.scale(blend));
        return blended.lengthSqr() < 1.0E-8 ? current : blended.normalize();
    }
}