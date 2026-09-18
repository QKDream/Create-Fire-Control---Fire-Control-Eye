package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlock;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.network.IffStatusPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When the fire control screen is opened, the server also tells that player
 * whether an IFF transponder is wired to this computer, so the screen can show
 * the identity section only when there really is one.
 */
@Mixin(StabilizerControllerBlock.class)
public abstract class ControllerIffStatusMixin {

    @Inject(method = "useWithoutItem", at = @At("TAIL"))
    private void firecontrolcompat$sendIffStatus(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof StabilizerControllerBlockEntity controller)) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        IffStatusPayload.send(serverPlayer, serverLevel, pos, ControllerTracker.controllerSubLevelId(controller));
    }
}