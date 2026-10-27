package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.RwrThreat;
import com.hooya.stabilizedturret.content.controller.RwrThreatCollector;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.IffRadar;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps own side emitters and own side ordnance off the radar warning
 * receiver. Threat ids are either the emitting computer's deterministic source
 * id or, for missiles, the round's entity id, so the emitter is resolved back
 * to its fire control computer (or its structure) and the IFF channels of that
 * rig are compared with the receiver's own channels.
 */
@Mixin(RwrThreatCollector.class)
public abstract class RwrThreatFilterMixin {

    @Inject(method = "collect", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$dropFriendlyThreats(
            StabilizerControllerBlockEntity receiver,
            CallbackInfoReturnable<List<RwrThreat>> cir
    ) {
        List<RwrThreat> threats = cir.getReturnValue();
        Level level = receiver == null ? null : receiver.getLevel();
        if (level == null || threats == null || threats.isEmpty()) {
            return;
        }
        Set<String> own = IffRadar.ownChannels(level, receiver);
        if (own.isEmpty()) {
            return;
        }
        List<RwrThreat> kept = new ArrayList<>(threats.size());
        for (RwrThreat threat : threats) {
            if (!IffRadar.friendlyThreat(level, threat, own)) {
                kept.add(threat);
            }
        }
        if (kept.size() != threats.size()) {
            cir.setReturnValue(List.copyOf(kept));
        }
    }
}
