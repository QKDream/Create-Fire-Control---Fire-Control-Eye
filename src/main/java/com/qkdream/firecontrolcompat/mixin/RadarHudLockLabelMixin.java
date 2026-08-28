package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.client.RadarHudRenderer;
import com.qkdream.firecontrolcompat.client.LockLabelText;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws the locked/selected target category under the radar lock box in the
 * world HUD: {@code sable结构} for Sable sublevels, {@code 弹药} for
 * Shaolib/entity ordnance and {@code 生物} for living entities.
 *
 * <p>In the ground branch vanilla replaces the searching corner frame of the
 * locked contact with the connected square. This mixin keeps the corner frame
 * and wraps it with the connected square, so the locked target keeps its box
 * and gains the extra TWS lock ring.
 */
@Mixin(RadarHudRenderer.class)
public abstract class RadarHudLockLabelMixin {

    @Invoker("drawConnectedSquare")
    private static void invokeDrawConnectedSquare(GuiGraphics graphics, int x, int y, int half) {
        throw new AbstractMethodError();
    }

    @Invoker("drawCornerSquare")
    private static void invokeDrawCornerSquare(GuiGraphics graphics, int x, int y, int half) {
        throw new AbstractMethodError();
    }

    @Redirect(
            method = "renderWorldFrames",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/client/RadarHudRenderer;drawConnectedSquare(Lnet/minecraft/client/gui/GuiGraphics;III)V"
            )
    )
    private static void firecontrolcompat$connectedSquareWithLabel(GuiGraphics graphics, int x, int y, int half) {
        invokeDrawCornerSquare(graphics, x, y, half);
        invokeDrawConnectedSquare(graphics, x, y, half + 4);
        firecontrolcompat$drawGroundLockLabel(graphics, ClientRadarState.getLockedTarget(), x, y, half + 4);
    }

    @Redirect(
            method = "renderWorldFrames",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/client/RadarHudRenderer;drawCornerSquare(Lnet/minecraft/client/gui/GuiGraphics;III)V"
            )
    )
    private static void firecontrolcompat$cornerSquareWithLabel(GuiGraphics graphics, int x, int y, int half) {
        invokeDrawCornerSquare(graphics, x, y, half);
        if (ClientRadarState.isAircraftMode()) {
            firecontrolcompat$drawTypeLabel(graphics, firecontrolcompat$aircraftBoxTarget(), x, y, half);
        }
    }

    @Unique
    private static UUID firecontrolcompat$aircraftBoxTarget() {
        UUID selected = ClientRadarState.getSelectedTarget();
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            if (selected != null && contact.id().equals(selected)) {
                return selected;
            }
        }
        UUID locked = ClientRadarState.getLockedTarget();
        for (ClientRadarState.Contact contact : ClientRadarState.getContacts()) {
            if (locked != null && contact.id().equals(locked)) {
                return locked;
            }
        }
        return null;
    }

    @Unique
    private static void firecontrolcompat$drawTypeLabel(GuiGraphics graphics, UUID target, int x, int y, int half) {
        Component label = LockLabelText.component(target);
        if (label == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        graphics.drawCenteredString(minecraft.font, label, x, y + half + 12, LockLabelText.LOCK_GREEN);
    }

    @Unique
    private static void firecontrolcompat$drawGroundLockLabel(
            GuiGraphics graphics, UUID target, int x, int y, int half) {
        Component label = LockLabelText.groundLockComponent(target);
        if (label == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        graphics.drawCenteredString(minecraft.font, label, x, y + half + 12, LockLabelText.LOCK_GREEN);
    }
}
