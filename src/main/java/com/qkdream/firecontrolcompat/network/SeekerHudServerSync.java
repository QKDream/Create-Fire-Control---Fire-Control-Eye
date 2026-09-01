package com.qkdream.firecontrolcompat.network;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Feeds the pre-launch seeker range frame. While the player sits in a
 * fire-control-bound seat, the seated controller's infrared readiness and the
 * launch geometry of the first ready launcher / rack are streamed to that
 * player, so the HUD can draw the frame along the missile's launch direction.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class SeekerHudServerSync {

    private static int syncCounter;

    private SeekerHudServerSync() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !ModList.get().isLoaded("create_fire_control")) {
            return;
        }
        StabilizerControllerBlockEntity controller =
                ControllerTracker.findForVehicle(player.level(), player.getVehicle());
        if (controller == null || controller.getLevel() == null) {
            return;
        }
        boolean groundIrReady = false;
        boolean aircraftIrSelected = false;
        Vec3 origin = null;
        Direction launchFacing = null;
        Level level = controller.getLevel();
        for (BindingRef launcher : controller.getAaMissileLaunchers()) {
            if (BeamMissileCompat.hasIrAntiAirAmmo(level, launcher)) {
                groundIrReady = true;
                origin = BeamMissileCompat.antiAirMuzzle(level, launcher.pos());
                launchFacing = BeamMissileCompat.facing(level, launcher.pos());
                break;
            }
        }
        if (controller.getSelectedAircraftWeapon() == MianbaoAircraftCompat.WeaponType.CLOSE) {
            for (BindingRef rack : controller.getAircraftMissileRacks()) {
                if (BeamMissileCompat.isIrRackLoaded(level, rack)) {
                    aircraftIrSelected = true;
                    if (origin == null) {
                        origin = BeamMissileCompat.rackMuzzle(level, rack.pos());
                        launchFacing = BeamMissileCompat.facing(level, rack.pos());
                    }
                    break;
                }
            }
        }
        Vec3 worldOrigin = null;
        Vec3 worldDirection = null;
        if (origin != null && launchFacing != null) {
            try {
                worldOrigin = Sable.HELPER.projectOutOfSubLevel(level, origin);
                Vec3 ahead = Sable.HELPER.projectOutOfSubLevel(level,
                        origin.add(launchFacing.getStepX(), launchFacing.getStepY(), launchFacing.getStepZ()));
                Vec3 projectedDirection = ahead.subtract(worldOrigin);
                worldDirection = projectedDirection.lengthSqr() < 1.0E-8
                        ? new Vec3(launchFacing.getStepX(), launchFacing.getStepY(), launchFacing.getStepZ())
                        : projectedDirection.normalize();
            } catch (Throwable ignored) {
                worldOrigin = null;
                worldDirection = null;
            }
        }
        PacketDistributor.sendToPlayer(player, new SeekerHudPayload(
                groundIrReady, aircraftIrSelected, worldOrigin, worldDirection));
        if (syncCounter++ % 40 == 0) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] seeker hud sync player={} ground={} aircraft={} origin={} dir={}",
                    player.getName().getString(), groundIrReady, aircraftIrSelected, worldOrigin, worldDirection);
        }
    }
}
