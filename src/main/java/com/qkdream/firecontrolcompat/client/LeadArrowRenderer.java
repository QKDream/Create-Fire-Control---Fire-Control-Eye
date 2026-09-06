package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Shared lead indicator drawing for the fire control HUD.
 *
 * <p>The arrow starts at the lock frame centre and ends at the predicted
 * intercept point solved against the target's motion with a nominal muzzle
 * speed, so the tip is where the shot should be aimed. The tip is drawn as a
 * small circle. The radar lock and the optical lock both feed this renderer;
 * the caller decides which one is shown to avoid duplicates when both locks
 * are active.
 */
public final class LeadArrowRenderer {

    public static final double MUZZLE_SPEED = 6.0D;

    private static final double MAX_FLIGHT_TICKS = 40.0D;

    private static final double FALLBACK_FLIGHT_TICKS = 2.0D;

    private static final double MAX_SCREEN_LENGTH_BASE = 240.0D;

    private static final double MAX_SCREEN_LENGTH_HEIGHT_FACTOR = 0.55D;

    private static final double MIN_SCREEN_LENGTH = 3.0D;

    private static final double FALLBACK_SCREEN_LENGTH_BASE = 14.0D;

    private static final double FALLBACK_SCREEN_LENGTH_HEIGHT_FACTOR = 0.05D;

    private static final int TIP_RADIUS = 4;

    /** Last entity resolved as the velocity source, reused for a few ticks. */
    private static Entity cachedVelocityEntity;

    private static Vec3 cachedVelocityPoint = Vec3.ZERO;

    private static long cachedScanTick;

    private static boolean cachedScanValid;

    /** Optical lock point history used to derive the target's real velocity. */
    private static Vec3 previousLockPoint;

    private static long previousLockPointNanos;

    private static Vec3 smoothedLockVelocity;

    /** Last radar contact id whose estimated velocity was smoothed. */
    private static UUID smoothedContactId;

    private static Vec3 smoothedContactVelocity;

    private LeadArrowRenderer() {
    }

    /**
     * Draws the lead arrow for a moving target whose current world position
     * and velocity are known. The line starts at the given screen point (the
     * lock frame centre) and ends at the projected intercept point. When the
     * intercept cannot be solved or projected, the arrow still appears in
     * the screen direction of the target's relative motion.
     */
    public static void draw(
            GuiGraphics graphics, int originX, int originY,
            Minecraft minecraft, float partialTicks,
            Vec3 targetPos, Vec3 targetVelocity) {
        if (!FireControlLeadSettings.gunLeadEnabled()) {
            return;
        }
        Vec3 launchPos = launchPosition(minecraft);
        Vec3 own = ClientRadarState.isActive() ? ClientRadarState.getOwnVelocity() : null;
        Vec3 relative = own == null ? targetVelocity : targetVelocity.subtract(own);
        if (relative.lengthSqr() < 1.0E-6D) {
            return;
        }
        double flightTicks = solveFlightTicks(targetPos, relative, launchPos);
        if (flightTicks <= 0.0D) {
            return;
        }
        Vec3 intercept = targetPos.add(targetVelocity.scale(flightTicks));
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int[] to = project(minecraft, intercept, width, height, partialTicks);
        if (to == null) {
            double[] direction = screenDirection(minecraft, relative);
            if (direction == null) {
                return;
            }
            double fallbackLength = Math.max(
                    FALLBACK_SCREEN_LENGTH_BASE, height * FALLBACK_SCREEN_LENGTH_HEIGHT_FACTOR);
            int endX = originX + (int) Math.round(direction[0] * fallbackLength);
            int endY = originY + (int) Math.round(direction[1] * fallbackLength);
            drawSegment(graphics, originX, originY, endX, endY);
            drawCircle(graphics, endX, endY, TIP_RADIUS);
            return;
        }
        double offsetX = to[0] - originX;
        double offsetY = to[1] - originY;
        double length = Math.sqrt(offsetX * offsetX + offsetY * offsetY);
        if (length < 1.0E-6D) {
            return;
        }
        double maxLength = Math.max(MAX_SCREEN_LENGTH_BASE, height * MAX_SCREEN_LENGTH_HEIGHT_FACTOR);
        double capped = Math.min(Math.max(length, MIN_SCREEN_LENGTH), maxLength);
        int endX = originX + (int) Math.round(offsetX / length * capped);
        int endY = originY + (int) Math.round(offsetY / length * capped);
        drawSegment(graphics, originX, originY, endX, endY);
        drawCircle(graphics, endX, endY, TIP_RADIUS);
    }

