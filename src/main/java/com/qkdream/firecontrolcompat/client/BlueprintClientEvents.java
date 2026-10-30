package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client-only half of the blueprint exporter's <b>game</b>-bus listeners.
 *
 * <p>Split from {@link BlueprintClientSetup} because a class carries a single
 * {@code @EventBusSubscriber} and the two halves live on different buses. Both are
 * {@link Dist#CLIENT} annotated, so neither is loaded on a dedicated server: the annotation is
 * read from the scanned mod file, not by loading the class.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT)
public final class BlueprintClientEvents {

    private BlueprintClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        BlueprintExporter.onClientTick(event);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        BlueprintExporter.onRegisterClientCommands(event);
    }
}
