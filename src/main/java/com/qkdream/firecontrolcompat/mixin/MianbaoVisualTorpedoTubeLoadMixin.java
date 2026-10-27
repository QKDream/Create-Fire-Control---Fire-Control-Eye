package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.VisualTorpedoTube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Lets 面包学's own visually guided round be loaded into a CBCMS torpedo tube.
 *
 * <p>The tube's own loading answers to CBCMS rounds only, so a round of 面包学's
 * is turned down before the click can reach it. The hook sits in front of the
 * contraption's interaction - before the tube is asked - and answers the click
 * itself when the round is one of 面包学's, handing the tube one of our torpedo
 * blocks carrying that round's name. Every other click falls straight through,
 * so a tube on a mount behaves exactly as it always did.</p>
 *
 * <p>Optional: without it the tube simply does not take that round.</p>
 */
@Mixin(PitchOrientedContraptionEntity.class)
public abstract class MianbaoVisualTorpedoTubeLoadMixin {

    @Inject(method = "handlePlayerInteraction", at = @At("HEAD"), cancellable = true, require = 0)
    private void firecontrolcompat$loadVisualRound(
            Player player, BlockPos localPos, Direction side, InteractionHand hand, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (VisualTorpedoTube.load((PitchOrientedContraptionEntity) (Object) this, player, localPos, side, hand)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
