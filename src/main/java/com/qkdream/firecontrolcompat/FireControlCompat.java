package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.network.ContactTypePayload;
import com.qkdream.firecontrolcompat.network.LeadSettingsPayload;
import com.qkdream.firecontrolcompat.network.LeadSettingsSyncPayload;
import com.qkdream.firecontrolcompat.network.MissileTrackPayload;
import com.qkdream.firecontrolcompat.network.SeekerHudPayload;
import org.slf4j.LoggerFactory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod("firecontrolcompat")
public final class FireControlCompat {
    public static final String MOD_ID = "firecontrolcompat";
    public static final Logger LOGGER = LoggerFactory.getLogger("firecontrolcompat");

    public FireControlCompat(IEventBus modEventBus, ModContainer container) {
        ShaolibBridge.resolve();
        CbcAutocannonPayloads.markerType();
        BeamMissileRegistry.register(modEventBus);
        modEventBus.addListener(ContactTypePayload::register);
        modEventBus.addListener(LeadSettingsPayload::register);
        modEventBus.addListener(LeadSettingsSyncPayload::register);
        modEventBus.addListener(MissileTrackPayload::register);
        modEventBus.addListener(SeekerHudPayload::register);
    }
}


