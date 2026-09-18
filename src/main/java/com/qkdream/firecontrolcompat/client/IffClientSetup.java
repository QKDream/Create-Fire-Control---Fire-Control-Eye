package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.iff.IffRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Registers the client screen of the IFF transponder. */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class IffClientSetup {

    private IffClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(IffRegistry.IFF_MENU.get(), IffTransponderScreen::new);
    }
}