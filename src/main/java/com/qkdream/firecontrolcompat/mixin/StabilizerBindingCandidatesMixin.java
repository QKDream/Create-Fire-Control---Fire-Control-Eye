package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerBindingHandler;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.qkdream.firecontrolcompat.sonar.SonarCandidates;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts sonar sets on the list of boxes a fire control computer paints while a
 * binding is armed, so a set is highlighted exactly like a module is and still
 * binds through this mod's own handshake when it is clicked. Optional, so a
 * reshaped candidate search in a later release costs the boxes rather than the
 * whole game.
 */
@Mixin(StabilizerBindingHandler.class)
public abstract class StabilizerBindingCandidatesMixin {

    @Inject(
            method = "findBindingCandidates(Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/core/BlockPos;Ljava/util/Set;Z)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private static void firecontrolcompat$addSonarCandidates(
            Level level,
            BlockPos sourcePos,
            Set<?> boundTargets,
            boolean cannonMountsOnly,
            CallbackInfoReturnable<List<BindingSessionPayload.Candidate>> cir) {
        if (cannonMountsOnly) {
            return;
        }
        try {
            List<BindingSessionPayload.Candidate> found = cir.getReturnValue();
            if (found == null) {
                return;
            }
            List<BindingSessionPayload.Candidate> extra = SonarCandidates.add(level, sourcePos, found);
            if (extra.isEmpty()) {
                return;
            }
            List<BindingSessionPayload.Candidate> merged = new ArrayList<>(found.size() + extra.size());
            merged.addAll(found);
            merged.addAll(extra);
            cir.setReturnValue(List.copyOf(merged));
        } catch (Throwable ignored) {
        }
    }
}
