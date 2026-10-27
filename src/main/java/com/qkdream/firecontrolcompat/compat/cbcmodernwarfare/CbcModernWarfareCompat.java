package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.client.AtgmMediumcannonProjectileRenderer;
import com.qkdream.firecontrolcompat.client.SensorFuzedSubmunitionRenderer;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.AtgmMediumcannonProjectile;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.IncendiaryApdsMediumcannonProjectile;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.MetalJetProjectile;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.SapMediumcannonProjectile;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.SensorFuzedMediumcannonProjectile;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.SensorFuzedSubmunition;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.TrafficConeMediumcannonProjectile;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.AtgmMediumcannonCartridgeItem;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.AtgmMediumcannonRoundItem;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.SapMediumcannonCartridgeItem;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.SapMediumcannonRoundItem;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.SfmMediumcannonCartridgeItem;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.SfmMediumcannonRoundItem;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.TrafficConeMediumcannonCartridgeItem;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item.TrafficConeMediumcannonRoundItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import rbasamoyai.createbigcannons.munitions.config.MunitionPropertiesHandler;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMunitionPropertiesHandlers;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonProjectileRenderer;

/**
 * Medium cannon rounds added on top of CBC Modern Warfare: the semi-armour
 * piercing shell, the traffic cone and the gun launched missile. Everything
 * here is only touched while that mod is present, so a pack without it still
 * starts.
 */
