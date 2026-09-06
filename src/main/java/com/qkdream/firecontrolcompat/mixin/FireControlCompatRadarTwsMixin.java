package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.client.ClientTelevisionGuidanceState;
import com.hooya.stabilizedturret.client.RadarHudRenderer;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.qkdream.firecontrolcompat.client.LeadArrowRenderer;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TWS (track-while-scan) presentation for the aircraft radar world HUD, plus
 * a lead-direction arrow at the centre of the ground radar lock frame.
 *
 * <p>Vanilla {@code renderWorldFrames} only draws a single corner frame in
 * aircraft mode: the selected/locked/reticle-nearest contact, and when that
 * contact disappears no frames are drawn at all. This mixin keeps a corner
 * frame on every visible contact after the vanilla pass and wraps the
 * actively boxed contact in a connected square with the same centre and
 * half-size as its corner frame, so locking a target never hides the other
 * tracks and the lock ring sits exactly on the existing frame.
 *
 * <p>For the ground radar the locked contact already keeps its connected
 * square; this mixin additionally draws the lead indicator at the square's
 * centre unless the optical lock owns the indicator already, so the gunner
 * can read the required lead.
 */
@Mixin(RadarHudRenderer.class)
public abstract class FireControlCompatRadarTwsMixin {

    @Invoker("selectedAircraftContact")
    private static ClientRadarState.Contact firecontrolcompat$invokeSelectedAircraftContact(
            Minecraft minecraft, float partialTicks) {
        throw new AbstractMethodError();
    }

    @Invoker("drawCornerSquare")
    private static void firecontrolcompat$invokeDrawCornerSquare(GuiGraphics graphics, int x, int y, int half) {
        throw new AbstractMethodError();
    }

    @Invoker("drawConnectedSquare")
    private static void firecontrolcompat$invokeDrawConnectedSquare(GuiGraphics graphics, int x, int y, int half) {
        throw new AbstractMethodError();
    }

    @Invoker("renderWorldFrames")
    private static void firecontrolcompat$invokeRenderWorldFrames(
            GuiGraphics graphics, Minecraft minecraft, float partialTicks) {
        throw new AbstractMethodError();
    }

