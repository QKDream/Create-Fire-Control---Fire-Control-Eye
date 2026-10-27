package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.GeoMissileIconItem;
import com.qkdream.firecontrolcompat.NevaLauncher;
import com.qkdream.firecontrolcompat.StartupProbe;
import com.qkdream.firecontrolcompat.client.model.GeoMissileIconModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * Registers GeckoLib item renderers so the Blockbench missile models show up
 * as the inventory icons of the corresponding missile items.
 *
 * <p>The baked geo model is in block units, and the models are authored
 * missile-length along Z. Each renderer therefore applies the vanilla GUI
 * rotation (30/225 degrees), scales the model so the diagonal fits the
 * 16 pixel slot and moves the bounding-box midpoint onto the slot center.
 * The transform runs inside {@code preRender}, which geckolib calls inside
 * the GUI 16x scale of {@code GuiGraphics.renderItem}.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class MissileItemRenderers {

    private MissileItemRenderers() {
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        // Startup probe: the GeckoLib item icon registration, the other client-side cost.
        long probeIcons = StartupProbe.tick();
        register(event, BeamMissileRegistry.INFRARED_MISSILE.get(),
                "geo/fim92.geo.json", "textures/entity/fim92texture.png",
                0.41F, new Vector3f(0.0F, 0.175F, 0.164F));
        /*
         * TEMP-DISABLED(new model, AIM-9): the airborne infrared round's icon goes back to the
         * 面包学 infra-red airframe ({@code infrared_missile}'s own geo), which is what the round
         * looked like before the Sidewinder model. The AIM-9 icon is kept, commented, so going
         * back is moving this block back in.
         *
         *   register(event, BeamMissileRegistry.AIRCRAFT_INFRARED_MISSILE.get(),
         *           "geo/aim9.geo.json", "textures/entity/aim9texture.png",
         *           0.41F, new Vector3f(0.0F, 0.175F, 0.164F));
         *
         * TEMP-DISABLED(ir-icon, 面包学 model): the hand-held model of the airborne infrared round
         * is now 面包学's own 近程空对空导弹 airframe - the same model its {@code closemissile_1}
         * item carries - through this item's model file
         * ({@code models/item/aircraft_infrared_missile.json} parents
         * {@code mianbaos_modernwarfare:custom/jin_ju_chi_ge_dou_dao_dan_}). A registered
         * GeckoLib item renderer would draw over that model, so no icon is registered here any
         * more. The round's own name is untouched: the weapon slot still reads 空射红外弹 while our
         * round is on the rail (see {@code AircraftWeaponTypeLabelMixin}), and 面包学's own
         * {@code closemissile_1} still reads 近程空对空导弹.
         *
         *   register(event, BeamMissileRegistry.AIRCRAFT_INFRARED_MISSILE.get(),
         *           "geo/fim92.geo.json", "textures/entity/fim92texture.png",
         *           0.41F, new Vector3f(0.0F, 0.175F, 0.164F));
         */
        /*
         * TEMP-DISABLED(semi-active): the round is not registered, so it has no icon. The R-40
         * icon registration is kept, commented.
         *
         *   register(event, BeamMissileRegistry.SEMI_ACTIVE_MISSILE.get(),
         *           "geo/r40.geo.json", "textures/entity/r40_texture.png",
         *           0.34F * 1.5F, new Vector3f(0.0F, 0.125F, 0.0F));
         */
        register(event, BeamMissileRegistry.HEAVY_AIR_DEFENSE_MISSILE.get(),
                "geo/48n6.geo.json", "textures/entity/48n6texture.png",
                0.34F, new Vector3f(0.0F, 0.125F, -0.074F));
        register(event, BeamMissileRegistry.NEVA_MISSILE.get(),
                "geo/s125.geo.json", "textures/entity/s125texture.png",
                0.74F, new Vector3f(0.099F, -0.013F, -0.308F));
        register(event, NevaLauncher.NEVA_LAUNCHER_ITEM.get(),
                "geo/s125.geo.json", "textures/entity/s125texture.png",
                0.74F, new Vector3f(0.099F, -0.013F, -0.308F));
        // TEMP-DISABLED(java-model): the infrared anti-tank round's airframe is a plain Java item
        // model now (models/item/ir_atgm.json: thirteen elements, six textures), so it needs no
        // GeckoLib renderer at all - the item model draws itself in the inventory, in hand and on
        // the ground, and the round in flight is drawn from the same model by
        // client/IrAtgmModelRenderer. Registering an icon here would draw over that model again.
        //
        //   registerAirframe(event, BeamMissileRegistry.IR_ATGM.get(),
        //           "geo/ir_atgm.geo.json", "textures/entity/ir_atgm_texture.png");
        StartupProbe.stage("item-icons", probeIcons);
    }

    /**
     * Registers the plain GeckoLib item renderer, i.e. the one that lets the item model's own
     * {@code display} block do the posing.
     *
     * <p>Used for the rounds whose {@code models/item/*.json} is a {@code builtin/entity} model with
     * a full {@code display} block, so there is exactly one place the transform lives.</p>
     */
    private static void registerAirframe(RegisterClientExtensionsEvent event, Item item,
            String geoPath, String texturePath) {
        IrAtgmItemRenderer renderer = new IrAtgmItemRenderer(geoPath, texturePath);
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        }, item);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(
            RegisterClientExtensionsEvent event,
            Item item,
            String geoPath,
            String texturePath,
            float scale,
            Vector3f center
    ) {
        GeoModel model = new GeoMissileIconModel<>(geoPath, texturePath);
        CenteredGeoItemRenderer renderer = new CenteredGeoItemRenderer<>(model, scale, center);
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        }, item);
    }

    /** Icon renderer that lays the missile diagonally across the item slot. */
    private static final class CenteredGeoItemRenderer<T extends Item & software.bernie.geckolib.animatable.GeoAnimatable>
            extends GeoItemRenderer<T> {

        private static final float GUI_ROT_X = (float) Math.toRadians(30.0F);
        private static final float GUI_ROT_Y = (float) Math.toRadians(225.0F);

        private final Vector3f center;
        private final float scale;

        CenteredGeoItemRenderer(GeoModel<T> model, float scale, Vector3f center) {
            super(model);
            this.scale = scale;
            this.center = center;
        }

        @Override
        public void preRender(PoseStack poseStack, T animatable, BakedGeoModel model,
                              MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                              float partialTick, int packedLight, int packedOverlay, int colour) {
            super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                    partialTick, packedLight, packedOverlay, colour);
            poseStack.translate(-this.center.x, -this.center.y, -this.center.z);
            poseStack.scale(this.scale, this.scale, this.scale);
            poseStack.mulPose(new Quaternionf().rotationXYZ(GUI_ROT_X, GUI_ROT_Y, 0.0F));
        }
    }
}
