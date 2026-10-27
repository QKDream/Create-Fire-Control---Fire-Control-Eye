package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance;
import com.qkdream.firecontrolcompat.client.CompatBvr;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Carries this mod's rounds on fire control's beyond-visual-range rendering chain.
 *
 * <h2>Why this one predicate</h2>
 *
 * <p>{@code OrdinaryAirDefenseGuidance#isOrdinaryType} is the switch the pack's whole long-range
 * missile picture hangs off. It is read in exactly four places, and all four are wanted here:</p>
 *
 * <table>
 *   <tr><th>call site</th><th>what changes for our rounds</th></tr>
 *   <tr><td>{@code MissileLongRangeEffects#acceptRemoteState} :175</td>
 *       <td>the client proxy becomes {@code detachedFromClientChunks}: it is <b>not</b> added to the
 *       client level, so it is not drawn by the ordinary (fogged) entity pass at all;</td></tr>
 *   <tr><td>{@code ClientMissileRenderer#render} :110</td>
 *       <td>the detached proxy is drawn by {@code renderOrdinaryAa}, which pushes the fog out to a
 *       million blocks, rebuilds the projection so the far plane reaches the round, and calls
 *       {@code EntityRenderDispatcher} - this mod's own renderer and texture;</td></tr>
 *   <tr><td>{@code MissileLongRangeEffects#onMissileRendered} :404</td>
 *       <td>the round gets the pack's own long-range trail particle, so there is one trail and it
 *       comes from the same place as the model;</td></tr>
 *   <tr><td>{@code OrdinaryAirDefenseGuidance#isManaged} :45</td>
 *       <td><b>nothing</b>: that also requires the {@code CreateFireControlOrdinaryAaGuided}
 *       tag, which this mod's rounds never carry. The pack's beam-riding guidance therefore never
 *       adopts a round of ours, and this widening cannot hand our guidance to the pack.</td></tr>
 * </table>
 *
 * <p>Handled at {@code RETURN} and only when the pack said <b>no</b>: its own id keeps working
 * exactly as before, so the pack's air-defense round is untouched.</p>
 */
@Mixin(OrdinaryAirDefenseGuidance.class)
public abstract class OrdinaryAaBvrMixin {

    @Inject(method = "isOrdinaryType", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$broadcastOurRounds(EntityType<?> type, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (Boolean.TRUE.equals(cir.getReturnValue())) {
                return;
            }
            if (!CompatBvr.available()) {
                return;
            }
            if (CompatBvr.isBvrRound(type)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
            // A pack class that moved must not take the renderer down with it; MissileFarMarker
            // still answers in that case.
        }
    }
}
