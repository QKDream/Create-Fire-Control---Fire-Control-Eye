package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.client.Fim92MissileRenderer;
import com.qkdream.firecontrolcompat.client.HeavyAaMissileRenderer;
import com.qkdream.firecontrolcompat.client.IrAtgmModelRenderer;
import com.qkdream.firecontrolcompat.client.IrAtgmMissileRenderer;
import com.qkdream.firecontrolcompat.client.JavaItemMissileRenderer;
import com.qkdream.firecontrolcompat.client.LoiteringMissileRenderer;
import com.qkdream.firecontrolcompat.client.NevaLauncherBlockRenderer;
import com.qkdream.firecontrolcompat.client.NevaMissileRenderer;
import com.qkdream.firecontrolcompat.compat.cbcbigcannon.CbcBigCannonCompat;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;
import com.qkdream.firecontrolcompat.entity.HeavyAirDefenseMissileEntity;
import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import com.qkdream.firecontrolcompat.entity.IrAtgmMissileEntity;
import com.qkdream.firecontrolcompat.entity.LoiteringMissileEntity;
import com.qkdream.firecontrolcompat.entity.NevaAirDefenseMissileEntity;
import com.qkdream.firecontrolcompat.sonar.SonarBlocks;
import com.qkdream.firecontrolcompat.underwater.AcousticTorpedoes;
import com.qkdream.firecontrolcompat.underwater.TorpedoDecoy;
import com.qkdream.firecontrolcompat.underwater.WireGuidedTorpedoes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item + entity registrations for the missiles that are left.
 *
 * <p>The beam-riding round this mod used to carry is retired: fire control flies an ordinary
 * beam-riding anti-air round of its own since 0.9.3, brought up to the envelope the old round had
 * by {@code OrdinaryAaPhysicsMixin}. Its item and entity type are gone from this registry, and the
 * shared base class {@code BeamRidingMissileEntity} is kept for the six rounds that extend it.</p>
 */
