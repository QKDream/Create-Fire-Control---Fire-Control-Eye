package com.qkdream.firecontrolcompat;



import com.qkdream.firecontrolcompat.compat.cbcbigcannon.CbcBigCannonCompat;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaBigCannonCompat;

import com.qkdream.firecontrolcompat.crate.AmmoCrateRegistry;

import com.qkdream.firecontrolcompat.deco.DecoRegistry;

import com.qkdream.firecontrolcompat.sonar.SonarBlocks;

import com.qkdream.firecontrolcompat.underwater.AcousticTorpedoes;
import com.qkdream.firecontrolcompat.underwater.TorpedoDecoy;
import com.qkdream.firecontrolcompat.underwater.WireGuidedTorpedoes;

import com.qkdream.firecontrolcompat.network.TorpedoSettingsPayload;

import com.qkdream.firecontrolcompat.network.ContactTypePayload;
import com.qkdream.firecontrolcompat.network.StrikePointPayload;
import com.qkdream.firecontrolcompat.network.OpticalBlockLockPayload;

import com.qkdream.firecontrolcompat.network.GunBallisticsPayload;

import com.qkdream.firecontrolcompat.network.LeadSettingsPayload;

import com.qkdream.firecontrolcompat.network.LeadSettingsSyncPayload;

import com.qkdream.firecontrolcompat.network.ControllerLeadPayload;

import com.qkdream.firecontrolcompat.network.ControllerLeadSyncPayload;

import com.qkdream.firecontrolcompat.network.LaserCodePayload;
import com.qkdream.firecontrolcompat.network.RadarNamePayload;

import com.qkdream.firecontrolcompat.network.RadarNameSyncPayload;

import com.qkdream.firecontrolcompat.network.SonarSessionPayload;

import com.qkdream.firecontrolcompat.network.SonarSelectBindPayload;

import com.qkdream.firecontrolcompat.network.SonarSettingsPayload;

import com.qkdream.firecontrolcompat.network.SonarSettingsSyncPayload;

import com.qkdream.firecontrolcompat.network.SpyglassMarkClientPayload;

import com.qkdream.firecontrolcompat.network.YuushyaCannonTypePayload;

import com.qkdream.firecontrolcompat.network.SpyglassMarkPayload;

import com.qkdream.firecontrolcompat.network.RadarThreatLabelPayload;

import com.qkdream.firecontrolcompat.network.MissileTrackPayload;

import com.qkdream.firecontrolcompat.network.SeekerHudPayload;

import org.slf4j.LoggerFactory;

import net.neoforged.bus.api.IEventBus;

import net.neoforged.fml.ModContainer;

import net.neoforged.fml.ModList;

import net.neoforged.fml.common.Mod;

import net.neoforged.fml.event.config.ModConfigEvent;

import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import net.neoforged.fml.loading.FMLEnvironment;

import net.neoforged.neoforge.common.ModConfigSpec;

import org.slf4j.Logger;



@Mod("firecontrolcompat")

public final class FireControlCompat {

    /**
     * Starts the start-up cost probe as early as this mod can observe anything.

     * The static initialiser runs before FML constructs this class, so
     * {@code StartupProbe}'s {@code nanoTime} reference is taken at the first moment the mod
     * is touched - what every later phase is measured against.
     */
    static {

        StartupProbe.begin();

    }



    public static final String MOD_ID = "firecontrolcompat";

    public static final Logger LOGGER = LoggerFactory.getLogger("firecontrolcompat");



