package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.RadarHudRenderer;
import com.qkdream.firecontrolcompat.client.LockFrameHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the locked target's category above the fire control lock frame.
 *
 * <p>Fire control 0.9.3 replaced its corner and connected squares with one frame class that is
 * package private, and draws the frame's own read-out below the box: distance and closing rate at
 * +43 to the right, height above ground centred at +41, motion indicator centred at +84, in a space
 * scaled by {@code guiHeight / 1440}. The category this mod has always drawn sat at
 * {@code box bottom + 12}, which now lands on the height line, so it is drawn above the box.</p>
 *
 * <p>The hook runs at the tail of the world frame pass and asks the pack's own projection where the
 * locked contact is, so nothing of the pack's frame is reimplemented or duplicated: the box stays
 * theirs, this mod adds the one line it always added.</p>
 */
@Mixin(RadarHudRenderer.class)
public abstract class RadarHudLockLabelMixin {

    @Inject(method = "renderWorldFrames", at = @At("TAIL"), require = 0)
    private static void firecontrolcompat$drawCategoryAboveFrame(
            GuiGraphics graphics, Minecraft minecraft, float partialTick, CallbackInfo ci) {
        LockFrameHud.drawRadarLabel(graphics, minecraft, minecraft.getWindow().getGuiScaledHeight());
    }
}