public final class BeamMissileRegistry {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FireControlCompat.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, FireControlCompat.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FireControlCompat.MOD_ID);

    public static final DeferredHolder<Item, Item> INFRARED_MISSILE =
            ITEMS.register("infrared_missile",
                    () -> new GeoMissileIconItem("item.firecontrolcompat.infrared_missile"));
    /*
     * RETIRED(ir-arm-rounds): the airborne infrared round.
     *
     * 火控学 ships that round officially in its next version, so this mod's own item, entity,
     * renderer and rail hooks are gone - this build no longer registers the round at all. A world
     * saved with one in a rack keeps the pack's block; a round that was in the air is dropped on
     * load with the usual "unknown entity" line instead of crashing. Restoring the round means
     * bringing back the item and entity holders below together with
     * {@code AircraftInfraredMissileEntity} / {@code AircraftInfraredMissileRenderer} from
     * {@code _bak_src/retire_ir_arm_20261025b}.
     *
     * public static final DeferredHolder<Item, Item> AIRCRAFT_INFRARED_MISSILE =
     *         ITEMS.register("aircraft_infrared_missile",
     *                 () -> new GeoMissileIconItem("item.firecontrolcompat.aircraft_infrared_missile"));
     */
    public static final DeferredHolder<Item, Item> LOITERING_MUNITION =
            ITEMS.register("loitering_munition",
                    () -> new FireControlMissileItem("item.firecontrolcompat.loitering_munition"));
    /*
     * TEMP-DISABLED(semi-active): the semi-active air-to-air missile item.
     *
     * The round, the entity type, the renderer and the R-40 model are all withdrawn for now. The
     * holders below are commented out rather than deleted, and every use site carries the same
     * `TEMP-DISABLED(semi-active)` marker, so restoring the round is "uncomment these two holders,
     * the two renderer lines, the two creative-tab lines, and the marked blocks in
     * BeamMissileCompat / RackMountedRounds / AntiRadiationRackLoadMixin / MissileItemRenderers".
     *
     * Registry entries are gone, so `/summon firecontrolcompat:semi_active_tanshe` reports an
     * unknown entity and the item cannot be obtained; a world that still holds such an entity
     * drops it on load with the usual "unknown entity" log line - it does not crash.
     *
     * public static final DeferredHolder<Item, Item> SEMI_ACTIVE_MISSILE =
     *         ITEMS.register("semi_active_missile",
     *                 () -> new GeoMissileIconItem("item.firecontrolcompat.semi_active_missile"));
     */
    public static final DeferredHolder<Item, Item> HEAVY_AIR_DEFENSE_MISSILE =
            ITEMS.register("heavy_air_defense_missile",
                    () -> new GeoMissileIconItem("item.firecontrolcompat.heavy_air_defense_missile"));

    public static final DeferredHolder<Item, Item> NEVA_MISSILE =
            ITEMS.register("neva_missile",
                    () -> new NevaMissileBlockItem(NevaLauncher.NEVA_LAUNCHER.get(), new Item.Properties()));

    /**
     * The heavy round's airframe, as a plain item.
     *
     * <p>Not a gameplay item: it is never put in the creative tab, has no recipe and is never
     * dropped. It exists because {@code ItemRenderer#renderStatic} draws <b>an item's own model</b>,
     * and both rounds that used to be drawn by GeckoLib already spend their item identity on an
     * icon that goes through a GeckoLib renderer ({@code heavy_air_defense_missile} is a
     * {@code GeoMissileIconItem}, {@code neva_missile} is a {@code NevaMissileBlockItem implements
     * GeoItem}), so asking for that stack's model would land back in GeckoLib. A plain item with a
     * plain model is the shortest way to say "draw this geometry and nothing else".</p>
     *
     * <p>The model itself is {@code models/item/heavy_aa_airframe.json}, built by
     * {@code _work/build_heavy_airframes.py} from the 48n6 proportions with the 48n6 texture.</p>
     */
    public static final DeferredHolder<Item, Item> HEAVY_AA_AIRFRAME =
            ITEMS.register("heavy_aa_airframe", () -> new Item(new Item.Properties()));

    /** The super-heavy (S-125) round's airframe; see {@link #HEAVY_AA_AIRFRAME}. */
    public static final DeferredHolder<Item, Item> NEVA_AIRFRAME =
            ITEMS.register("neva_airframe", () -> new Item(new Item.Properties()));

    /**
     * Fallback airframe dimensions in blocks, measured from the airframe models themselves
     * ({@code bbox z=-14.08..32.96} of 16 units per block = 2.94 blocks long, 0.58 tall).
     *
     * <p>Only {@code client/JavaItemMissileRenderer}'s <b>primitive</b> path uses these - the path
     * taken when the item model cannot be baked (missing texture, empty model, no model loader).
     * They are the model's own unscaled size on purpose: the renderer applies the same
     * {@code scale} to both paths, so a 48N6 whose item model fails to bake is the same size as one
     * whose item model works, and the fallback costs the round its detail and nothing else.</p>
     */
    public static final float HEAVY_AA_BODY_LENGTH = 2.94F;
    public static final float HEAVY_AA_BODY_RADIUS = 0.29F;
    public static final float NEVA_BODY_LENGTH = 2.94F;
    public static final float NEVA_BODY_RADIUS = 0.33F;

    /**
     * The infrared anti-tank round: fired at a locked ground point, over the top, HEAT on impact.
     *
     * <p>Loaded into 面包学's anti-tank launcher ({@code AtgmLauncherLoadMixin} ->
     * {@code BeamMissileCompat.tryLoadAtgm}) and released through the same launcher's own trigger,
     * where {@link IrAtgm} refuses the shot without an electro-optical lock.</p>
     *
     * <p><b>The airframe is a Java model (2026-10-26).</b> The art the user supplied is a
     * Java/Blockbench {@code elements} export ({@code 素材-红外反坦克导弹/spiker_nlos.json}), which is
     * the same format a Java item model uses, so it is shipped as one:
     * {@code models/item/ir_atgm.json}, built by {@code _work/build_ir_atgm_item_model.py}, drawn in
     * hand and in the inventory by the model itself and in flight by
     * {@code client/IrAtgmModelRenderer}. Nothing GeckoLib remains on this round: no geo file, no
     * {@code GeoAnimatable} item, no {@code BlockEntityWithoutLevelRenderer} - so the item is a
     * plain {@link FireControlMissileItem} and the icon is the model.</p>
     *
     * <p><b>TEMP-DISABLED(java-model)</b>: the GeckoLib airframe this replaces is still in the
     * tree ({@code client/model/IrAtgmMissileModel}, {@code client/IrAtgmMissileRenderer},
     * {@code client/IrAtgmItemRenderer}, {@code GeoMissileIconItem}) and the file
     * {@code geo/ir_atgm.geo.json} was moved to {@code _bak_src/ir_atgm_java_20261026/} - restoring
     * it is putting the geo back, swapping the two registrations below back and rebuilding
     * {@code models/item/ir_atgm.json} with {@code _work/convert_ir_atgm.py}.</p>
     */
    public static final DeferredHolder<Item, Item> IR_ATGM =
            ITEMS.register("ir_atgm",
                    () -> new FireControlMissileItem("item.firecontrolcompat.ir_atgm"));

    /**
     * How far a round of this mod is tracked by a client, in chunks.
     *
     * <p>Sixty-four chunks is <b>1024 blocks</b>, which answers the user's "it cannot be seen beyond
     * visual range" report (they asked for at least 512). It used to be <b>8</b> - 128 blocks - so
     * past that distance the server stopped telling the client the round existed at all, and no
     * renderer, however permissive, could draw what the client never received. That is also why a
     * separate long-range proxy existed, and why a round near the player could be drawn twice (the
     * tracked round plus the proxy; see {@code MissileVisualSupport#shouldSuppressNative}).</p>
     *
     * <p>{@code updateInterval} is the other half of the same report: at 20 the client was told where
     * the round was once a second - sixty blocks behind on a cruise leg. Two ticks is one packet per
     * 6 blocks at the anti-tank round's 1.2-1.6 blocks per tick, which vanilla interpolates
     * smoothly.</p>
     */
    private static final int MISSILE_TRACKING_CHUNKS = 64;

    /** Ticks between position packets for a round of this mod. */
    private static final int MISSILE_UPDATE_INTERVAL = 2;

    /** Entity type of the infrared anti-tank round's airframe. */
    public static final DeferredHolder<EntityType<?>, EntityType<IrAtgmMissileEntity>> IR_ATGM_TANSHE =
            ENTITY_TYPES.register("ir_atgm_tanshe",
                    () -> EntityType.Builder.<IrAtgmMissileEntity>of(IrAtgmMissileEntity::new, MobCategory.MISC)
                            .sized(0.6F, 0.6F)
                            .clientTrackingRange(MISSILE_TRACKING_CHUNKS)
                            .updateInterval(MISSILE_UPDATE_INTERVAL)
                            .build("ir_atgm_tanshe"));

    public static final DeferredHolder<EntityType<?>, EntityType<InfraredMissileEntity>> INFRARED_TANSHE =
            ENTITY_TYPES.register("infrared_tanshe",
                    () -> EntityType.Builder.<InfraredMissileEntity>of(InfraredMissileEntity::new, MobCategory.MISC)
                            .sized(0.6F, 0.6F)
                            .clientTrackingRange(MISSILE_TRACKING_CHUNKS)
                            .updateInterval(MISSILE_UPDATE_INTERVAL)
                            .build("infrared_tanshe"));

    /* RETIRED(ir-arm-rounds): the airborne infrared round's entity type. See the note above. */
    /*
     * TEMP-DISABLED(semi-active): the round's entity type. See the note above the item holders.
     *
     * public static final DeferredHolder<EntityType<?>, EntityType<com.qkdream.firecontrolcompat.entity.SemiActiveMissileEntity>> SEMI_ACTIVE_TANSHE =
     *         ENTITY_TYPES.register("semi_active_tanshe",
     *                 () -> EntityType.Builder.<com.qkdream.firecontrolcompat.entity.SemiActiveMissileEntity>of(
     *                                 com.qkdream.firecontrolcompat.entity.SemiActiveMissileEntity::new, MobCategory.MISC)
     *                         .sized(0.45F, 0.45F)
     *                         .clientTrackingRange(12)
     *                         .build("semi_active_tanshe"));
     */

    public static final DeferredHolder<EntityType<?>, EntityType<LoiteringMissileEntity>> LOITERING_TANSHE =
            ENTITY_TYPES.register("loitering_tanshe",
                    () -> EntityType.Builder.<LoiteringMissileEntity>of(LoiteringMissileEntity::new, MobCategory.MISC)
                            .sized(0.6F, 0.6F)
                            .clientTrackingRange(MISSILE_TRACKING_CHUNKS)
                            .updateInterval(MISSILE_UPDATE_INTERVAL)
                            .build("loitering_tanshe"));

    public static final DeferredHolder<EntityType<?>, EntityType<HeavyAirDefenseMissileEntity>> HEAVY_AA_TANSHE =
            ENTITY_TYPES.register("heavy_aa_tanshe",
                    () -> EntityType.Builder.<HeavyAirDefenseMissileEntity>of(HeavyAirDefenseMissileEntity::new, MobCategory.MISC)
                            .sized(0.6F, 0.6F)
                            .clientTrackingRange(MISSILE_TRACKING_CHUNKS)
                            .updateInterval(MISSILE_UPDATE_INTERVAL)
                            .build("heavy_aa_tanshe"));

    public static final DeferredHolder<EntityType<?>, EntityType<NevaAirDefenseMissileEntity>> NEVA_TANSHE =
            ENTITY_TYPES.register("neva_tanshe",
                    () -> EntityType.Builder.<NevaAirDefenseMissileEntity>of(NevaAirDefenseMissileEntity::new, MobCategory.MISC)
                            .sized(1.0F, 1.0F)
                            .clientTrackingRange(MISSILE_TRACKING_CHUNKS)
                            .updateInterval(1)
                            .build("neva_tanshe"));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FIRE_CONTROL_EYE_TAB =
            CREATIVE_TABS.register("fire_control_eye",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.firecontrolcompat.fire_control_eye"))
                            .icon(() -> new ItemStack(INFRARED_MISSILE.get()))
                            // One fixed order, the same one the combat tab below uses, so the page
                            // never depends on registration order: infrared, airborne infrared,
                            // semi-active, heavy, Neva, loitering, then everything else.
                            .displayItems((parameters, output) -> {
                                output.accept(INFRARED_MISSILE.get());
                                // The infrared anti-tank round, beside the round it is derived from.
                                output.accept(IR_ATGM.get());
                                // RETIRED(ir-arm-rounds): the airborne infrared round is the pack's
                                // own from its next version on.
                                // TEMP-DISABLED(semi-active): the round is not registered.
                                // output.accept(SEMI_ACTIVE_MISSILE.get());
                                output.accept(HEAVY_AIR_DEFENSE_MISSILE.get());
                                output.accept(NEVA_MISSILE.get());
                                output.accept(LOITERING_MUNITION.get());
                                output.accept(StrikeTerminalRegistry.STRIKE_TERMINAL.get());
                                if (FireControlCompat.isModLoaded("cbcmodernwarfare")) {
                                    CbcModernWarfareCompat.addToCreativeTab(output);
                                }
                                CbcBigCannonCompat.addToCreativeTab(output);
                                output.accept(SonarBlocks.SONAR_ITEM.get());
                                output.accept(AcousticTorpedoes.ACOUSTIC_TORPEDO_ITEM.get());
                                output.accept(WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO_ITEM.get());
                                output.accept(AcousticTorpedoes.AIRDROPPED_ACOUSTIC_TORPEDO_ITEM.get());
                                output.accept(TorpedoDecoy.TORPEDO_DECOY_ITEM.get());
                                output.accept(SonarBlocks.MANPORTABLE_SONAR_ITEM.get());
                                SilencerSupport.acceptTabItems(output::accept);
                            })
                            .build());

    private BeamMissileRegistry() {
    }

    /**
     * Prints what each of this mod's entity types was really registered with, once, at start-up.
     *
     * <p>The long-range report ("the round cannot be seen beyond visual range") has twice come down
     * to the numbers on the {@code EntityType.Builder}: first {@code clientTrackingRange(8)}, then a
     * suspected second {@code clientTrackingRange(8)} overriding the raised one. A build cannot prove
     * either - the value is only readable once the registry has been frozen - so this line is the
     * proof, read straight off {@link EntityType} itself rather than off the constant the builder was
     * given: {@code range=} is chunks times sixteen (the distance a client can be told about the
     * round), {@code chunks=} the raw figure, {@code interval=} the ticks between position packets.</p>
     *
     * <p>Called from {@code FireControlCompat#commonSetup}, which runs on both sides after
     * registration, so one start-up log covers the server and the client.</p>
     */
    public static void logTracking() {
        logTracking("ir_atgm_tanshe", IR_ATGM_TANSHE);
        logTracking("infrared_tanshe", INFRARED_TANSHE);
        logTracking("loitering_tanshe", LOITERING_TANSHE);
        logTracking("heavy_aa_tanshe", HEAVY_AA_TANSHE);
        logTracking("neva_tanshe", NEVA_TANSHE);
    }

    private static void logTracking(String id,
            DeferredHolder<EntityType<?>, ? extends EntityType<?>> holder) {
        try {
            EntityType<?> type = holder.get();
            int chunks = type.clientTrackingRange();
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] entity-type tracking: {} range={} chunks={} interval={}",
                    id, chunks * 16, chunks, type.updateInterval());
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] entity-type tracking: {} unreadable: {}",
                    id, String.valueOf(failure));
        }
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        ENTITY_TYPES.register(bus);
        CREATIVE_TABS.register(bus);
        bus.addListener(BeamMissileRegistry::registerRenderers);
        bus.addListener(BeamMissileRegistry::addCreativeTabItems);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Startup probe: entity renderer registration is one of the two client-side costs.
        long probeRenderers = StartupProbe.tick();
        event.registerEntityRenderer(INFRARED_TANSHE.get(), Fim92MissileRenderer::new);
        // The user's Spike-class airframe, drawn from its own Java item model
        // (models/item/ir_atgm.json) through ItemRenderer#renderStatic.
        event.registerEntityRenderer(IR_ATGM_TANSHE.get(), IrAtgmModelRenderer::new);
        /*
         * TEMP-DISABLED(java-model): the GeckoLib renderer these two used to register. Kept so
         * going back is swapping the two lines and putting geo/ir_atgm.geo.json back.
         *
         *   event.registerEntityRenderer(IR_ATGM_TANSHE.get(), IrAtgmMissileRenderer::new);
         */
        /*
         * RETIRED(ir-arm-rounds): the airborne infrared round has no renderer any more - the round
         * is not registered. Its renderer, its entity class and its model files were removed with
         * the rest of that mechanism (copies in {@code _bak_src/retire_ir_arm_20261025b}).
         */
        /*
         * TEMP-DISABLED(semi-active): the round is not registered, so it has no renderer.
         *
         *   event.registerEntityRenderer(SEMI_ACTIVE_TANSHE.get(),
         *           com.qkdream.firecontrolcompat.client.R40MissileRenderer::new);
         */
        event.registerEntityRenderer(LOITERING_TANSHE.get(), LoiteringMissileRenderer::new);
        // 2026-10-27 (user report: "not the model I used before, a blocky wrong one"): the two
        // heavy rounds go BACK to their GeckoLib airframes. The Java item route drew a faithful box -
        // closed octagonal body, tapered nose, four mid fins, four tail fins - and a box is exactly
        // what the report was about. The user's own Blockbench airframes were never the problem; the
        // geo files were always in the tree and are valid 1.12.0 Bedrock geometry:
        //   geo/48n6.geo.json  texture_size 128x128  bones=5   cubes=53
        //   geo/s125.geo.json  texture_size  64x64   bones=35  cubes=310
        // with their textures at textures/entity/48n6texture.png and s125texture.png.
        event.registerEntityRenderer(HEAVY_AA_TANSHE.get(), HeavyAaMissileRenderer::new);
        event.registerEntityRenderer(NEVA_TANSHE.get(), NevaMissileRenderer::new);
        // Registration proof: which renderer each type got, and which geo file GeckoLib will be
        // asked for. The "ir-atgm render: .. baked=" field then says whether that file baked,
        // and the cache= / reason= fields on the same line say why not when it did not.
        FireControlCompat.LOGGER.info(
                "renderer reg: heavy={} neva={} geo={} bones=5/35 cubes=53/310"
                        + " textures=entity/48n6texture.png+entity/s125texture.png",
                "GeoMissileRenderer(HeavyAaMissileModel,nosePlusZ)",
                "GeoMissileRenderer(NevaMissileModel,scale2.8)",
                "geo/48n6.geo.json+geo/s125.geo.json");
        /*
         * TEMP-DISABLED(blocky-airframe): the Java item route, kept one line away. Nothing registers
         * it now, and the two blocky airframe assets (models/item/heavy_aa_airframe.json,
         * neva_airframe.json and textures/item/*_airframe.png) stay on disk unreferenced - deleting
         * them would make the two non-gameplay airframe items log "unable to load model" on every
         * resource reload, which is noise rather than a fix. The airframe items stay registered for
         * the same reason; nothing puts them in a tab, a recipe or a drop.
         *
         *   event.registerEntityRenderer(HEAVY_AA_TANSHE.get(), context -> new JavaItemMissileRenderer<>(
         *           context, HEAVY_AA_AIRFRAME.get(), "heavy_aa_tanshe", 1.0F,
         *           ResourceLocation.fromNamespaceAndPath(FireControlCompat.MOD_ID,
         *                   "textures/entity/48n6texture.png"),
         *           HEAVY_AA_BODY_LENGTH, HEAVY_AA_BODY_RADIUS,
         *           new JavaItemMissileRenderer.Skin(128, 128, 0, 48, 9, 57, 22, 48, 27, 53)));
         *   event.registerEntityRenderer(NEVA_TANSHE.get(), context -> new JavaItemMissileRenderer<>(
         *           context, NEVA_AIRFRAME.get(), "neva_tanshe", 2.8F,
         *           ResourceLocation.fromNamespaceAndPath(FireControlCompat.MOD_ID,
         *                   "textures/entity/s125texture.png"),
         *           NEVA_BODY_LENGTH, NEVA_BODY_RADIUS,
         *           new JavaItemMissileRenderer.Skin(64, 64, 15, 18, 24, 27, 26, 36, 31, 41)));
         */
        event.registerBlockEntityRenderer(NevaLauncher.NEVA_LAUNCHER_BE.get(), NevaLauncherBlockRenderer::new);
        StartupProbe.stage("renderers", probeRenderers);
    }

    private static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        ResourceLocation key = event.getTabKey().location();
        // The breech is ammunition furniture, so it belongs beside the shells rather than in
        // this mod's own tab: any tab of the cannon family takes it.
        String tabPath = key.getPath();
        // This mod's own page carries everything it adds, so a crew does not have to hunt for the
        // new breech or the new rounds inside another mod's tabs.
        // The semi-active round belongs to this mod's own page, but it is already part of that
        // page's ordered item list, so accepting it here as well listed it twice and pushed it to
        // the end of the page.
        if (tabPath.contains("ammo") || tabPath.contains("shell") || tabPath.contains("cannon")) {
        }
        if (key.equals(ResourceLocation.withDefaultNamespace("combat"))
                || key.equals(ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "mianbaos_modern_warfare"))) {
            event.accept(INFRARED_MISSILE.get());
            event.accept(IR_ATGM.get());
            // RETIRED(ir-arm-rounds): see the note above the retired entity holder.
            // TEMP-DISABLED(semi-active): the round is not registered.
            // event.accept(SEMI_ACTIVE_MISSILE.get());
            event.accept(HEAVY_AIR_DEFENSE_MISSILE.get());
            event.accept(NEVA_MISSILE.get());
            event.accept(LOITERING_MUNITION.get());
            event.accept(StrikeTerminalRegistry.STRIKE_TERMINAL.get());
            if (FireControlCompat.isModLoaded("cbcmodernwarfare")) {
                CbcModernWarfareCompat.addToCreativeTab(event);
            }
            CbcBigCannonCompat.addToCreativeTab(event);
            event.accept(AcousticTorpedoes.ACOUSTIC_TORPEDO_ITEM.get());
            event.accept(WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO_ITEM.get());
            event.accept(AcousticTorpedoes.AIRDROPPED_ACOUSTIC_TORPEDO_ITEM.get());
            event.accept(TorpedoDecoy.TORPEDO_DECOY_ITEM.get());
            event.accept(SonarBlocks.MANPORTABLE_SONAR_ITEM.get());
        }
    }
}

