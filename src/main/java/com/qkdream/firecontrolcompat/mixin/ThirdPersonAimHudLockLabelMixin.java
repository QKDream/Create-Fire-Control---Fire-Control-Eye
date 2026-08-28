package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.client.ThirdPersonAimHudRenderer;
import com.qkdream.firecontrolcompat.client.LeadArrowRenderer;
import com.qkdream.firecontrolcompat.client.LockLabelText;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws the target category under the first-person lock square when the
 * locked target is the one currently locked by the radar (ground bearing or
 * aircraft radar). Manual optical locks are left unidentified.
 */
@Mixin(ThirdPersonAimHudRenderer.class)
public abstract class ThirdPersonAimHudLockLabelMixin {

    @Invoker("drawConnectedSquare")
    private static void invokeDrawConnectedSquare(GuiGraphics graphics, int x, int y, int half, int color) {
        throw new AbstractMethodError();
    }

    @Redirect(
            method = "renderOpticalMarkers",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/client/ThirdPersonAimHudRenderer;drawConnectedSquare(Lnet/minecraft/client/gui/GuiGraphics;IIII)V"
            )
    )
    private static void firecontrolcompat$lockSquareWithLabel(GuiGraphics graphics, int x, int y, int half, int color) {
        invokeDrawConnectedSquare(graphics, x, y, half, color);
        Minecraft minecraft = Minecraft.getInstance();
        UUID id = firecontrolcompat$radarLockedContactId();
        if (id != null) {
            Component label = LockLabelText.groundLockComponent(id);
            if (label != null) {
                graphics.drawCenteredString(minecraft.font, label, x, y + half + 10, LockLabelText.LOCK_GREEN);
            }
        }
        if (!ClientAimState.isOpticalLockActive()) {
            return;
        }
        Vec3 point = ClientAimState.getOpticalLockDisplayPoint(1.0F);
        if (point == null) {
            return;
        }
        Vec3 velocity = LeadArrowRenderer.velocityFor(point);
        if (velocity == null) {
            return;
        }
        LeadArrowRenderer.draw(
                graphics, x, y, minecraft,
                minecraft.getTimer().getGameTimeDeltaTicks(), point, velocity);
    }

    @Unique
    private static UUID firecontrolcompat$radarLockedContactId() {
        if (!ClientRadarState.isActive() || ClientRadarState.isAircraftMode()) {
            return null;
        }
        if (ClientAimState.isScoped()) {
            return null;
        }
        if (Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON) {
            return null;
        }
        if (!ClientAimState.isOpticalLockActive()) {
            return null;
        }
        UUID lockedId = ClientRadarState.getLockedTarget();
        if (lockedId == null) {
            return null;
        }
        Vec3 point = ClientAimState.getOpticalLockDisplayPoint(1.0F);
        if (point == null) {
            return null;
        }
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts == null || contacts.isEmpty()) {
            return null;
        }
        for (ClientRadarState.Contact contact : contacts) {
            if (!contact.id().equals(lockedId)) {
                continue;
            }
            if (contact.bounds() != null && contact.bounds().inflate(4.0).contains(point)) {
                return lockedId;
            }
            if (contact.center().distanceToSqr(point) <= 144.0) {
                return lockedId;
            }
            return null;
        }
        return null;
    }
}