    /** Projects a world position to screen coordinates, or null when it lies behind the camera. */
    public static int[] projectWorldPoint(Minecraft minecraft, Vec3 position, int width, int height, float partialTicks) {
        return project(minecraft, position, width, height, partialTicks);
    }

    /**
     * Solves the flight time to intercept: {@code |toTarget + relative * t| =
     * muzzleSpeed * t}. Targets faster than the muzzle speed fall back to a
     * nominal time-to-range so the arrow keeps pointing along the target's
     * future path.
     */
    private static double solveFlightTicks(Vec3 targetPos, Vec3 relative, Vec3 launchPos) {
        Vec3 toTarget = targetPos.subtract(launchPos);
        double speedSqr = MUZZLE_SPEED * MUZZLE_SPEED;
        double a = relative.lengthSqr() - speedSqr;
        double b = 2.0D * relative.dot(toTarget);
        double c = toTarget.lengthSqr();
        double flightTicks = 0.0D;
        if (a < -1.0E-9D) {
            double discriminant = b * b - 4.0D * a * c;
            if (discriminant >= 0.0D) {
                double sqrtDiscriminant = Math.sqrt(discriminant);
                double first = (-b - sqrtDiscriminant) / (2.0D * a);
                double second = (-b + sqrtDiscriminant) / (2.0D * a);
                flightTicks = first > 0.0D ? first : second;
            }
        }
        if (flightTicks <= 0.0D) {
            flightTicks = Math.min(Math.sqrt(c) / MUZZLE_SPEED, FALLBACK_FLIGHT_TICKS);
        }
        return Math.min(Math.max(flightTicks, 0.25D), MAX_FLIGHT_TICKS);
    }

    /**
     * Velocity of the moving target under an optical lock point. The lock
     * point follows the target surface every frame, so its own history is the
     * most accurate, lag-free and cheapest velocity source and never
     * alternates with other estimates. When the history is fresh, it is
     * returned directly; only on the first frame of a lock (or after a lock
     * jump) the radar contact nearest to the point, and finally a cached
     * moving-entity scan, are used as fallbacks.
     */
    public static Vec3 velocityFor(Vec3 point) {
        if (point == null) {
            return null;
        }
        Vec3 historyVelocity = historyVelocity(point);
        if (historyVelocity != null) {
            return historyVelocity;
        }
        Vec3 contactVelocity = contactVelocityNear(point);
        if (contactVelocity != null) {
            return contactVelocity;
        }
        return entityVelocityNear(point);
    }

    /**
     * Smooths a radar-estimated contact velocity per contact id so the radar
     * lock lead arrow does not jitter or flicker between sweeps. The first
     * sample for a new id is accepted as-is.
     */
    public static Vec3 stabilizedContactVelocity(UUID id, Vec3 raw) {
        if (raw == null) {
            return null;
        }
        if (id == null) {
            return raw;
        }
        if (!id.equals(smoothedContactId)) {
            smoothedContactId = id;
            smoothedContactVelocity = raw;
            return raw;
        }
        if (smoothedContactVelocity == null) {
            smoothedContactVelocity = raw;
            return raw;
        }
        smoothedContactVelocity =
                smoothedContactVelocity.add(raw.subtract(smoothedContactVelocity).scale(0.4D));
        return smoothedContactVelocity;
    }