    /**
     * Vanilla only calls {@code renderWorldFrames} when the aircraft radar is
     * active, the player is scoped in or the camera is not first person. That
     * means the ground radar TWS frames disappear as soon as the player leaves
     * the scope. When the radar HUD is otherwise active but vanilla skips the
     * world frames, draw them anyway so the tracks stay visible.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private static void firecontrolcompat$worldFramesOutsideScope(
            RenderGuiEvent.Post event, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientRadarState.isActive()
                || ClientAimState.isPodView()
                || ClientTelevisionGuidanceState.isActive()
                || minecraft.options.hideGui
                || minecraft.screen != null) {
            return;
        }
        if (ClientRadarState.isAircraftMode()
                || ClientAimState.isScoped()
                || minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            return;
        }
        firecontrolcompat$invokeRenderWorldFrames(
                event.getGuiGraphics(), minecraft, event.getPartialTick().getGameTimeDeltaTicks());
    }

    @Inject(method = "renderWorldFrames", at = @At("RETURN"))
    private static void firecontrolcompat$twsWorldFrames(
            GuiGraphics graphics, Minecraft minecraft, float partialTicks, CallbackInfo ci) {
        if (ClientRadarState.isAircraftMode()) {
            firecontrolcompat$aircraftFrames(graphics, minecraft, partialTicks);
        } else {
            firecontrolcompat$groundLeadArrow(graphics, minecraft, partialTicks);
        }
    }

    @Unique
    private static void firecontrolcompat$aircraftFrames(
            GuiGraphics graphics, Minecraft minecraft, float partialTicks) {
        ClientRadarState.Contact boxed = firecontrolcompat$invokeSelectedAircraftContact(minecraft, partialTicks);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts == null) {
            return;
        }
        for (ClientRadarState.Contact contact : contacts) {
            int[] point = firecontrolcompat$project(minecraft, contact.center(), width, height, partialTicks);
            if (point == null) {
                continue;
            }
            AABB bounds = contact.bounds();
            int[] size = bounds == null ? null : firecontrolcompat$projectBounds(minecraft, bounds, height, partialTicks);
            if (size == null) {
                continue;
            }
            int half = Mth.clamp((Math.max(size[0], size[1]) + 1) / 2 + 2, 7, 220);
            if (boxed != null && contact.id().equals(boxed.id())) {
                firecontrolcompat$invokeDrawConnectedSquare(graphics, point[0], point[1], half);
            } else {
                firecontrolcompat$invokeDrawCornerSquare(graphics, point[0], point[1], half);
            }
        }
    }

    @Unique
    private static void firecontrolcompat$groundLeadArrow(
            GuiGraphics graphics, Minecraft minecraft, float partialTicks) {
        if (ClientAimState.isOpticalLockActive()) {
            return;
        }
        UUID locked = ClientRadarState.getLockedTarget();
        if (locked == null) {
            return;
        }
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts == null) {
            return;
        }
        ClientRadarState.Contact lockedContact = null;
        for (ClientRadarState.Contact contact : contacts) {
            if (contact.id().equals(locked)) {
                lockedContact = contact;
                break;
            }
        }
        if (lockedContact == null) {
            return;
        }
        int[] origin = firecontrolcompat$project(
                minecraft, lockedContact.center(), graphics.guiWidth(), graphics.guiHeight(), partialTicks);
        if (origin == null) {
            return;
        }
        Vec3 leadVelocity =
                LeadArrowRenderer.stabilizedContactVelocity(
                        lockedContact.id(), lockedContact.velocity());
        if (leadVelocity == null) {
            return;
        }
        LeadArrowRenderer.draw(
                graphics, origin[0], origin[1], minecraft, partialTicks,
                lockedContact.center(), leadVelocity);
    }

    @Unique
    private static int[] firecontrolcompat$project(
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
        double fov = ((GameRendererAccessor) minecraft.gameRenderer).stabilizedTurret$getFov(camera, partialTicks, true);
        double scale = (height * 0.5D) / Math.tan(0.01745329238474369D * fov * 0.5D);
        int x = width / 2 + (int) Math.round(-offset.dot(leftVector) * scale / forward);
        int y = height / 2 + (int) Math.round(-offset.dot(upVector) * scale / forward);
        return new int[] {x, y};
    }

    @Unique
    private static int[] firecontrolcompat$projectBounds(
            Minecraft minecraft, AABB bounds, int height, float partialTicks) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vector3f look = camera.getLookVector();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vec3 lookVector = new Vec3(look.x, look.y, look.z);
        Vec3 leftVector = new Vec3(left.x, left.y, left.z);
        Vec3 upVector = new Vec3(up.x, up.y, up.z);
        double fov = ((GameRendererAccessor) minecraft.gameRenderer).stabilizedTurret$getFov(camera, partialTicks, true);
        double scale = (height * 0.5D) / Math.tan(0.01745329238474369D * fov * 0.5D);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int count = 0;
        double[] xs = {bounds.minX, bounds.maxX};
        double[] ys = {bounds.minY, bounds.maxY};
        double[] zs = {bounds.minZ, bounds.maxZ};
        for (double cornerX : xs) {
            for (double cornerY : ys) {
                for (double cornerZ : zs) {
                    Vec3 offset = new Vec3(cornerX, cornerY, cornerZ).subtract(camera.getPosition());
                    double forward = offset.dot(lookVector);
                    if (forward <= 0.05D) {
                        continue;
                    }
                    double sx = -offset.dot(leftVector) * scale / forward;
                    double sy = -offset.dot(upVector) * scale / forward;
                    minX = Math.min(minX, sx);
                    maxX = Math.max(maxX, sx);
                    minY = Math.min(minY, sy);
                    maxY = Math.max(maxY, sy);
                    count++;
                }
            }
        }
        if (count == 0) {
            return null;
        }
        return new int[] {(int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY)};
    }
}
