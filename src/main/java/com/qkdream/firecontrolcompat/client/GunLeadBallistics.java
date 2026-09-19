package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.network.GunBallisticsPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Firing solution behind the cannon lead indicator.
 *
 * <p>The indicator used to assume the shell flies straight, which made the
 * arrow short at range because real shells drop. Now that the bound cannon's
 * ballistic profile reaches the client ({@link GunBallisticsPayload}), the
 * solution is handed to the fire control mod's own solver so the arrow points
 * exactly where the mod's automatic aim would put the barrel: it accounts for
 * the muzzle speed, the shell's gravity and drag, and the platform's own
 * motion.</p>
 *
 * <p>The observer's eye (or the radar origin, while a radar session is up) is
 * used as the launch point instead of the barrel, because the arrow is a sight
 * correction: pointing the reticle at the returned world position means the
 * bore points along the required elevation.</p>
 *
 * <p>Everything is wrapped defensively: if the mod's ballistics API is not
 * available or refuses the shot, the caller falls back to the old
 * straight-line indicator.</p>
 */
public final class GunLeadBallistics {

    /** Hard cap of the solved flight, in ticks. */
    private static final int MAX_FLIGHT_TICKS = 400;

    private GunLeadBallistics() {
    }

    /**
     * World-space point the reticle has to sit on so the shell lands on the
     * locked target, or null when no profile is available or the solution
     * cannot be found (for example when the target is out of reach for the
     * loaded charge).
     */
    public static Vec3 aimPoint(Vec3 launch, Vec3 targetPos, Vec3 targetVelocity) {
        if (launch == null || targetPos == null || targetVelocity == null
                || !GunBallisticsPayload.isUsable()) {
            return null;
        }
        double speed = GunBallisticsPayload.speed();
        double gravity = GunBallisticsPayload.drop();
        double drag = GunBallisticsPayload.airDrag();
        if (!Double.isFinite(speed) || speed <= 1.0E-4D
                || !Double.isFinite(gravity) || !Double.isFinite(drag) || drag < 0.0D) {
            return null;
        }
        Vec3 displacement = targetPos.subtract(launch);
        double range = displacement.length();
        if (!(range > 1.0E-3D)) {
            return null;
        }
        Vec3 carrier = GunBallisticsPayload.platformVelocity();
        if (carrier == null || !firecontrolcompat$isFinite(carrier)) {
            carrier = Vec3.ZERO;
        }
        int budget = Math.max(1, Math.min(MAX_FLIGHT_TICKS, GunBallisticsPayload.flightTickLimit()));
        int maxTicks = (int) Math.min(
                budget, Math.max(20.0D, Math.ceil(range / speed * 3.0D) + 20.0D));
        maxTicks = Math.max(1, maxTicks);
        try {
            CannonBallisticsCompat.ShotProfile profile = new CannonBallisticsCompat.ShotProfile(
                    launch,
                    displacement.normalize(),
                    carrier,
                    speed,
                    gravity,
                    drag,
                    GunBallisticsPayload.squaredDrag(),
                    "",
                    "",
                    0,
                    0,
                    maxTicks,
                    0.0D,
                    0.0D,
                    0.0D);
            CannonBallisticsCompat.MovingTargetSolution solution =
                    CannonBallisticsCompat.solveMovingTarget(profile, targetPos, targetVelocity);
            if (solution == null) {
                return null;
            }
            Vec3 direction = solution.launchDirection();
            Vec3 predicted = solution.target();
            if (direction == null || predicted == null
                    || !firecontrolcompat$isFinite(direction) || !firecontrolcompat$isFinite(predicted)) {
                return null;
            }
            double distance = predicted.distanceTo(launch);
            if (!(distance > 1.0E-3D) || direction.lengthSqr() < 1.0E-10D) {
                return null;
            }
            Vec3 result = launch.add(direction.normalize().scale(distance));
            return firecontrolcompat$isFinite(result) ? result : null;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.debug(
                    "[firecontrolcompat] ballistic lead unavailable: {}", failure.toString());
            return null;
        }
    }

    private static boolean firecontrolcompat$isFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}