    /**
     * Derives the target velocity from consecutive optical lock points.
     * Returns null while the history is missing, stale, or when the point
     * jumped to a different target, in which case the smoothing restarts.
     */
    private static Vec3 historyVelocity(Vec3 point) {
        long nowNanos = System.nanoTime();
        Vec3 result = null;
        if (previousLockPoint != null && previousLockPointNanos > 0L) {
            double dtTicks = (nowNanos - previousLockPointNanos) / 1.0E9 / 0.05D;
            if (dtTicks > 1.0E-6D && dtTicks < 20.0D) {
                Vec3 velocity = point.subtract(previousLockPoint).scale(1.0D / dtTicks);
                if (velocity.lengthSqr() <= 3600.0D) {
                    smoothedLockVelocity = smoothedLockVelocity == null
                            ? velocity
                            : smoothedLockVelocity.add(
                                    velocity.subtract(smoothedLockVelocity).scale(0.4D));
                    result = smoothedLockVelocity;
                } else {
                    smoothedLockVelocity = null;
                }
            } else {
                smoothedLockVelocity = null;
            }
        }
        previousLockPoint = point;
        previousLockPointNanos = nowNanos;
        return result;
    }

    /** Nearest radar contact around the point, containment preferred. */
    private static Vec3 contactVelocityNear(Vec3 point) {
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts == null) {
            return null;
        }
        ClientRadarState.Contact best = null;
        boolean bestContained = false;
        double bestCenterDistanceSqr = Double.MAX_VALUE;
        for (ClientRadarState.Contact contact : contacts) {
            Vec3 velocity = contact.velocity();
            if (velocity == null) {
                continue;
            }
            boolean contained =
                    contact.bounds() != null && contact.bounds().inflate(4.0D).contains(point);
            double centerDistanceSqr = contact.center().distanceToSqr(point);
            if (!contained && centerDistanceSqr > 576.0D) {
                continue;
            }
            boolean better =
                    contained && !bestContained
                            || contained == bestContained
                                    && centerDistanceSqr < bestCenterDistanceSqr;
            if (best == null || better) {
                best = contact;
                bestContained = contained;
                bestCenterDistanceSqr = centerDistanceSqr;
            }
        }
        if (best == null) {
            return null;
        }
        return stabilizedContactVelocity(best.id(), best.velocity());
    }

    /** Moving entity nearest to the point, resolved by a throttled scan. */
    private static Vec3 entityVelocityNear(Vec3 point) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        long now = minecraft.level.getGameTime();
        if (now < cachedScanTick) {
            cachedScanValid = false;
            cachedVelocityEntity = null;
        }
        if (cachedScanValid && now - cachedScanTick <= 3L) {
            if (cachedVelocityEntity == null) {
                return null;
            }
            if (cachedVelocityEntity.isAlive()
                    && !cachedVelocityEntity.isRemoved()
                    && point.distanceToSqr(cachedVelocityPoint) <= 225.0D) {
                Vec3 velocity = cachedVelocityEntity.getDeltaMovement();
                if (velocity != null && velocity.lengthSqr() >= 1.0E-6D) {
                    return velocity;
                }
            }
        }
        Entity bestEntity = null;
        Vec3 bestVelocity = null;
        double bestDistanceSqr = 576.0D;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            Vec3 velocity = entity.getDeltaMovement();
            if (velocity == null || velocity.lengthSqr() < 1.0E-6D) {
                continue;
            }
            AABB bounds = entity.getBoundingBox();
            double centerX = (bounds.minX + bounds.maxX) * 0.5D;
            double centerY = (bounds.minY + bounds.maxY) * 0.5D;
            double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;
            double offsetX = Math.max(0.0D, Math.abs(point.x - centerX) - bounds.getXsize() * 0.5D - 2.0D);
            double offsetY = Math.max(0.0D, Math.abs(point.y - centerY) - bounds.getYsize() * 0.5D - 2.0D);
            double offsetZ = Math.max(0.0D, Math.abs(point.z - centerZ) - bounds.getZsize() * 0.5D - 2.0D);
            double distanceSqr = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                bestVelocity = velocity;
                bestEntity = entity;
            }
        }
        cachedVelocityEntity = bestEntity;
        cachedVelocityPoint = point;
        cachedScanTick = now;
        cachedScanValid = true;
        return bestVelocity;
    }

    private static Vec3 launchPosition(Minecraft minecraft) {
        Vec3 origin = ClientRadarState.isActive() ? ClientRadarState.getOrigin() : null;
        return origin == null ? minecraft.gameRenderer.getMainCamera().getPosition() : origin;
    }

    private static double[] screenDirection(Minecraft minecraft, Vec3 relative) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vec3 leftVector = new Vec3(left.x, left.y, left.z);
        Vec3 upVector = new Vec3(up.x, up.y, up.z);
        double screenX = -relative.dot(leftVector);
        double screenY = -relative.dot(upVector);
        double length = Math.sqrt(screenX * screenX + screenY * screenY);
        if (length < 1.0E-6D) {
            return null;
        }
        return new double[] {screenX / length, screenY / length};
    }

    private static int[] project(
            Minecraft minecraft, Vec3 position, int width, int height, float partialTicks) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 offset = position.subtract(camera.getPosition());
        Vector3f look = camera.getLookVector();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vec3 lookVector = new Vec3(look.x, look.y, look.z);
        Vec3 leftVector = new Vec3(left.x, left.y, left.z);
        Vec3 upVector = new Vec3(up.x, up.y, up.z);
        double forward = offset.dot(lookVector);
        if (forward <= 0.05D) {
            return null;
        }
        double fov = ((GameRendererAccessor) minecraft.gameRenderer).stabilizedTurret$getFov(
                camera, partialTicks, true);
        double scale = (height * 0.5D) / Math.tan(0.01745329238474369D * fov * 0.5D);
        int x = width / 2 + (int) Math.round(-offset.dot(leftVector) * scale / forward);
        int y = height / 2 + (int) Math.round(-offset.dot(upVector) * scale / forward);
        return new int[] {x, y};
    }

    private static void drawSegment(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps == 0) {
            graphics.fill(x1, y1, x1 + 1, y1 + 1, LockLabelText.LOCK_GREEN);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int px = x1 + (int) Math.round((x2 - x1) * (i / (double) steps));
            int py = y1 + (int) Math.round((y2 - y1) * (i / (double) steps));
            graphics.fill(px, py, px + 1, py + 1, LockLabelText.LOCK_GREEN);
            graphics.fill(px + 1, py, px + 2, py + 1, LockLabelText.LOCK_GREEN);
        }
    }

    private static void drawCircle(GuiGraphics graphics, int x, int y, int radius) {
        int decision = 1 - radius;
        int deltaX = 1;
        int deltaY = -2 * radius;
        int px = 0;
        int py = radius;
        plot(graphics, x, y + radius);
        plot(graphics, x, y - radius);
        plot(graphics, x + radius, y);
        plot(graphics, x - radius, y);
        while (px < py) {
            if (decision >= 0) {
                py--;
                deltaY += 2;
                decision += deltaY;
            }
            px++;
            deltaX += 2;
            decision += deltaX;
            plot(graphics, x + px, y + py);
            plot(graphics, x - px, y + py);
            plot(graphics, x + px, y - py);
            plot(graphics, x - px, y - py);
            plot(graphics, x + py, y + px);
            plot(graphics, x - py, y + px);
            plot(graphics, x + py, y - px);
            plot(graphics, x - py, y - px);
        }
    }

    private static void plot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 1, y + 1, LockLabelText.LOCK_GREEN);
    }
}