    public FireControlCompat(IEventBus modEventBus, ModContainer container) {
        // Startup probe: the whole constructor is timed, and the missile registry call inside it
        // is timed again on its own so the two numbers can be read apart.
        long probeConstructor = StartupProbe.tick();
        // The blueprint exporter was its own mod until 1.70; its listeners are registered here
        // now, which is the only thing that used to happen in its own @Mod constructor.
        new com.qkdream.firecontrolcompat.blueprint.BlueprintExporter(modEventBus);


        ShaolibBridge.resolve();

        if (isModLoaded("shaolib") && isModLoaded("taov_weapons")) {

            CbcAutocannonPayloads.markerType();

        }

        FireControlLeadSettings.register(container);

        long probeRegistry = StartupProbe.tick();

        BeamMissileRegistry.register(modEventBus);

        StartupProbe.stage("registry", probeRegistry);

        NevaLauncher.register(modEventBus);

        CbcBigCannonCompat.register(modEventBus);

        if (isModLoaded("cbcmodernwarfare")) {

            CbcModernWarfareCompat.register(modEventBus);

        }

        AmmoCrateRegistry.register(modEventBus);
        StrikeTerminalRegistry.register(modEventBus);
        SonicPulse.register(modEventBus);

        SonarBlocks.register(modEventBus);

        AcousticTorpedoes.register(modEventBus);
        WireGuidedTorpedoes.register(modEventBus);

        TorpedoDecoy.register(modEventBus);

        DecoRegistry.register(modEventBus);

        YuushyaBigCannonCompat.register(modEventBus);

        SilencerSupport.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        modEventBus.addListener((ModConfigEvent.Loading event) -> this.configRead(event));

        modEventBus.addListener((ModConfigEvent.Reloading event) -> this.configRead(event));

        modEventBus.addListener(ContactTypePayload::register);
        modEventBus.addListener(StrikePointPayload::register);

        modEventBus.addListener(LeadSettingsPayload::register);

        modEventBus.addListener(LeadSettingsSyncPayload::register);

        modEventBus.addListener(GunBallisticsPayload::register);

        modEventBus.addListener(MissileTrackPayload::register);

        modEventBus.addListener(OpticalBlockLockPayload::register);

        modEventBus.addListener(SeekerHudPayload::register);

        modEventBus.addListener(RadarNamePayload::register);
        modEventBus.addListener(LaserCodePayload::register);

        modEventBus.addListener(RadarNameSyncPayload::register);

        modEventBus.addListener(ControllerLeadPayload::register);

        modEventBus.addListener(ControllerLeadSyncPayload::register);

        // Beam riding launch mode: horizontal (along the rail) or vertical (kick
        // the round out of the tube and turn it over). Kept per computer, so the
        // client asks for it when the screen opens and writes it back on save.
        modEventBus.addListener(com.qkdream.firecontrolcompat.network.BeamLaunchModePayload::register);

        modEventBus.addListener(com.qkdream.firecontrolcompat.network.BeamLaunchModeSyncPayload::register);

        modEventBus.addListener(RadarThreatLabelPayload::register);

        modEventBus.addListener(SpyglassMarkPayload::register);

        modEventBus.addListener(SpyglassMarkClientPayload::register);

        modEventBus.addListener(SonarSettingsPayload::register);

        modEventBus.addListener(SonarSettingsSyncPayload::register);

        modEventBus.addListener(SonarSessionPayload::register);

        modEventBus.addListener(SonarSelectBindPayload::register);

        modEventBus.addListener(TorpedoSettingsPayload::register);

        modEventBus.addListener(YuushyaCannonTypePayload::register);

        StartupProbe.stage("constructor", probeConstructor);

    }



    /**

     * Restores the lead settings the fire control computer stored last time, so

     * a launch mode or a proximity range picked there survives a restart.

     */

    private void configRead(ModConfigEvent event) {

        if (event.getConfig().getSpec() instanceof ModConfigSpec spec

                && FireControlLeadSettings.owns(spec)) {

            FireControlLeadSettings.loadFromConfig();

        }

    }



    private void commonSetup(FMLCommonSetupEvent event) {

        long probeSetup = StartupProbe.tick();

        // Startup probe: load (never initialise) the utility classes whose static initialisers
        // are timed, so the JVM side of class info is on the same log as the rest.
        StartupProbe.classInfo();

        if (isModLoaded("taov_core")) {

            MianbaoWeaponHud.registerSources();

            CbcAmmoRackHud.registerSources();

        }

        if (isModLoaded("cbcmodernwarfare")) {

            CbcModernWarfareCompat.registerProjectileHandlers();

        }

        CbcBigCannonCompat.registerProjectileHandlers();

        CbcBigCannonCompat.initialize();

        AcousticTorpedoes.registerProjectileHandlers();

        AcousticTorpedoes.initialize();

        WireGuidedTorpedoes.registerProjectileHandlers();

        WireGuidedTorpedoes.initialize();

        // Taken out together with the rest of the pod chain when the config says so:

        // this path writes aim origins and view points on a gunner's behalf.

        if (PodLinkSwitch.seatViewPortEnabled()) {

            SynaxisSeatViewPortCompat.register();

        }

        if (isModLoaded(YuushyaBigCannonCompat.YUUSHYA)) {

            YuushyaBigCannonCompat.initialize();

        }

        StartupProbe.stage("setup", probeSetup);

        // Which tracking range each of our entity types really got, read off the registry once:
        // the "invisible beyond visual range" report has twice been about these numbers, and a
        // build cannot show them. One line per type, on both sides.
        try {
            BeamMissileRegistry.logTracking();
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a failed start-up
        }

        // On a client the probe's end is client set-up (that is where the renderer and item icon
        // registrations have all happened); a dedicated server has no such stage, so the summary
        // goes out here instead. Only the first call reports.
        if (!FMLEnvironment.dist.isClient()) {

            StartupProbe.finish();

        }

    }



    /** True when the given mod is present. Safe to call during mod construction. */

    public static boolean isModLoaded(String modId) {

        try {

            return ModList.get().isLoaded(modId);

        } catch (Throwable ignored) {

            return false;

        }

    }

}




