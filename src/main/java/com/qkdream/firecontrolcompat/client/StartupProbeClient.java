package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.StartupProbe;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client half of the start-up cost probe.
 *
 * <p>The client set-up hook only measures one stage; the single summary line is printed on the
 * first client tick. That is deliberate: renderer registration, GeckoLib's item extensions and
 * the resource reload do not have a fixed order relative to {@code FMLClientSetupEvent}, and the
 * summary is supposed to contain them all. The first rendered tick is after every one of them,
 * and it is still start-up - the player is on the title screen.</p>
 *
 * <p>Once the summary is out {@link StartupProbe#finish()} is a no-op, so this costs a single
 * boolean test per tick for the rest of the session.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class StartupProbeClient {

    private StartupProbeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        long began = StartupProbe.tick();
        StartupProbe.stage("client-setup", began);
        NeoForge.EVENT_BUS.addListener(StartupProbeClient::onClientTick);
        // Which long-range channel the rounds are on. Said once, at client setup, because it is the
        // one fact a reader of the log needs before any round is in the air: on the pack's chain the
        // fog and the far plane are the pack's problem, and this mod's own marker is off.
        try {
            CompatBvr.report();
        } catch (Throwable ignored) {
            // a missing pack class must never stop the client from starting
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        StartupProbe.finish();
    }
}
