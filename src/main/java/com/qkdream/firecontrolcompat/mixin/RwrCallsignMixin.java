package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.client.RadarHudRenderer;
import com.hooya.stabilizedturret.network.RadarSessionPayload;
import com.qkdream.firecontrolcompat.RadarNameState;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prints radar call signs on the radar warning receiver: a named radar is
 * marked with the call sign its crew wrote on the fire control screen instead
 * of the plain type, an unnamed radar keeps the vanilla type, and a missile
 * always keeps its plain MSL mark.
 *
 * <p>One platform often paints with two radars at once - its fire control
 * computer and a connected display - and both marks land on the same spot of
 * the dial, which used to leave a stray type label on top of the call sign.
 * Labels are therefore collected while the vanilla loop runs, clustered by
 * position and drawn once at the end of the method: a cluster that contains a
 * call sign prints the call sign, a cluster without one prints the plain
 * type.</p>
 */
@Mixin(RadarHudRenderer.class)
public abstract class RwrCallsignMixin {

    /** Upper bound of labels the vanilla loop can hand over in one frame. */
    @Unique
    private static final int COMPAT_MAX_LABELS = 96;

    /** Two marks closer than this on screen belong to the same cluster. */
    @Unique
    private static final int COMPAT_CLUSTER_RADIUS = 4;

    @Unique
    private static int[] firecontrolcompat$labelX;

    @Unique
    private static int[] firecontrolcompat$labelY;

    @Unique
    private static float[] firecontrolcompat$labelScale;

    @Unique
    private static int[] firecontrolcompat$labelColor;

    @Unique
    private static String[] firecontrolcompat$labelText;

    @Unique
    private static boolean[] firecontrolcompat$labelCallSign;

    @Unique
    private static boolean[] firecontrolcompat$labelDrawn;

    @Unique
    private static void firecontrolcompat$ensureLabelBuffers() {
        if (firecontrolcompat$labelX != null) {
            return;
        }
        firecontrolcompat$labelX = new int[COMPAT_MAX_LABELS];
        firecontrolcompat$labelY = new int[COMPAT_MAX_LABELS];
        firecontrolcompat$labelScale = new float[COMPAT_MAX_LABELS];
        firecontrolcompat$labelColor = new int[COMPAT_MAX_LABELS];
        firecontrolcompat$labelText = new String[COMPAT_MAX_LABELS];
        firecontrolcompat$labelCallSign = new boolean[COMPAT_MAX_LABELS];
        firecontrolcompat$labelDrawn = new boolean[COMPAT_MAX_LABELS];
    }

    @Unique
    private static int firecontrolcompat$labelCount;

    /** Position of the threat whose label is being collected right now. */
    @Unique
    private static int firecontrolcompat$rwrLabelIndex;

    @Invoker("drawScaledCenteredString")
    private static void invokeDrawScaledCenteredString(
            GuiGraphics graphics, Minecraft minecraft, String text, int x, int y, float scale, int color) {
        throw new AbstractMethodError();
    }

    @Inject(method = "renderRwr", at = @At("HEAD"))
    private static void firecontrolcompat$startRwrLabels(
            GuiGraphics graphics, Minecraft minecraft, CallbackInfo ci) {
        firecontrolcompat$ensureLabelBuffers();
        firecontrolcompat$labelCount = 0;
        firecontrolcompat$rwrLabelIndex = 0;
    }

    /**
     * Takes the label over instead of drawing it: the text is paired with the
     * threat it belongs to and only drawn once the whole threat list has been
     * walked, so overlapping marks can be merged.
     */
    @Redirect(
            method = "renderRwr",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/client/RadarHudRenderer;drawScaledCenteredString(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/Minecraft;Ljava/lang/String;IIFI)V"
            )
    )
    private static void firecontrolcompat$collectLabel(
            GuiGraphics graphics, Minecraft minecraft, String label, int x, int y, float scale, int color) {
        String callSign = firecontrolcompat$callSignAt(firecontrolcompat$rwrLabelIndex++);
        int slot = firecontrolcompat$labelCount;
        if (slot < 0 || slot >= COMPAT_MAX_LABELS) {
            invokeDrawScaledCenteredString(graphics, minecraft, callSign.isEmpty() ? label : callSign, x, y, scale, color);
            return;
        }
        firecontrolcompat$labelX[slot] = x;
        firecontrolcompat$labelY[slot] = y;
        firecontrolcompat$labelScale[slot] = scale;
        firecontrolcompat$labelColor[slot] = color;
        firecontrolcompat$labelText[slot] = callSign.isEmpty() ? label : callSign;
        firecontrolcompat$labelCallSign[slot] = !callSign.isEmpty();
        firecontrolcompat$labelDrawn[slot] = false;
        firecontrolcompat$labelCount = slot + 1;
    }

    /** Draws one label per cluster, preferring the call sign when there is one. */
    @Inject(method = "renderRwr", at = @At("RETURN"))
    private static void firecontrolcompat$drawRwrLabels(
            GuiGraphics graphics, Minecraft minecraft, CallbackInfo ci) {
        for (int index = 0; index < firecontrolcompat$labelCount; index++) {
            if (firecontrolcompat$labelDrawn[index]) {
                continue;
            }
            String text = firecontrolcompat$labelText[index];
            for (int other = index + 1; other < firecontrolcompat$labelCount; other++) {
                if (firecontrolcompat$labelDrawn[other] || !firecontrolcompat$sameMark(index, other)) {
                    continue;
                }
                firecontrolcompat$labelDrawn[other] = true;
                if (!firecontrolcompat$labelCallSign[index] && firecontrolcompat$labelCallSign[other]) {
                    text = firecontrolcompat$labelText[other];
                }
            }
            firecontrolcompat$labelDrawn[index] = true;
            invokeDrawScaledCenteredString(
                    graphics,
                    minecraft,
                    text,
                    firecontrolcompat$labelX[index],
                    firecontrolcompat$labelY[index],
                    firecontrolcompat$labelScale[index],
                    firecontrolcompat$labelColor[index]);
        }
        firecontrolcompat$labelCount = 0;
    }

    @Unique
    private static boolean firecontrolcompat$sameMark(int first, int second) {
        return Math.abs(firecontrolcompat$labelX[first] - firecontrolcompat$labelX[second]) <= COMPAT_CLUSTER_RADIUS
                && Math.abs(firecontrolcompat$labelY[first] - firecontrolcompat$labelY[second]) <= COMPAT_CLUSTER_RADIUS;
    }

    @Unique
    private static String firecontrolcompat$callSignAt(int index) {
        List<ClientRadarState.Threat> threats = ClientRadarState.getThreats();
        if (index < 0 || index >= threats.size()) {
            return "";
        }
        ClientRadarState.Threat threat = threats.get(index);
        if (threat.type() == RadarSessionPayload.ThreatType.MSL) {
            return "";
        }
        return RadarNameState.threatName(threat.id());
    }
}
