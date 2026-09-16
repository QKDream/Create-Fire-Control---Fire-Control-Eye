package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Registers the Mianbao weapon-HUD executor on the client once during setup.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MianbaoWeaponHudClient {

    private MianbaoWeaponHudClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (FireControlCompat.isModLoaded("taov_core")) {
            MianbaoWeaponHudExecutor.register();
            if (FireControlCompat.isModLoaded("cbcmoreshells")) {
                CbcAmmoRackHudExecutor.register();
            }
        }
    }
}
