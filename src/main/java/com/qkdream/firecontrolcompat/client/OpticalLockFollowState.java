package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client copy of the controller's block-lock flag. The HUD reticle mixin
 * reads it to switch the lock reticle style, and it is cleared whenever the
 * player leaves the session so a stale block lock never survives a rejoin.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT)
public final class OpticalLockFollowState {

    private static boolean blockLockActive;

    private OpticalLockFollowState() {
    }

    public static void applyBlockLock(boolean blockLock) {
        blockLockActive = blockLock;
    }

    public static boolean isBlockLockActive() {
        return blockLockActive;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        blockLockActive = false;
    }
}
