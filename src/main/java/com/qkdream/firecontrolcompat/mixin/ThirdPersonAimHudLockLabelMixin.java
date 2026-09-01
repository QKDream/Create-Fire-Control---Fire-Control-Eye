package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ThirdPersonAimHudRenderer;
import com.qkdream.firecontrolcompat.client.LeadArrowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the optical lock square and adds the lead indicator at its centre.
 * The target category text is drawn only by the radar HUD so that optical
 * and radar locks never stack duplicate labels.
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
}
