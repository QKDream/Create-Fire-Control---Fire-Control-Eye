package com.qkdream.firecontrolcompat;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps the TAOV weapon-HUD leases for bound CBCMS ammo racks alive. The
 * racks do not publish their own leases, so this server-side heartbeat
 * stands in for the per-block-entity publishing that TAOV weapons do.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class CbcAmmoRackHudTicker {

    private CbcAmmoRackHudTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (FireControlCompat.isModLoaded("taov_core") && FireControlCompat.isModLoaded("cbcmoreshells")) {
            CbcAmmoRackHud.heartbeatAll();
        }
    }
}
