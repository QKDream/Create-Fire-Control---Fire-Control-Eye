package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws a missile airframe with no GeckoLib in the path: a <b>primitively drawn box airframe</b> by
 * default, the round's Java item model on request.
 *
 * <h2>History, in order, because each step removed one failure</h2>
 *
 * <ol>
 *   <li>The rounds were drawn by {@link GeoMissileRenderer} (GeckoLib). "Trail, no body": GeckoLib
 *       draws nothing, silently, when its model has no baked geometry.</li>
 *   <li>Moved to a Java item model. That round's log said
 *       {@code Missing textures in model firecontrolcompat:heavy_aa_airframe#inventory} - the model
 *       pointed at {@code textures/entity/**}, and {@code ItemRenderer#renderStatic} bakes against
 *       the <b>block</b> atlas, which only collects {@code textures/block/**} and
 *       {@code textures/item/**}. Fixed by shipping the art as {@code textures/item/<name>.png}
 *       (see {@code _work/audit_airframe_models.py}).</li>
 *   <li>Then nothing was drawn at all, because the round is flown mathematically and the only page
 *       that sends the client a proxy was never subscribed - see {@code MathGuidedMissiles}.</li>
 *   <li>Then the proxy arrived and the model baked ({@code mode=item model=baked}) and the report was
 *       <b>black, mangled missiles</b>. A baked model drawn at full brightness cannot be black from
 *       lighting, so what is left is the model pipeline itself: an item model's vertices are
 *       atlas-UV and are handed to whatever render type the BVR pass supplies, and any of those can
 *       flatten the result without an error.</li>
 * </ol>
 *
 * <h2>What is drawn now</h2>
 *
 * <p><b>Default: the primitive airframe.</b> A box body, a tapered nose and four tail fins drawn
 * straight into a {@link VertexConsumer} with</p>
 * <ul>
 *   <li>an <b>explicit per-face UV rectangle</b> taken from the round's own texture - the near-white
 *       body panel for the body, the grey panel for the fins - so the result is a grey-white body
 *       with dark fins, never a flat colour and never atlas UVs;</li>
 *   <li>{@code RenderType.entityCutoutNoCull(texture)}: an <b>entity</b> render type binds a raw
 *       texture path, so no atlas entry, no sprite lookup and no model json is involved at all;</li>
 *   <li>{@code LightTexture.FULL_BRIGHT}, because a round hundreds of blocks up is in a chunk whose
 *       light the client may not have, and zero light is a black model;</li>
 *   <li>counter-clockwise winding with outward normals on every body quad (a reversed quad is lit
 *       from behind and reads as black) and double-sided fins;</li>
 *   <li>the shader colour reset to white first, so a tint left behind by another mod's pass cannot
 *       paint the round black.</li>
 * </ul>
 *
 * <p><b>Opt in: the item model</b>, with {@code airframeItemModel=true} in
 * {@code config/firecontrolcompat-pod.txt} (or {@code -Dfirecontrolcompat.airframeItemModel=true}).
 * It reports {@code mode=item uv=atlas}, so the two routes are never confused in the log.</p>
 *
 * <h2>Heading</h2>
 *
 * <p>Model space: nose at <b>-Z</b>. The direction comes from the position step between two ticks
 * first (a mathematical round is teleported by payload, so its synced rotation lags), the entity's
 * own delta movement second, the synced rotation last; a round on a rail uses the rail's yaw.</p>
 *
 * @param <T> the round it draws
 */
public class JavaItemMissileRenderer<T extends BeamRidingMissileEntity> extends EntityRenderer<T> {

    /**
     * One airframe's painted regions, in texture pixels.
     *
     * <p>Produced by {@code _work/build_heavy_airframes.py}'s {@code pick_regions}: {@code body} is
     * the brightest large panel (the near-white body colour) and {@code fin} a dark panel, both
     * verified fully opaque, which is what makes them safe to cut out with a per-face UV rectangle
     * under {@code entityCutoutNoCull}.</p>
     */
    public record Skin(int width, int height,
                       int bodyU, int bodyV, int bodyU2, int bodyV2,
                       int finU, int finV, int finU2, int finV2) {
    }

    /** The airframe item whose model the item route draws. */
    private final ItemStack airframe;

    /** Registry path of the round type, for the log. */
    private final String type;

    /** Uniform scale, so the S-125 keeps the size of the launcher that fires it. */
    private final float scale;

    /** The airframe texture: the base-class contract, the primitive's material, the log name. */
    private final ResourceLocation texture;

    /** Primitive airframe length (nose to tail) in blocks, before {@link #scale}. */
    private final float bodyLength;

    /** Primitive airframe half-width in blocks, before {@link #scale}. */
    private final float bodyRadius;

    /** Painted regions of {@link #texture}. */
    private final Skin skin;

    /** Last model state seen on the item route, for the report line. */
    private String modelState = "unknown";

    /** Whether the item route is allowed, read once from the config file or the system property. */
    private static final boolean ITEM_MODEL_ROUTE = firecontrolcompat$readItemModelRoute();

    /** Whether only the first quad of the airframe model is drawn (diagnostic). */
    private static final boolean ONE_QUAD = firecontrolcompat$readOneQuad();

    /** The model evidence line, printed once per renderer on the first successful item draw. */
    private boolean evidencePrinted;

    /** Measured length of the baked model along its nose axis, in blocks; -1 until measured. */
    private float modelLength = -1.0F;

    /** Measured size of the baked model, in blocks; zero until measured. */
    private Vec3 modelSize = Vec3.ZERO;

    /** Measured centre of the baked model relative to the round's origin, in blocks. */
    private Vec3 modelCentre = Vec3.ZERO;

    /** {@code x,y,z} with two decimals, for the log. */
    private String firecontrolcompat$format(Vec3 value) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", value.x, value.y, value.z);
    }

    public JavaItemMissileRenderer(EntityRendererProvider.Context context, Item airframe,
                                   String type, float scale, ResourceLocation texture,
                                   float bodyLength, float bodyRadius, Skin skin) {
        super(context);
        this.airframe = new ItemStack(airframe);
        this.type = type;
        this.scale = scale;
        this.texture = texture;
        this.bodyLength = bodyLength;
        this.bodyRadius = bodyRadius;
        this.skin = skin;
        this.shadowRadius = 0.0F;
        FireControlCompat.LOGGER.info(
                "ir-atgm renderer: type={} renderer=JavaItemMissileRenderer airframe={} texture={}"
                        + " scale={} primitive={}x{} route={}",
                type, airframe, texture, String.format(Locale.ROOT, "%.2f", scale),
                String.format(Locale.ROOT, "%.1f", bodyLength),
                String.format(Locale.ROOT, "%.2f", bodyRadius),
                ITEM_MODEL_ROUTE ? "item-model" : "primitive");
    }

    /**
     * {@code airframeItemModel=false} in {@code config/firecontrolcompat-pod.txt}, or
     * {@code -Dfirecontrolcompat.airframeItemModel=false}, drops these two rounds to the primitive
     * airframe.
     *
     * <p><b>On by default again (2026-10-27, second attempt).</b> It was turned off when the model
     * drew black, but every cause of that black has since been fixed on this path: the light handed
     * to {@code renderStatic} is {@link LightTexture#FULL_BRIGHT} rather than a {@code packedLight}
     * taken from a chunk the client may not have lit, the shader colour is reset to white before the
     * draw, and the texture is an atlas sprite under {@code textures/item/**}
     * ({@code _work/audit_airframe_models.py} fails the review if it is not). The primitive route
     * stays as the <b>fallback</b> - it is taken only when the model is missing, empty, unloadable or
     * throws - because the box airframe is what the user saw as "the shape is wrong".</p>
     */
    private static boolean firecontrolcompat$readItemModelRoute() {
        try {
            String property = System.getProperty("firecontrolcompat.airframeItemModel");
            if (property != null) {
                return Boolean.parseBoolean(property.trim());
            }
        } catch (Throwable ignored) {
            // a restricted environment: fall through to the file
        }
        try {
            Path path = Path.of("config/firecontrolcompat-pod.txt");
            if (!Files.isRegularFile(path)) {
                return true;
            }
            for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().replace(" ", "");
                if (text.startsWith("airframeItemModel=")) {
                    return Boolean.parseBoolean(text.substring("airframeItemModel=".length()));
                }
            }
        } catch (Throwable ignored) {
            // no config: the item model is the picture
        }
        return true;
    }

    /**
     * {@code airframeOneQuad=true} draws only the first quad of the airframe model.
     *
     * <p>The diagnostic the last round asked for: if one quad takes its colour, the model, the
     * sprite and the lighting are all fine and the fault is in how the whole list is drawn; if even
     * one quad comes out black, the fault is the sprite or the vertex colour. Off by default,
     * switchable without a rebuild.</p>
     */
    private static boolean firecontrolcompat$readOneQuad() {
        try {
            String property = System.getProperty("firecontrolcompat.airframeOneQuad");
            if (property != null) {
                return Boolean.parseBoolean(property.trim());
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Path path = Path.of("config/firecontrolcompat-pod.txt");
            if (!Files.isRegularFile(path)) {
                return false;
            }
            for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().replace(" ", "");
                if (text.startsWith("airframeOneQuad=")) {
                    return Boolean.parseBoolean(text.substring("airframeOneQuad=".length()));
                }
            }
        } catch (Throwable ignored) {
            // no config: the whole model
        }
        return false;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        if (MissileVisualSupport.shouldSuppressNative(entity)) {
            // The proxy is the picture on fire control's BVR chain - but only once that proxy exists.
            // Reporting the suppression is what tells "the pack draws it" from "nobody draws it".
            firecontrolcompat$reportSuppressed(entity);
            return;
        }
        firecontrolcompat$countDraw();
        MissileVisualSupport.spawnLongRangeTrail(entity);
        Step step = STEPS.computeIfAbsent(entity, key -> new Step());
        poseStack.pushPose();
        this.firecontrolcompat$orient(entity, step, poseStack, partialTicks);
        // The scale belongs to the round, not to the drawing method: applying it here keeps the
        // primitive airframe the same size as the item model it stands in for.
        if (this.scale != 1.0F) {
            poseStack.scale(this.scale, this.scale, this.scale);
        }
        boolean drawn = false;
        if (ITEM_MODEL_ROUTE) {
            // Past the far plane fire control hands renderers a buffer pinned to its own texture;
            // bufferFor answers one that keeps the block atlas, which is what an item model's
            // atlas-UV vertices need.
            MultiBufferSource target = MissileVisualSupport.bufferFor(
                    entity, buffer, TextureAtlas.LOCATION_BLOCKS);
            drawn = this.firecontrolcompat$drawItemModel(entity, poseStack, target);
        }
        if (!drawn) {
            step.draw = "primitive";
            this.firecontrolcompat$drawPrimitive(poseStack, buffer);
        } else {
            step.draw = "item";
        }
        poseStack.popPose();
        firecontrolcompat$report(entity, step, drawn);
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    /**
     * Draws the airframe item model.
     *
     * <p>Three things happen here for a reason, and each one used to make this model draw black:</p>
     * <ul>
     *   <li>the shader colour is reset to white first - the BVR pass this renderer is called from
     *       runs in {@code AFTER_LEVEL} and any tint left behind by an earlier pass multiplies every
     *       vertex to black;</li>
     *   <li>the light handed to {@code renderStatic} is {@link LightTexture#FULL_BRIGHT}, not the
     *       {@code packedLight} the caller passed - these rounds fly hundreds of blocks up and out
     *       of the client's lit chunks, and lightmap zero is a black model;</li>
     *   <li>the texture is an atlas sprite under {@code textures/item/**}, which is the only place
     *       {@code renderStatic} can resolve one (see {@code _work/audit_airframe_models.py}).</li>
     * </ul>
     *
     * @return false when the model is not there (missing sprite, empty model, no loader, a throw):
     *         the caller then draws the primitive airframe
     */
    private boolean firecontrolcompat$drawItemModel(T entity, PoseStack poseStack, MultiBufferSource buffer) {
        BakedModel model = this.firecontrolcompat$airframeModel(entity);
        if (this.firecontrolcompat$isEmptyModel(model)) {
            return false;
        }
        this.modelState = "baked";
        try {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (ONE_QUAD) {
                // Diagnostic: one quad, drawn by this mod's own vertex writer, with the sprite's own
                // atlas rectangle and full brightness. If a single quad takes its colour, the model,
                // the sprite and the light are all fine; if it does not, the sprite's UVs or the
                // vertex colour are the fault - and nothing else can hide behind the model list.
                this.firecontrolcompat$drawOneQuad(model, poseStack, buffer);
            } else {
                Minecraft.getInstance().getItemRenderer().renderStatic(this.airframe,
                        ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        poseStack, buffer, entity.level(), entity.getId());
            }
        } catch (Throwable failure) {
            this.modelState = "threw:" + failure.getClass().getSimpleName();
            return false;
        }
        this.firecontrolcompat$printItemEvidence(model);
        return true;
    }

    /**
     * One quad of the baked model, drawn straight into an entity buffer.
     *
     * <p>Positions and UVs are read out of the quad's own vertex data (the baked format is
     * position/colour/uv/light/normal, so uv sits at offsets 4 and 5 of each eight-int vertex), and
     * the {@code -0.5} half-block centring {@code renderStatic} applies is applied here too, so the
     * quad lands where the model would have put it.</p>
     */
    private void firecontrolcompat$drawOneQuad(BakedModel model, PoseStack poseStack, MultiBufferSource buffer) {
        RandomSource random = RandomSource.create(42L);
        java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                new java.util.ArrayList<>(model.getQuads(null, null, random));
        for (Direction side : Direction.values()) {
            quads.addAll(model.getQuads(null, side, random));
        }
        if (quads.isEmpty()) {
            return;
        }
        net.minecraft.client.renderer.block.model.BakedQuad quad = quads.get(0);
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        if (stride < 8) {
            return;
        }
        VertexConsumer into = buffer.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        Matrix4f matrix = poseStack.last().pose();
        PoseStack.Pose pose = poseStack.last();
        float[] xs = new float[4];
        float[] ys = new float[4];
        float[] zs = new float[4];
        float[] us = new float[4];
        float[] vs = new float[4];
        for (int index = 0; index < 4; index++) {
            int base = index * stride;
            xs[index] = Float.intBitsToFloat(vertices[base]) - 0.5F;
            ys[index] = Float.intBitsToFloat(vertices[base + 1]) - 0.5F;
            zs[index] = Float.intBitsToFloat(vertices[base + 2]) - 0.5F;
            us[index] = Float.intBitsToFloat(vertices[base + 4]);
            vs[index] = Float.intBitsToFloat(vertices[base + 5]);
        }
        for (int index = 0; index < 4; index++) {
            into.addVertex(matrix, xs[index], ys[index], zs[index])
                    .setColor(255, 255, 255, 255)
                    .setUv(us[index], vs[index])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT)
                    .setNormal(pose, 0.0F, 1.0F, 0.0F);
        }
        FireControlCompat.LOGGER.info(
                "ir-atgm render: type={} quad=1 uv-atlas={}/{} {}/{} {}/{} {}/{} sprite={}",
                this.type,
                String.format(Locale.ROOT, "%.4f", us[0]), String.format(Locale.ROOT, "%.4f", vs[0]),
                String.format(Locale.ROOT, "%.4f", us[1]), String.format(Locale.ROOT, "%.4f", vs[1]),
                String.format(Locale.ROOT, "%.4f", us[2]), String.format(Locale.ROOT, "%.4f", vs[2]),
                String.format(Locale.ROOT, "%.4f", us[3]), String.format(Locale.ROOT, "%.4f", vs[3]),
                quad.getSprite() == null ? "-" : quad.getSprite().contents().name());
    }

    /**
     * One line, once, with everything needed to tell a texture fault from a light fault from a
     * geometry fault: the sprite the model actually resolves to, that sprite's atlas rectangle, the
     * quad count, and the model's measured length along its nose axis.
     */
    private void firecontrolcompat$printItemEvidence(BakedModel model) {
        if (this.evidencePrinted) {
            return;
        }
        this.evidencePrinted = true;
        try {
            RandomSource random = RandomSource.create(42L);
            java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                    new java.util.ArrayList<>(model.getQuads(null, null, random));
            for (Direction side : Direction.values()) {
                quads.addAll(model.getQuads(null, side, random));
            }
            java.util.Set<String> sprites = new java.util.LinkedHashSet<>();
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            float u0 = 1.0F;
            float v0 = 1.0F;
            float u1 = 0.0F;
            float v1 = 0.0F;
            for (net.minecraft.client.renderer.block.model.BakedQuad quad : quads) {
                if (quad.getSprite() != null) {
                    sprites.add(quad.getSprite().contents().name()
                            + "@" + String.format(Locale.ROOT, "%.3f..%.3f/%.3f..%.3f",
                            quad.getSprite().getU0(), quad.getSprite().getU1(),
                            quad.getSprite().getV0(), quad.getSprite().getV1()));
                }
                int[] vertices = quad.getVertices();
                int stride = vertices.length / 4;
                if (stride < 8) {
                    continue;
                }
                for (int index = 0; index < 4; index++) {
                    int base = index * stride;
                    float x = Float.intBitsToFloat(vertices[base]);
                    float y = Float.intBitsToFloat(vertices[base + 1]);
                    float z = Float.intBitsToFloat(vertices[base + 2]);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                    float u = Float.intBitsToFloat(vertices[base + 4]);
                    float v = Float.intBitsToFloat(vertices[base + 5]);
                    u0 = Math.min(u0, u);
                    v0 = Math.min(v0, v);
                    u1 = Math.max(u1, u);
                    v1 = Math.max(v1, v);
                }
            }
            if (maxZ > minZ) {
                this.modelLength = maxZ - minZ;
                this.modelSize = new Vec3(maxX - minX, maxY - minY, maxZ - minZ);
                // The bake puts a Java item model in the 0..1 unit cube and renderStatic subtracts
                // 0.5, so the centre that matters for "is the model shifted next to the round" is
                // the measured centre minus that half block.
                this.modelCentre = new Vec3((maxX + minX) * 0.5 - 0.5,
                        (maxY + minY) * 0.5 - 0.5, (maxZ + minZ) * 0.5 - 0.5);
            }
            FireControlCompat.LOGGER.info(
                    "ir-atgm item route: type={} light=FULL_BRIGHT({}) shader-colour=1,1,1,1 model={}"
                            + " quads={} sprites={} vertex-uv={} length={} center={} size={}"
                            + " scale={} one-quad={} texture={}",
                    this.type, LightTexture.FULL_BRIGHT, this.modelState, quads.size(), sprites,
                    String.format(Locale.ROOT, "%.3f..%.3f/%.3f..%.3f", u0, u1, v0, v1),
                    String.format(Locale.ROOT, "%.2f", this.modelLength),
                    this.firecontrolcompat$format(this.modelCentre),
                    this.firecontrolcompat$format(this.modelSize),
                    String.format(Locale.ROOT, "%.2f", this.scale), ONE_QUAD, this.texture);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("ir-atgm item route: type={} evidence failed: {}",
                    this.type, String.valueOf(failure));
        }
    }

    /** The baked model of the airframe item, or null when the renderer is not ready. */
    private BakedModel firecontrolcompat$airframeModel(T entity) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getItemRenderer() == null) {
                return null;
            }
            return minecraft.getItemRenderer().getModel(this.airframe, entity.level(), null, entity.getId());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Whether a baked model has nothing to draw: the missing model, or a model whose every quad was
     * dropped.
     */
    private boolean firecontrolcompat$isEmptyModel(BakedModel model) {
        if (model == null) {
            this.modelState = "no-loader";
            return true;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getModelManager() != null
                    && model == minecraft.getModelManager().getMissingModel()) {
                this.modelState = "missing";
                return true;
            }
            RandomSource random = RandomSource.create(42L);
            java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads =
                    new java.util.ArrayList<>(model.getQuads(null, null, random));
            for (Direction side : Direction.values()) {
                quads.addAll(model.getQuads(null, side, random));
            }
            if (quads.isEmpty()) {
                this.modelState = "empty";
                return true;
            }
            // The purple/black checkerboard: the model baked, the quads are there, and the sprite
            // they resolve to is the MISSING sprite - the atlas could not find the file. Drawing that
            // says nothing about the round and everything about a resource path, so it is treated
            // like a missing model and the primitive airframe takes over (raw texture path, no atlas).
            for (net.minecraft.client.renderer.block.model.BakedQuad quad : quads) {
                net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = quad.getSprite();
                if (sprite == null) {
                    continue;
                }
                ResourceLocation name = sprite.contents().name();
                // The missing sprite's contents are named minecraft:missingno; there is no usable
                // subtype to test against (MissingTextureAtlasSprite is not a TextureAtlasSprite in
                // 1.21.1), so the name is the test.
                if (name == null || "minecraft:missingno".equals(name.toString())) {
                    this.modelState = "missing-sprite(" + name + ")";
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            this.modelState = "unreadable";
            return true;
        }
    }

    /**
     * The airframe without any model json: box body, tapered nose, four tail fins.
     *
     * <p>Every face carries an explicit UV rectangle cut from the round's own texture (see
     * {@link Skin}), every body quad is wound counter-clockwise as seen from outside with a matching
     * outward normal, the light is {@link LightTexture#FULL_BRIGHT}, and the shader colour is reset
     * to white first. Those four things are the whole difference between "a grey-white missile with
     * dark fins" and "a black jumble".</p>
     */
    private void firecontrolcompat$drawPrimitive(PoseStack poseStack, MultiBufferSource buffer) {
        try {
            // Defensive: any tint left behind by an earlier pass would multiply the round to black.
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            VertexConsumer into = buffer.getBuffer(RenderType.entityCutoutNoCull(this.texture));
            Matrix4f matrix = poseStack.last().pose();
            PoseStack.Pose pose = poseStack.last();
            int light = LightTexture.FULL_BRIGHT;
            float half = this.bodyLength * 0.5F;
            float r = this.bodyRadius;
            float nose = -half;
            float tail = half;
            float[] body = this.firecontrolcompat$rect(this.skin.bodyU(), this.skin.bodyV(),
                    this.skin.bodyU2(), this.skin.bodyV2());
            float[] fin = this.firecontrolcompat$rect(this.skin.finU(), this.skin.finV(),
                    this.skin.finU2(), this.skin.finV2());
            // --- body: six faces, outward normals, counter-clockwise seen from outside ----------
            this.firecontrolcompat$quad(into, pose, matrix, light, body,
                    -r, -r, tail, r, -r, tail, r, -r, nose, -r, -r, nose, 0.0F, -1.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, body,
                    -r, r, nose, r, r, nose, r, r, tail, -r, r, tail, 0.0F, 1.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, body,
                    r, -r, tail, r, -r, nose, r, r, nose, r, r, tail, 1.0F, 0.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, body,
                    -r, -r, nose, -r, -r, tail, -r, r, tail, -r, r, nose, -1.0F, 0.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, body,
                    -r, -r, nose, -r, r, nose, r, r, nose, r, -r, nose, 0.0F, 0.0F, -1.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, body,
                    -r, -r, tail, r, -r, tail, r, r, tail, -r, r, tail, 0.0F, 0.0F, 1.0F);
            // --- tapered nose: a smaller box on the front, so it reads as a missile ------------
            float nr = r * 0.55F;
            float nTip = nose - this.bodyLength * 0.22F;
            this.firecontrolcompat$quad(into, pose, matrix, light, fin,
                    -nr, -nr, nTip, nr, -nr, nTip, nr, -nr, nose, -nr, -nr, nose, 0.0F, -1.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, fin,
                    -nr, nr, nose, nr, nr, nose, nr, nr, nTip, -nr, nr, nTip, 0.0F, 1.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, fin,
                    nr, -nr, nTip, nr, -nr, nose, nr, nr, nose, nr, nr, nTip, 1.0F, 0.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, fin,
                    -nr, -nr, nose, -nr, -nr, nTip, -nr, nr, nTip, -nr, nr, nose, -1.0F, 0.0F, 0.0F);
            this.firecontrolcompat$quad(into, pose, matrix, light, fin,
                    -nr, -nr, nTip, -nr, nr, nTip, nr, nr, nTip, nr, -nr, nTip, 0.0F, 0.0F, -1.0F);
            // --- four tail fins, double sided --------------------------------------------------
            float span = r * 2.8F;
            float root = tail - this.bodyLength * 0.30F;
            this.firecontrolcompat$fin(into, pose, matrix, light, fin,
                    -span, -r, tail, span, -r, tail, span, -r, root, -span, -r, root, 0.0F, -1.0F, 0.0F);
            this.firecontrolcompat$fin(into, pose, matrix, light, fin,
                    -span, r, root, span, r, root, span, r, tail, -span, r, tail, 0.0F, 1.0F, 0.0F);
            this.firecontrolcompat$fin(into, pose, matrix, light, fin,
                    r, -span, tail, r, -span, root, r, span, root, r, span, tail, 1.0F, 0.0F, 0.0F);
            this.firecontrolcompat$fin(into, pose, matrix, light, fin,
                    -r, -span, root, -r, -span, tail, -r, span, tail, -r, span, root, -1.0F, 0.0F, 0.0F);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("ir-atgm render: primitive airframe failed type={} reason={}",
                    this.type, String.valueOf(failure));
        }
    }

    /** A texture rectangle in pixels, as {@code {u0, v0, u1, v1}} in 0..1 of the image. */
    private float[] firecontrolcompat$rect(int u0, int v0, int u1, int v1) {
        float width = Math.max(1, this.skin.width());
        float height = Math.max(1, this.skin.height());
        return new float[]{u0 / width, v0 / height, u1 / width, v1 / height};
    }

    /** One quad with an outward normal; the four corners are counter-clockwise seen from outside. */
    private void firecontrolcompat$quad(VertexConsumer into, PoseStack.Pose pose, Matrix4f matrix, int light,
                                        float[] uv,
                                        float x0, float y0, float z0, float x1, float y1, float z1,
                                        float x2, float y2, float z2, float x3, float y3, float z3,
                                        float nx, float ny, float nz) {
        this.firecontrolcompat$vertex(into, pose, matrix, light, x0, y0, z0, uv[0], uv[3], nx, ny, nz);
        this.firecontrolcompat$vertex(into, pose, matrix, light, x1, y1, z1, uv[2], uv[3], nx, ny, nz);
        this.firecontrolcompat$vertex(into, pose, matrix, light, x2, y2, z2, uv[2], uv[1], nx, ny, nz);
        this.firecontrolcompat$vertex(into, pose, matrix, light, x3, y3, z3, uv[0], uv[1], nx, ny, nz);
    }

    /** A fin: the same quad twice with opposite normals, so it is visible from both sides. */
    private void firecontrolcompat$fin(VertexConsumer into, PoseStack.Pose pose, Matrix4f matrix, int light,
                                       float[] uv,
                                       float x0, float y0, float z0, float x1, float y1, float z1,
                                       float x2, float y2, float z2, float x3, float y3, float z3,
                                       float nx, float ny, float nz) {
        this.firecontrolcompat$quad(into, pose, matrix, light, uv,
                x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3, nx, ny, nz);
        this.firecontrolcompat$quad(into, pose, matrix, light, uv,
                x3, y3, z3, x2, y2, z2, x1, y1, z1, x0, y0, z0, -nx, -ny, -nz);
    }

    private void firecontrolcompat$vertex(VertexConsumer into, PoseStack.Pose pose, Matrix4f matrix, int light,
                                          float x, float y, float z, float u, float v,
                                          float nx, float ny, float nz) {
        into.addVertex(matrix, x, y, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    /** Lays the airframe's nose (-Z) onto the direction this round is travelling. */
    private void firecontrolcompat$orient(T entity, Step step, PoseStack poseStack, float partialTick) {
        firecontrolcompat$track(entity, step);
        if (entity.firecontrolcompat$isMounted()) {
            step.mode = "rail";
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-entity.getYRot() + 180.0F));
            return;
        }
        Vec3 direction = step.direction;
        if (direction == null) {
            float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
            float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
            step.mode = "entity";
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - yaw));
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-pitch));
            return;
        }
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        float yaw = (float) (Mth.atan2(-direction.x, direction.z) * (180.0 / Math.PI));
        float pitch = (float) (-Mth.atan2(direction.y, horizontal) * (180.0 / Math.PI));
        step.mode = step.fromStep ? "item-step" : "item-motion";
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - yaw));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-pitch));
    }

    /**
     * The direction this round is travelling: the position step between two ticks first (exact for a
     * mathematical round, which payloads teleport rather than steer), the synced velocity second.
     */
    private void firecontrolcompat$track(T entity, Step step) {
        Vec3 now = entity.position();
        int tick = entity.tickCount;
        if (step.tick != tick) {
            if (step.tick != Integer.MIN_VALUE) {
                Vec3 delta = now.subtract(step.position);
                if (delta.lengthSqr() > 1.0E-6D) {
                    step.direction = delta.normalize();
                    step.fromStep = true;
                }
            }
            step.tick = tick;
            step.position = now;
        }
        if (!step.fromStep) {
            Vec3 velocity = entity.getDeltaMovement();
            if (velocity != null && velocity.lengthSqr() > 1.0E-6D) {
                step.direction = velocity.normalize();
                step.fromStep = false;
            }
        }
    }

    /** Per-round bookkeeping: the last position seen, and the direction its step gave. */
    private static final class Step {
        private int tick = Integer.MIN_VALUE;
        private Vec3 position = Vec3.ZERO;
        private Vec3 direction;
        private boolean fromStep;
        private int logged = -1_000_000;
        private String mode = "-";
        private String draw = "-";
    }

    /** Client-side only, keyed weakly so a round that leaves the level leaves nothing behind. */
    private static final Map<BeamRidingMissileEntity, Step> STEPS = new WeakHashMap<>();

    /** Airframes drawn since the counter rolled over, per type. */
    private static final Map<String, Integer> DRAWS = new ConcurrentHashMap<>();

    /** Client level tick the draw counter is currently counting. */
    private static long frameTick = Long.MIN_VALUE;

    /** Airframes drawn in the last complete client tick. */
    private static int lastFrameDraws;

    /** Tick of the last log line, never {@code Long.MIN_VALUE} (that wraps and never prints). */
    private static long lastLog;

    /** Last "suppressed" report, so a round that stops being drawn says so once a second. */
    private static long lastSuppressedLog;

    private void firecontrolcompat$countDraw() {
        long tick;
        try {
            tick = Minecraft.getInstance().level == null
                    ? 0L : Minecraft.getInstance().level.getGameTime();
        } catch (Throwable ignored) {
            tick = 0L;
        }
        if (tick != frameTick) {
            if (frameTick != Long.MIN_VALUE) {
                lastFrameDraws = DRAWS.getOrDefault(this.type, 0);
                DRAWS.clear();
            }
            frameTick = tick;
        }
        DRAWS.merge(this.type, 1, Integer::sum);
    }

    /**
     * One line a second per type for a round that is <b>not</b> drawn by this renderer: without it,
     * "the pack's proxy is the picture" and "nothing is drawing this round" produce the same log.
     */
    private void firecontrolcompat$reportSuppressed(T entity) {
        try {
            long now = Minecraft.getInstance().level == null
                    ? 0L : Minecraft.getInstance().level.getGameTime();
            if (lastSuppressedLog != 0L && now - lastSuppressedLog < 20L) {
                return;
            }
            lastSuppressedLog = now;
            boolean proxy = MissileVisualSupport.isRemoteVisual(entity);
            FireControlCompat.LOGGER.info(
                    "ir-atgm render: type={} mode=suppressed reason=pack-draws-this-uuid proxy={} dist={}"
                            + " renderer=JavaItemMissileRenderer",
                    this.type, proxy,
                    String.format(Locale.ROOT, "%.1f", firecontrolcompat$distance(entity)));
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a frame
        }
    }

    private double firecontrolcompat$distance(T entity) {
        try {
            if (Minecraft.getInstance().gameRenderer != null
                    && Minecraft.getInstance().gameRenderer.getMainCamera() != null) {
                return Math.sqrt(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                        .distanceToSqr(entity.position()));
            }
        } catch (Throwable ignored) {
            // no camera: distance unknown
        }
        return -1.0D;
    }

    /**
     * One line a second per type: is the airframe drawn, with which route, at what distance, and how
     * was it laid on the flight path.
     *
     * <pre>ir-atgm render: type=&lt;path&gt; mode=&lt;item|primitive&gt; uv=&lt;atlas|box&gt; tex=&lt;path&gt;
     * light=full item-route=&lt;on|off&gt; dist=&lt;n&gt; draws=&lt;n&gt; src=&lt;…&gt;
     * renderer=JavaItemMissileRenderer model=&lt;baked|primitive|…&gt; proxy=&lt;bool&gt;</pre>
     */
    private void firecontrolcompat$report(T entity, Step step, boolean itemModel) {
        int tick = entity.tickCount;
        if (step.logged >= 0 && tick - step.logged < 20 && tick != 0) {
            return;
        }
        long now;
        try {
            now = Minecraft.getInstance().level == null ? 0L
                    : Minecraft.getInstance().level.getGameTime();
        } catch (Throwable ignored) {
            now = 0L;
        }
        if (lastLog != 0L && now - lastLog < 20L && step.logged >= 0) {
            return;
        }
        step.logged = tick;
        lastLog = now;
        float length = this.modelLength > 0.0F
                ? this.modelLength * this.scale
                : this.bodyLength * this.scale;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] ir-atgm render: type={} mode={} uv={} tex={} light=full"
                        + " shader-colour=white item-route={} scale={} length={} center={} size={}"
                        + " dist={} draws={} src={} renderer={} model={} proxy={}",
                this.type,
                itemModel ? "item" : "primitive",
                itemModel ? "atlas" : "box",
                this.texture,
                ITEM_MODEL_ROUTE ? "on" : "off",
                String.format(Locale.ROOT, "%.2f", this.scale),
                String.format(Locale.ROOT, "%.2f", length),
                this.firecontrolcompat$format(itemModel ? this.modelCentre : Vec3.ZERO),
                this.firecontrolcompat$format(itemModel && this.modelSize.lengthSqr() > 0.0D
                        ? this.modelSize : new Vec3(this.bodyLength, this.bodyRadius * 2.0, this.bodyLength)),
                String.format(Locale.ROOT, "%.1f", firecontrolcompat$distance(entity)),
                lastFrameDraws,
                step.mode,
                "JavaItemMissileRenderer",
                itemModel ? this.modelState : "primitive",
                MissileVisualSupport.isRemoteVisual(entity));
    }

    /**
     * The airframe is nearly three blocks long but the entity box is small, so frustum culling
     * against the box would drop the round while its nose or tail is still on screen. A missile is
     * one cheap model per client; it is always drawn.
     */
    @Override
    public boolean shouldRender(T entity, Frustum frustum, double camX, double camY, double camZ) {
        return true;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return this.texture;
    }
}
