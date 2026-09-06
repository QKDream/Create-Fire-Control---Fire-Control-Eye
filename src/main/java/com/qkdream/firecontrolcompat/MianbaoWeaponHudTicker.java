package com.qkdream.firecontrolcompat;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps the TAOV weapon-HUD leases for bound Mianbao sources alive. Mianbao
 * block entities do not tick, so this server-side heartbeat stands in for
 * the per-block-entity publishing that TAOV weapons do themselves.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class MianbaoWeaponHudTicker {

    private MianbaoWeaponHudTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MianbaoWeaponHud.heartbeatAll();
    }
}
