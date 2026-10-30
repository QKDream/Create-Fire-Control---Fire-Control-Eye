package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Client-only half of the blueprint exporter's start-up, on the <b>mod</b> bus.
 *
 * <p>Until 1.70.6 the exporter was constructed from {@code FireControlCompat}'s constructor, so a
 * dedicated server linked it too - and the exporter mentions {@code Screen} and
 * {@code Minecraft}, which the server's runtime-dist transformer refuses to load ("Attempted to
 * load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER").</p>
 *
 * <p>The registration is therefore annotation driven: FML reads {@code @EventBusSubscriber} out of
 * the scanned mod-file data and only instantiates the class when {@link Dist#CLIENT} matches, so a
 * dedicated server never resolves this class or anything it imports. The common entry point
 * ({@code FireControlCompat}) does not name it at all.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BlueprintClientSetup {

    private BlueprintClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        BlueprintExporter.onRegisterKeys(event);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BlueprintExporter.onClientSetup(event);
    }
}
