package com.qkdream.firecontrolcompat.network;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.RemoteControlTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.OpticalBlockLockAccess;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Streams the block-lock flag to the player who is operating a fire control
 * controller. The flag mirrors the controller selection of the fire control
 * mod's own aim session handler, so the client always hears about the lock
 * state of the controller whose pod / scope it is actually looking through.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class OpticalBlockLockSync {

    private static final Map<UUID, Boolean> LAST_SENT = new HashMap<>();

    private OpticalBlockLockSync() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !ModList.get().isLoaded("create_fire_control")) {
            return;
        }
        boolean blockLock = firecontrolcompat$blockLock(player);
        Boolean previous = LAST_SENT.put(player.getUUID(), blockLock);
        if (previous == null || !Objects.equals(previous, blockLock)) {
            PacketDistributor.sendToPlayer(player, new OpticalBlockLockPayload(blockLock));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    private static boolean firecontrolcompat$blockLock(ServerPlayer player) {
        StabilizerControllerBlockEntity seated = ControllerTracker.findForVehicle(player.level(), player.getVehicle());
        StabilizerControllerBlockEntity inputController = RemoteControlTracker.findInputController(player);
        StabilizerControllerBlockEntity remoteController =
                inputController != null && inputController.isRemoteHeadAimEnabled() ? inputController : null;
        StabilizerControllerBlockEntity controller = inputController == null
                        || (!inputController.isPodViewEnabled(player) && !inputController.isScopeEnabled(player))
                ? (remoteController != null ? remoteController : seated)
                : inputController;
        return controller instanceof OpticalBlockLockAccess access
                && access.firecontrolcompat$hasBlockLock()
                && controller.hasOpticalLock();
    }
}
