package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.qkdream.firecontrolcompat.BombRackCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps 面包学's bomb racks visible to the fire control computer as weapon racks.
 *
 * <p>Fire control 0.9.3 knows the bomb racks itself and flies the bombs it drops with its own math
 * flight, complete with the predicted impact ring. Everything this hook used to add on top of that -
 * answering the weapon type for a loaded bomb rack, releasing the rack through its own procedure,
 * and handing the bombs to the air-to-ground guidance - has been removed: each of those took the
 * release out of the pack's hands, so the round never entered the math flight and the ring was
 * drawn for a flight the bomb was not taking. What is left is only what the pack still needs from
 * this mod: the empty and loaded bomb-rack blocks are racks, and the loaded variant is the same rack
 * as the empty one when the rounds come and go.</p>
 */
@Mixin(MianbaoAircraftCompat.class)
public abstract class MianbaoBombRackAircraftCompatMixin {

    /** A block that is a bomb rack, loaded or empty, is a rack like any other. */
    @Inject(method = "isRack", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$bombRackIsRack(
            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && BombRackCompat.isBombRack(state)) {
            cir.setReturnValue(true);
        }
    }

    /** ...and one that stays the same rack while the rounds on it come and go. */
    @Inject(method = "isSameRackVariant", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$bombRackStaysARack(
            ResourceLocation first, ResourceLocation second, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && BombRackCompat.isBombRackId(first)
                && BombRackCompat.isBombRackId(second)) {
            cir.setReturnValue(true);
        }
    }
}
