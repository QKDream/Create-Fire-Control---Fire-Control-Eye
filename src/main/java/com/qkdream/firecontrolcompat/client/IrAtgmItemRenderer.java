package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.GeoMissileIconItem;
import com.qkdream.firecontrolcompat.client.model.GeoMissileIconModel;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * The hand-held model of the infrared anti-tank round: the same
 * {@code geo/ir_atgm.geo.json} airframe the round flies with, drawn as the item.
 *
 * <h2>Where the transform lives</h2>
 *
 * <p>Not here. 0.9.4-era item rendering applies the item model's own
 * {@code display} block before the custom (GeckoLib) renderer is called - that is how vanilla's
 * {@code item/trident_in_hand} and {@code item/shield} are posed in hand, both of which are
 * {@code builtin/entity} items like this one ({@code ItemRenderer#render}:
 * {@code getTransforms().getTransform(context).apply(...)} then
 * {@code translate(-0.5,-0.5,-0.5)} then {@code blockEntityRenderer.renderByItem(...)}). So the
 * rotations, the translations and the per-context scales are all in
 * {@code assets/firecontrolcompat/models/item/ir_atgm.json}, one place, editable without a
 * rebuild, and GeckoLib only has to draw the airframe.</p>
 *
 * <p>This class therefore adds no transform of its own on top of
 * {@link GeoItemRenderer#preRender} (which scales by one and re-centres the model on the slot,
 * undoing vanilla's {@code -0.5} shift). Adding the diagonal icon rotation here <em>as well</em>
 * would double it, which is exactly what the sibling missiles do - their whole transform is in
 * {@code CenteredGeoItemRenderer} because their model files carry no {@code display} block at
 * all. This round's model file does, so its renderer stays out of the way.</p>
 *
 * <h2>The line this class exists to print</h2>
 *
 * <p>{@code ir-atgm item: renderer=geckolib geo=... scale=... context=...} once per display
 * context: creative tab / inventory ({@code gui}), hand ({@code firstperson_*},
 * {@code thirdperson_*}), dropped ({@code ground}) and item frame ({@code fixed}). A context that
 * never prints is a context the item was never drawn in, and the scale column names the number to
 * turn in {@code ir_atgm.json} when a slot looks too big or too small.</p>
 */
public class IrAtgmItemRenderer extends GeoItemRenderer<GeoMissileIconItem> {

    /** Display contexts already reported, so the log carries one line each and no spam. */
    private static final Set<String> REPORTED = new HashSet<>();

    private final String geoPath;

    private final String texturePath;

    public IrAtgmItemRenderer(String geoPath, String texturePath) {
        super(new GeoMissileIconModel<GeoMissileIconItem>(geoPath, texturePath));
        this.geoPath = geoPath;
        this.texturePath = texturePath;
    }

    @Override
    public void preRender(PoseStack poseStack, GeoMissileIconItem animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender,
                partialTick, packedLight, packedOverlay, colour);
        // Only the first draw of each context: the interesting facts are "did the custom renderer
        // run at all" and "at what size", and both are answers that do not change per frame.
        reportOnce();
    }

    private void reportOnce() {
        try {
            ItemDisplayContext context = this.renderPerspective;
            String name = context == null ? "unknown" : context.name();
            synchronized (REPORTED) {
                if (!REPORTED.add(name)) {
                    return;
                }
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ir-atgm item: renderer=geckolib geo={} texture={} scale={} context={} transform=item-model-display",
                    this.geoPath, this.texturePath, displayScale(context), name);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** The scale the item model's {@code display} block asks for in this context. */
    private static String displayScale(ItemDisplayContext context) {
        if (context == null) {
            return "-";
        }
        return switch (context) {
            case GUI -> "0.41";
            case FIXED -> "0.5";
            case GROUND -> "0.3";
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> "0.5";
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> "0.55";
            default -> "-";
        };
    }
}
