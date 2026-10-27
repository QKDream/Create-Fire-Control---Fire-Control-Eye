package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlock;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.RadarNameStore;
import com.qkdream.firecontrolcompat.sonar.SonarSettings;
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
 * When the fire control screen is opened, the server also sends that player the
 * radar call sign of the computer, so the call sign box shows the stored name
 * instead of an empty field.
 */
@Mixin(StabilizerControllerBlock.class)
public abstract class ControllerNameStatusMixin {

    @Inject(method = "useWithoutItem", at = @At("TAIL"))
    private void firecontrolcompat$sendNameStatus(
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
        var subLevel = ControllerTracker.controllerSubLevelId(controller);
        RadarNameStore.sendStatus(serverPlayer, serverLevel, pos, subLevel);
        SonarSettings.sendStatus(serverPlayer, controller);
        // The lead settings belong to this computer too, so the settings page is
        // filled with this rig's own cannon family and charge.
        com.qkdream.firecontrolcompat.ControllerLeadState.sendStatus(serverPlayer, pos, subLevel);
        // And the launch mode of this rigs beam-riding rounds, so the toggle on
        // that page shows what this computer is actually set to.
        com.qkdream.firecontrolcompat.BeamLaunchModeStore.sendStatus(serverPlayer, serverLevel, pos, subLevel);
    }
}