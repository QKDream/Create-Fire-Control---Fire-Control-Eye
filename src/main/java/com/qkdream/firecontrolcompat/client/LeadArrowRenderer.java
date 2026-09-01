package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
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

    private static final double MAX_FLIGHT_TICKS = 5.0D;

    private static final double FALLBACK_FLIGHT_TICKS = 2.0D;

    private static final double MAX_SCREEN_LENGTH = 160.0D;

    private static final double MIN_SCREEN_LENGTH = 14.0D;

    private static final double FALLBACK_SCREEN_LENGTH = 28.0D;

    private static final int TIP_RADIUS = 4;

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
            int endX = originX + (int) Math.round(direction[0] * FALLBACK_SCREEN_LENGTH);
            int endY = originY + (int) Math.round(direction[1] * FALLBACK_SCREEN_LENGTH);
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
        double capped = Math.min(Math.max(length, MIN_SCREEN_LENGTH), MAX_SCREEN_LENGTH);
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
     * Best-effort velocity of the moving target under an optical lock point:
     * prefers the matching radar contact, then falls back to the moving
     * entity nearest to the point.
     */
    public static Vec3 velocityFor(Vec3 point) {
        if (point == null) {
            return null;
        }
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts != null) {
            for (ClientRadarState.Contact contact : contacts) {
                Vec3 velocity = contact.velocity();
                if (velocity == null || velocity.lengthSqr() < 1.0E-6D) {
                    continue;
                }
                if (contact.bounds() != null && contact.bounds().inflate(4.0D).contains(point)) {
                    return velocity;
                }
                if (contact.center().distanceToSqr(point) <= 400.0D) {
                    return velocity;
                }
            }
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        Vec3 best = null;
        double bestDistanceSqr = 144.0D;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            Vec3 velocity = entity.getDeltaMovement();
            if (velocity == null || velocity.lengthSqr() < 1.0E-6D) {
                continue;
            }
            double distanceSqr = entity.getBoundingBox().inflate(2.0D).distanceToSqr(point);
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                best = velocity;
            }
        }
        return best;
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