public final class CbcModernWarfareCompat {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FireControlCompat.MOD_ID);

    public static final DeferredHolder<Item, SapMediumcannonRoundItem> SAP_MEDIUMCANNON_ROUND =
            ITEMS.register("sap_mediumcannon_round", () -> new SapMediumcannonRoundItem(new Item.Properties()));
    public static final DeferredHolder<Item, SapMediumcannonCartridgeItem> SAP_MEDIUMCANNON_CARTRIDGE =
            ITEMS.register("sap_mediumcannon_cartridge", () -> new SapMediumcannonCartridgeItem(new Item.Properties()));
    public static final DeferredHolder<Item, TrafficConeMediumcannonRoundItem> TRAFFIC_CONE_MEDIUMCANNON_ROUND =
            ITEMS.register("traffic_cone_mediumcannon_round",
                    () -> new TrafficConeMediumcannonRoundItem(new Item.Properties()));
    public static final DeferredHolder<Item, TrafficConeMediumcannonCartridgeItem> TRAFFIC_CONE_MEDIUMCANNON_CARTRIDGE =
            ITEMS.register("traffic_cone_mediumcannon_cartridge",
                    () -> new TrafficConeMediumcannonCartridgeItem(new Item.Properties()));
    public static final DeferredHolder<Item, AtgmMediumcannonRoundItem> ATGM_MEDIUMCANNON_ROUND =
            ITEMS.register("atgm_mediumcannon_round", () -> new AtgmMediumcannonRoundItem(new Item.Properties()));
    public static final DeferredHolder<Item, AtgmMediumcannonCartridgeItem> ATGM_MEDIUMCANNON_CARTRIDGE =
            ITEMS.register("atgm_mediumcannon_cartridge",
                    () -> new AtgmMediumcannonCartridgeItem(new Item.Properties()));
    public static final DeferredHolder<Item, SfmMediumcannonRoundItem> SFM_MEDIUMCANNON_ROUND =
            ITEMS.register("sfm_mediumcannon_round", () -> new SfmMediumcannonRoundItem(new Item.Properties()));
    public static final DeferredHolder<Item, SfmMediumcannonCartridgeItem> SFM_MEDIUMCANNON_CARTRIDGE =
            ITEMS.register("sfm_mediumcannon_cartridge",
                    () -> new SfmMediumcannonCartridgeItem(new Item.Properties()));
    public static final DeferredHolder<Item, Item> SFM_SUBMUNITION =
            ITEMS.register("sfm_submunition", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<EntityType<?>, EntityType<SapMediumcannonProjectile>> SAP_MEDIUMSHELL =
            ENTITY_TYPES.register("sap_mediumshell",
                    () -> EntityType.Builder.<SapMediumcannonProjectile>of(SapMediumcannonProjectile::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("sap_mediumshell"));

    public static final DeferredHolder<EntityType<?>, EntityType<TrafficConeMediumcannonProjectile>> TRAFFIC_CONE_MEDIUMSHELL =
            ENTITY_TYPES.register("traffic_cone_mediumshell",
                    () -> EntityType.Builder.<TrafficConeMediumcannonProjectile>of(TrafficConeMediumcannonProjectile::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("traffic_cone_mediumshell"));

    public static final DeferredHolder<EntityType<?>, EntityType<AtgmMediumcannonProjectile>> ATGM_MEDIUMSHELL =
            ENTITY_TYPES.register("atgm_mediumshell",
                    () -> EntityType.Builder.<AtgmMediumcannonProjectile>of(AtgmMediumcannonProjectile::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(24)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("atgm_mediumshell"));
    public static final DeferredHolder<EntityType<?>, EntityType<SensorFuzedMediumcannonProjectile>> SFM_MEDIUMSHELL =
            ENTITY_TYPES.register("sfm_mediumshell",
                    () -> EntityType.Builder.<SensorFuzedMediumcannonProjectile>of(SensorFuzedMediumcannonProjectile::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("sfm_mediumshell"));

    public static final DeferredHolder<EntityType<?>, EntityType<SensorFuzedSubmunition>> SFM_SUBMUNITION_ENTITY =
            ENTITY_TYPES.register("sfm_submunition",
                    () -> EntityType.Builder.<SensorFuzedSubmunition>of(SensorFuzedSubmunition::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("sfm_submunition"));

    /**
     * The incendiary APDS dart the sensor fuzed shell's submunitions shoot. It
     * is only ever spawned by that shell, so it has no item of its own.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<IncendiaryApdsMediumcannonProjectile>> INCENDIARY_APDS_MEDIUMSHELL =
            ENTITY_TYPES.register("incendiary_apds_mediumshell",
                    () -> EntityType.Builder.<IncendiaryApdsMediumcannonProjectile>of(IncendiaryApdsMediumcannonProjectile::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("incendiary_apds_mediumshell"));

    /**
     * The molten jet a shaped charge throws out. It is never ammunition and has
     * no item, so it is kept out of the creative menu; the only thing that ever
     * spawns it is a detonating armour piercing shell.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<MetalJetProjectile>> METAL_JET =
            ENTITY_TYPES.register("metal_jet",
                    () -> EntityType.Builder.<MetalJetProjectile>of(MetalJetProjectile::new, MobCategory.MISC)
                            .sized(0.18F, 0.18F)
                            .clientTrackingRange(24)
                            .updateInterval(1)
                            .fireImmune()
                            .setShouldReceiveVelocityUpdates(false)
                            .build("metal_jet"));

    private CbcModernWarfareCompat() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        ENTITY_TYPES.register(bus);
        bus.addListener(CbcModernWarfareCompat::registerRenderers);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SAP_MEDIUMSHELL.get(), MediumcannonProjectileRenderer::new);
        event.registerEntityRenderer(TRAFFIC_CONE_MEDIUMSHELL.get(), MediumcannonProjectileRenderer::new);
        event.registerEntityRenderer(ATGM_MEDIUMSHELL.get(), AtgmMediumcannonProjectileRenderer::new);
        event.registerEntityRenderer(SFM_MEDIUMSHELL.get(), AtgmMediumcannonProjectileRenderer::new);
        event.registerEntityRenderer(SFM_SUBMUNITION_ENTITY.get(), SensorFuzedSubmunitionRenderer::new);
        event.registerEntityRenderer(INCENDIARY_APDS_MEDIUMSHELL.get(), MediumcannonProjectileRenderer::new);
        // The metal jet wears the armour piercing round's own model and texture.
        event.registerEntityRenderer(METAL_JET.get(), MediumcannonProjectileRenderer::new);
    }

    /**
     * Ties the rounds to the data pack entries in
     * {@code data/firecontrolcompat/munition_properties/projectiles}.
     */
    public static void registerProjectileHandlers() {
        MunitionPropertiesHandler.registerProjectileHandler(SAP_MEDIUMSHELL.get(),
                CBCModernWarfareMunitionPropertiesHandlers.APHE_MEDIUMCANNON);
        MunitionPropertiesHandler.registerProjectileHandler(TRAFFIC_CONE_MEDIUMSHELL.get(),
                CBCModernWarfareMunitionPropertiesHandlers.INERT_MEDIUMCANNON_PROJECTILE);
        MunitionPropertiesHandler.registerProjectileHandler(ATGM_MEDIUMSHELL.get(),
                CBCModernWarfareMunitionPropertiesHandlers.HEAP_MEDIUMCANNON);
        MunitionPropertiesHandler.registerProjectileHandler(SFM_MEDIUMSHELL.get(),
                CBCModernWarfareMunitionPropertiesHandlers.HEAP_MEDIUMCANNON);
        MunitionPropertiesHandler.registerProjectileHandler(INCENDIARY_APDS_MEDIUMSHELL.get(),
                CBCModernWarfareMunitionPropertiesHandlers.INERT_MEDIUMCANNON_PROJECTILE);
        // No munition properties handler for the jet.
        //
        // The handler pulls InertMediumcannonProjectileProperties for the entity's id, and those are
        // data driven: nothing defines them for a metal jet, so the lookup returns null and the
        // server dies while encoding the projectile sync packet - the client is dropped with
        // "连接已丢失" the moment it joins. The jet keeps its own motion in getForces instead.
    }

    /** Adds the rounds, already seated in a cartridge, to a creative menu. */
    public static void addToCreativeTab(CreativeModeTab.Output output) {
        output.accept(SAP_MEDIUMCANNON_ROUND.get());
        output.accept(SAP_MEDIUMCANNON_CARTRIDGE.get().getCreativeTabCartridgeItem());
        output.accept(TRAFFIC_CONE_MEDIUMCANNON_ROUND.get());
        output.accept(TRAFFIC_CONE_MEDIUMCANNON_CARTRIDGE.get().getCreativeTabCartridgeItem());
        output.accept(ATGM_MEDIUMCANNON_ROUND.get());
        output.accept(ATGM_MEDIUMCANNON_CARTRIDGE.get().getCreativeTabCartridgeItem());
        output.accept(SFM_MEDIUMCANNON_ROUND.get());
        output.accept(SFM_MEDIUMCANNON_CARTRIDGE.get().getCreativeTabCartridgeItem());
    }
}