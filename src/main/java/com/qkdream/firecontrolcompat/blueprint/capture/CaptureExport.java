package com.qkdream.firecontrolcompat.blueprint.capture;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.blueprint.BlueprintBake;
import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Records one frame of the render pipeline and writes it as a single mesh.
 *
 * <p>The per-block exporter walks the blueprint and asks each block and each block entity renderer
 * for its geometry. That works for anything described by a model, and fails for everything that is
 * drawn by a renderer that expects a live level - tracks, tyres, contraptions, moving structures -
 * which is what the report "the parts are missing or in the wrong place" is about.</p>
 *
 * <p>This mode does what MineToMesh's mcgltf does: it does not ask anybody for geometry, it wraps the
 * {@code VertexConsumer} the level renderer writes into and keeps the vertices. Whatever the game
 * draws during the recording window is in the file, in the position it was drawn at, with the UVs it
 * was drawn with - no per-block guessing, no atlas remapping.</p>
 *
 * <p>Each recorded {@link RenderType} samples its own texture, and only the block types sample the
 * stitched block atlas: entities, fonts, beams and glints bind a plain PNG that is not part of it.
 * Pointing every group at {@code blocks_atlas.png} therefore samples arbitrary texels and is what
 * made those parts look like noise in Blender. The texture of a render type is read back by
 * reflection ({@code CompositeRenderType.state.textureState.texture}) and, for anything that is not
 * the block atlas, the PNG itself is copied out of the resource packs next to the mesh.</p>
 *
 * <p>Recording never replaces the underlying consumer: every call is forwarded first and recorded
 * second, so the frame the player sees is unchanged and a failure here cannot break rendering.</p>
 */
public final class CaptureExport {

    /** Vertices past this are not recorded any more, so a big contraption cannot exhaust memory. */
    private static final int MAX_VERTICES = 2_000_000;

    /** Largest number of frames a recording may stay open for, so a missed close cannot leak. */
    private static final int MAX_TICKS = 40;

    /** The .mtl path of the stitched block atlas, relative to the export folder. */
    private static final String ATLAS_MTL_PATH = "textures/blocks_atlas.png";

    /** A {@code namespace:path.png} as it appears in a render type or in a ResourceLocation. */
    private static final Pattern LOCATION_PATTERN =
            Pattern.compile("([a-z0-9_.-]+:[a-z0-9_./-]+\\.png)");

    /** Deepest object graph a modded render type is searched through for its texture. */
    private static final int WALK_DEPTH = 4;

    /** How many objects one render type search may visit, so a strange field cannot hang a frame. */
    private static final int WALK_BUDGET = 256;

    private static boolean recording;
    private static int ticksOpen;
    private static String name = "capture";
    private static AABB region = null;
    private static double radiusUsed = 24.0D;
    private static int limitHits;

    /** Whether any render type was wrapped since arming: the render pass reached this window. */
    private static boolean sawDraw;

    /** Whether the wrap came from the {@code getBuffer} hook (the complete door). */
    private static boolean viaBufferSource;

    /** Whether it came from the dispatcher fallback (block entities and entities). */
    private static boolean viaDispatcher;

    /** First render type recorded, so "which door was hit" names something concrete. */
    private static String firstHitType = "";

    /** Whether {@code capture: hook hit} has been printed for this window. */
    private static boolean hitLogged;

    /** Whether {@code capture: hook not hit} has been printed for this window. */
    private static boolean missLogged;

    /**
     * Ticks after which a window with nothing recorded says so, while it is still open.
     *
     * <p>One second: the frame that is being captured is the next one drawn, so by then a closed door
     * is proved rather than suspected. The window keeps running to {@link #MAX_TICKS} either way, so
     * a slow first frame still gets its chance.</p>
     */
    private static final int REPORT_AFTER_TICKS = 20;

    /** Whether installing a recorder ever threw, so the "nothing captured" line can name why. */
    private static boolean wrapFailed;

    /** Quads per (render type, texture) pair, in the order the pairs were first seen. */
    private static final Map<String, Group> GROUPS = new LinkedHashMap<>();

    /** Every render type name seen while recording, including ones with no vertices kept. */
    private static final LinkedHashSet<String> SEEN_TYPES = new LinkedHashSet<>();

    /** Render type name -> how many groups already took that material name, so names stay unique. */
    private static final Map<String, Integer> MATERIAL_COUNTS = new HashMap<>();

    /** Texture location -> the png written for it, or "" when it could not be read. */
    private static final Map<String, String> TEX_FILES = new LinkedHashMap<>();

    /** Reflection lookup cost is paid once per class/field pair, not once per draw call. */
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static final Map<Class<?>, Field[]> FIELDS_OF = new HashMap<>();

    private CaptureExport() {
    }

    /** Whether the buffer source should wrap the consumers it hands out. */
    public static boolean isRecording() {
        return recording;
    }

    /**
     * Arms a recording of the next frames, centred on the player.
     *
     * @param requestedName file name without extension
     * @param radius        half size of the region in blocks
     * @return whether the window is now open
     */
    public static boolean arm(String requestedName, double radius) {
        return arm(requestedName, radius, "command");
    }

    /**
     * Arms a recording of the next frames, saying who asked for it.
     *
     * <p>The per-block exporter arms one of these itself once its own files are written, and it
     * needs to know whether the window opened: a capture that could not start is one warning, and
     * the export it follows has already succeeded either way. That is why this answers with a
     * boolean instead of only logging.</p>
     *
     * @param requestedName file name without extension
     * @param radius        half size of the region in blocks
     * @param reason        {@code command} or {@code auto-export}, for the log line
     * @return whether the window is now open
     */
    public static boolean arm(String requestedName, double radius, String reason) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) {
                BlueprintExporter.LOGGER.warn(
                        "capture: arm failed reason=not-in-game name={}", requestedName);
                return false;
            }
            name = requestedName == null || requestedName.isBlank() ? "capture" : requestedName;
            Vec3 centre = minecraft.player.position();
            double r = Math.max(2.0, Math.min(256.0, radius));
            radiusUsed = r;
            region = new AABB(centre.x - r, centre.y - r, centre.z - r,
                              centre.x + r, centre.y + r, centre.z + r);
            GROUPS.clear();
            SEEN_TYPES.clear();
            MATERIAL_COUNTS.clear();
            TEX_FILES.clear();
            limitHits = 0;
            ticksOpen = 0;
            sawDraw = false;
            viaBufferSource = false;
            viaDispatcher = false;
            firstHitType = "";
            hitLogged = false;
            missLogged = false;
            wrapFailed = false;
            recording = true;
            // Owner tagging for this window: the entity scope is opened by the dispatcher hook, so
            // every quad recorded below is stamped with the entity it was drawn for.
            CaptureScope.reset();
            rememberName(name);
            // The one line that says "a capture is armed" without having to read the region:
            // name, size and where the player stood, so a later file can be tied to this moment.
            BlueprintExporter.LOGGER.info("capture: armed name={} radius={} player={} reason={}",
                    name,
                    String.format(Locale.ROOT, "%.1f", r),
                    String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)",
                            centre.x, centre.y, centre.z),
                    reason == null || reason.isBlank() ? "unknown" : reason);
            BlueprintExporter.LOGGER.info("capture: open radius={} region={} groups=0 limit={}",
                    String.format(Locale.ROOT, "%.1f", r),
                    String.format(Locale.ROOT, "[%.1f, %.1f, %.1f .. %.1f, %.1f, %.1f]",
                            region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ),
                    MAX_VERTICES);
            // The hard evidence this mode was missing: whether the hook is inside the class, asked of
            // the class itself. A capture that then records nothing can no longer be read two ways.
            BlueprintExporter.LOGGER.info(
                    "capture-hook: present={} target=MultiBufferSource$BufferSource#getBuffer {}",
                    CaptureHooks.bufferSourceHooked(), CaptureHooks.describe());
            BlueprintExporter.LOGGER.info(
                    "capture-hook: target-methods getBuffer-class={} entity-dispatcher={}"
                            + " block-entity-dispatcher={} note={}",
                    CaptureHooks.methodCount(CaptureHooks.BUFFER_SOURCE),
                    CaptureHooks.methodCount(CaptureHooks.ENTITY_DISPATCHER),
                    CaptureHooks.methodCount(CaptureHooks.BLOCK_ENTITY_DISPATCHER),
                    CaptureHooks.bufferSourceHooked()
                            ? "the per-render-type door is hooked"
                            : "the per-render-type door is NOT hooked - the dispatcher door carries"
                                    + " this window (block entities + entities only)");
            return true;
        } catch (Throwable t) {
            // A window that half-opened is closed again rather than left to record a later frame.
            recording = false;
            region = null;
            BlueprintExporter.LOGGER.warn("capture: arm failed reason={}:{} name={}",
                    t.getClass().getSimpleName(), t.getMessage(), requestedName);
            return false;
        }
    }

    /**
     * Called once per client tick: closes the recording when the window is over.
     *
     * <p>The window is meant to be one frame. A tick that saw nothing handed to a wrapping
     * consumer is not the end of it: the render pass may not have run yet (a paused or hidden
     * window, a frame that lands in the next tick), so it keeps trying until
     * {@link #MAX_TICKS}. If even that window ends without a single draw reaching it, the reason
     * is written down - {@code no-render} when nothing was drawn, {@code exception} when the
     * recorder could not be installed - instead of leaving a missing file to be guessed at.</p>
     */
    public static void tick() {
        if (!recording) {
            return;
        }
        ticksOpen++;
        if (!sawDraw && ticksOpen >= REPORT_AFTER_TICKS && !missLogged) {
            missLogged = true;
            // Said while the window is still open, so the next log line is not a surprise: either
            // the hook is not in the class (CaptureHooks says which door is), or the frame drew
            // nothing that goes through a buffer source at all.
            BlueprintExporter.LOGGER.warn(
                    "capture: hook not hit reason={} ticks={} {} first-hit-type=none",
                    wrapFailed ? "recorder-exception"
                            : (CaptureHooks.bufferSourceHooked() ? "no-geometry-through-getBuffer"
                                    : "getBuffer-hook-absent"),
                    ticksOpen, CaptureHooks.describe());
        }
        if (!sawDraw && ticksOpen < MAX_TICKS) {
            return;
        }
        if (!sawDraw) {
            BlueprintExporter.LOGGER.warn("capture: no frame captured ticks={} cause={} name={}",
                    ticksOpen, wrapFailed ? "exception" : "no-render", name);
        }
        close(sawDraw ? "frame-end" : "timeout");
    }

    /** Wraps a consumer so it records as it forwards. The delegate is always written to. */
    public static VertexConsumer wrap(VertexConsumer delegate, RenderType type) {
        return wrap(delegate, type, "buffer-source");
    }

    /**
     * As {@link #wrap(VertexConsumer, RenderType)}, naming the door the consumer arrived through.
     *
     * <p>Two doors exist (see {@link RecordingBufferSource}): the per-render-type hook in
     * {@code MultiBufferSource$BufferSource#getBuffer}, and the dispatcher fallback. The first one to
     * hand over a consumer is what the log records, and a consumer that is already a recorder is
     * passed straight back - the dispatcher door asks the real source first, so with both doors open
     * the geometry must not be recorded twice.</p>
     */
    public static VertexConsumer wrap(VertexConsumer delegate, RenderType type, String via) {
        if (!recording || delegate == null || type == null) {
            return delegate;
        }
        if (delegate instanceof Recorder) {
            // Already being recorded - the other door got here first for this consumer.
            return delegate;
        }
        try {
            String typeName = typeName(type);
            SEEN_TYPES.add(typeName);
            // Two entity types can share the name "entity_cutout" and still bind different skins, so
            // the group key carries the texture as well: merging them is exactly the "wrong colours"
            // bug this mode is fixing.
            ResourceLocation location = locationOf(type);
            String key = typeName + '|' + (location == null ? "-" : location.toString());
            Group group = GROUPS.get(key);
            if (group == null) {
                group = new Group(typeName, location, type, uniqueMaterial(typeName));
                GROUPS.put(key, group);
            }
            // Something in this frame came through the wrapped source, so the window has
            // something to close on. Only set once a recorder is actually handed out.
            sawDraw = true;
            if ("dispatcher".equals(via)) {
                viaDispatcher = true;
            } else {
                viaBufferSource = true;
            }
            if (!hitLogged) {
                hitLogged = true;
                firstHitType = typeName;
                BlueprintExporter.LOGGER.info(
                        "capture: hook hit type={} via={} {}",
                        typeName, via, CaptureHooks.describe());
            }
            return new Recorder(delegate, group);
        } catch (Throwable t) {
            // A recording failure must never stop the frame from being drawn.
            wrapFailed = true;
            return delegate;
        }
    }

    /**
     * Which door the recorded geometry came through, e.g. {@code getBuffer} or {@code dispatcher}.
     *
     * <p>{@code both} is normal when the dispatcher door asks the hooked source first; {@code none}
     * only appears on a window that recorded nothing.</p>
     */
    private static String via() {
        if (viaBufferSource && viaDispatcher) {
            return "both";
        }
        if (viaBufferSource) {
            return "getBuffer";
        }
        return viaDispatcher ? "dispatcher" : "none";
    }

    /** A readable name for a render type: the shard name, e.g. {@code entity_cutout}. */
    private static String typeName(RenderType type) {
        try {
            String shard = shardName(type);
            if (shard != null && !shard.isEmpty()) {
                return shard.replaceAll("[^A-Za-z0-9_]", "_");
            }
            String text = String.valueOf(type);
            int bracket = text.indexOf('[');
            String tail = bracket >= 0 ? text.substring(0, bracket) : text;
            int dot = tail.lastIndexOf('.');
            if (dot >= 0) {
                tail = tail.substring(dot + 1);
            }
            return tail.replaceAll("[^A-Za-z0-9_]", "_");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** Material names must be unique, or two groups share one texture in Blender. */
    private static String uniqueMaterial(String typeName) {
        String base = "M_cap_" + typeName;
        int seen = MATERIAL_COUNTS.merge(base, 1, Integer::sum);
        return seen == 1 ? base : base + "_" + seen;
    }

    /** A group: every quad recorded for one render type with one texture. */
    private static final class Group {
        final String type;
        final ResourceLocation location;
        final RenderType renderType;
        final String material;
        final List<float[][]> quads = new ArrayList<>();   // [4][8] pos3 + uv2 + n3
        /**
         * The owner of each recorded quad, in the same order as {@link #quads}.
         *
         * <p>A whole group can hold the geometry of several entities - one render type is reused
         * by every renderer that happens to draw with it - so the owner is kept per quad rather
         * than per group. Parallel to {@code quads} and never written to the OBJ.</p>
         */
        final List<String> owners = new ArrayList<>();
        int dropped;

        Group(String type, ResourceLocation location, RenderType renderType, String material) {
            this.type = type;
            this.location = location;
            this.renderType = renderType;
            this.material = material;
        }
    }

    /**
     * The recording consumer.
     *
     * <p>Every method forwards to the real consumer first, then records. Positions arrive already
     * transformed by the caller's pose in 1.21.1, so what is recorded is world space and can be
     * clipped against the region without knowing which renderer produced it.</p>
     */
    private static final class Recorder implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Group group;
        private final float[][] quad = new float[4][8];
        private int corner;
        private float x, y, z, u, v, nx, ny, nz;

        Recorder(VertexConsumer delegate, Group group) {
            this.delegate = delegate;
            this.group = group;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            // 1.21.1 completes a vertex on the NEXT addVertex; there is no endVertex. So a quad is
            // finished when its fifth vertex starts, and the previous four are committed here.
            if (corner == 4) {
                corner = 0;
                record();
            }
            this.delegate.addVertex(x, y, z);
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0f;
            this.v = 0f;
            this.nx = 0f;
            this.ny = 0f;
            this.nz = 0f;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }


        private void record() {
            if (group.quads.size() * 4L >= MAX_VERTICES) {
                limitHits++;
                return;
            }
            if (region != null && !inside(quad)) {
                group.dropped++;
                return;
            }
            float[][] copy = new float[4][8];
            for (int i = 0; i < 4; i++) {
                System.arraycopy(quad[i], 0, copy[i], 0, 8);
            }
            group.quads.add(copy);
            // Who this quad belongs to, read now: by the time the file is written the entity that
            // drew it is gone. Null (no scope open) is kept as an empty string, and the counters
            // in CaptureScope are what the summary line reports.
            String owner = CaptureScope.current();
            group.owners.add(owner == null ? "" : owner);
            CaptureScope.noteQuad();
            CaptureScope.noteRenderType(group.type);
        }

        private static boolean inside(float[][] quad) {
            for (float[] vertex : quad) {
                if (vertex[0] < region.minX - 0.5 || vertex[0] > region.maxX + 0.5
                        || vertex[1] < region.minY - 0.5 || vertex[1] > region.maxY + 0.5
                        || vertex[2] < region.minZ - 0.5 || vertex[2] > region.maxZ + 0.5) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Closes the recording and writes the files. */
    public static void close(String why) {
        if (!recording) {
            return;
        }
        recording = false;
        long started = System.currentTimeMillis();
        try {
            int verts = 0;
            int faces = 0;
            int materials = 0;
            int dropped = 0;
            for (Group group : GROUPS.values()) {
                if (group.quads.isEmpty()) {
                    continue;
                }
                materials++;
                verts += group.quads.size() * 4;
                faces += group.quads.size();
                dropped += group.dropped;
            }
            if (faces == 0) {
                BlueprintExporter.LOGGER.info(
                        "capture: close reason={} verts=0 faces=0 materials=0 dropped={} atlas=false"
                                + " via={}",
                        why, dropped, via());
                // No second, contradictory story: nothing was recorded, and the per-block export
                // this window followed is finished on disk regardless. The line above says which
                // of the two causes it was (tick() reports no-render or exception), and this one
                // names the door: hook= says whether the getBuffer hook is inside the class at all.
                BlueprintExporter.LOGGER.warn(
                        "capture: nothing recorded - the per-block export is unaffected"
                                + " (no geometry reached the recording consumer; groups seen={}"
                                + " hook={} via={} first-hit-type={})",
                        SEEN_TYPES, CaptureHooks.bufferSourceHooked() ? "getBuffer" : "absent", via(),
                        firstHitType.isEmpty() ? "none" : firstHitType);
                return;
            }
            boolean atlas = write();
            int textures = 0;
            for (String path : TEX_FILES.values()) {
                if (!path.isEmpty()) {
                    textures++;
                }
            }
            BlueprintExporter.LOGGER.info(
                    "capture: close reason={} verts={} faces={} materials={} dropped={} limit_hits={}"
                            + " radius={} atlas={} textures={} groups={} ms={} via={} first-hit-type={}",
                    why, verts, faces, materials, dropped, limitHits,
                    String.format(Locale.ROOT, "%.1f", radiusUsed), atlas, textures, SEEN_TYPES,
                    System.currentTimeMillis() - started, via(),
                    firstHitType.isEmpty() ? "none" : firstHitType);
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.error("capture: write failed", t);
        } finally {
            // The owner totals for this window, on every path out including "nothing recorded".
            // Written after the mesh, so the numbers describe the file that was just produced;
            // the tagging itself changes nothing in it.
            CaptureScope.summarise();
            GROUPS.clear();
            SEEN_TYPES.clear();
            MATERIAL_COUNTS.clear();
            TEX_FILES.clear();
            region = null;
            ticksOpen = 0;
            sawDraw = false;
            wrapFailed = false;
        }
    }

    /**
     * Writes {@code <name>_capture.obj}, its .mtl, the block atlas and every other texture the
     * recorded render types bind.
     *
     * @return whether the block atlas was written
     */
    private static boolean write() throws Exception {
        Path outDir = BlueprintBake.outputDir();
        Files.createDirectories(outDir);
        String base = name + "_capture";
        Path obj = outDir.resolve(base + ".obj");
        Path mtl = outDir.resolve(base + ".mtl");

        List<Group> drawn = new ArrayList<>();
        StringBuilder body = new StringBuilder(1 << 20);
        body.append("# frame capture by firecontrolcompat\n");
        body.append("mtllib ").append(base).append(".mtl\n");
        int baseIndex = 1;
        for (Group group : GROUPS.values()) {
            if (group.quads.isEmpty()) {
                continue;
            }
            drawn.add(group);
            body.append("o ").append(group.type).append('\n');
            body.append("usemtl ").append(group.material).append('\n');
            for (float[][] quad : group.quads) {
                for (float[] vertex : quad) {
                    body.append("v ").append(fmt(vertex[0])).append(' ').append(fmt(vertex[1]))
                        .append(' ').append(fmt(vertex[2])).append('\n');
                }
                for (float[] vertex : quad) {
                    body.append("vt ").append(fmt(vertex[3])).append(' ')
                        .append(fmt(1.0f - vertex[4])).append('\n');
                }
                for (float[] vertex : quad) {
                    body.append("vn ").append(fmt(vertex[5])).append(' ').append(fmt(vertex[6]))
                        .append(' ').append(fmt(vertex[7])).append('\n');
                }
            }
            for (int i = 0; i < group.quads.size(); i++) {
                int v0 = baseIndex + i * 4;
                body.append("f ").append(v0).append('/').append(v0).append('/').append(v0)
                    .append(' ').append(v0 + 1).append('/').append(v0 + 1).append('/').append(v0 + 1)
                    .append(' ').append(v0 + 2).append('/').append(v0 + 2).append('/').append(v0 + 2)
                    .append(' ').append(v0 + 3).append('/').append(v0 + 3).append('/').append(v0 + 3)
                    .append('\n');
            }
            baseIndex += group.quads.size() * 4;
        }
        Files.writeString(obj, body);

        // The atlas is read off the GPU first: every other texture is a plain PNG inside a pack, but
        // the stitched atlases only exist as the texture the game bound for them.
        boolean atlas = dumpBoundTexture(TextureAtlas.LOCATION_BLOCKS,
                outDir.resolve(ATLAS_MTL_PATH), "blocks_atlas");

        try (BufferedWriter writer = Files.newBufferedWriter(mtl)) {
            writer.write("# frame capture materials\n");
            for (Group group : drawn) {
                Material material = materialFor(outDir, group);
                writer.write("\nnewmtl " + group.material + "\n");
                writer.write("# render_type: " + group.type + "\n");
                writer.write("# tex_location: "
                        + (group.location == null ? "unresolved" : group.location.toString()) + "\n");
                // The recorded UVs are used exactly as the game handed them over - the atlas space
                // for block types, the texture's own space for everything else. Nothing is remapped.
                writer.write("# uv_space: atlas\n");
                writer.write("# uv_in_0_1: " + material.uvLocal + "\n");
                for (String comment : material.comments) {
                    writer.write("# " + comment + "\n");
                }
                writer.write("Kd 1.000 1.000 1.000\nKa 0.000 0.000 0.000\nKs 0.000 0.000 0.000\n");
                writer.write("map_Kd " + material.mapKd + "\n");
                if ("translucent".equals(material.layer)) {
                    writer.write("d 0.500\nTr 0.500\nillum 2\n");
                } else if ("cutout".equals(material.layer)) {
                    // map_d is what makes Blender keep the cut-out pixels instead of painting the
                    // transparent part of the sheet black.
                    writer.write("map_d " + material.mapKd + "\n");
                    writer.write("illum 2\n");
                } else {
                    writer.write("d 1.0\nillum 1\n");
                }
                BlueprintExporter.LOGGER.info("capture: material={} layer={} tex={} uv-space={}",
                        group.type, material.layer, material.tex, material.uvSpace);
            }
        }
        BlueprintExporter.LOGGER.info("capture: wrote {} and {} ({} bytes) atlas={}", obj.getFileName(),
                mtl.getFileName(), Files.size(obj), atlas);
        return atlas;
    }

    /** One .mtl entry: which image a render type samples and how its UVs sit on it. */
    private static final class Material {
        String layer = "solid";
        /** {@code atlas}, {@code ns:path} or {@code unresolved}, for the log line. */
        String tex = "unresolved";
        /** {@code atlas}, {@code local} or {@code unknown}, for the log line. */
        String uvSpace = "unknown";
        String uvLocal = "no";
        String mapKd = ATLAS_MTL_PATH;
        /** Extra .mtl comment lines; {@code tex_unresolved} is what a fallback entry carries. */
        final List<String> comments = new ArrayList<>();
    }

    /**
     * Decides the texture of one group and makes sure the image is on disk.
     *
     * <p>A render type whose texture cannot be found, or whose PNG cannot be read, points at the
     * block atlas and is marked {@code # tex_unresolved}, and the log names it: a wrong-looking part
     * then has one line to search for instead of a silent colour mess.</p>
     */
    private static Material materialFor(Path outDir, Group group) {
        Material material = new Material();
        material.layer = layerOf(group);
        material.uvSpace = uvSpaceOf(group);
        material.uvLocal = "unknown".equals(material.uvSpace) ? "no" : "yes";
        if (group.location == null) {
            material.comments.add("tex_unresolved");
            BlueprintExporter.LOGGER.warn(
                    "capture: tex_unresolved type={} reason=no-texture-field (fell back to the atlas)",
                    group.type);
            return material;
        }
        if (group.location.equals(TextureAtlas.LOCATION_BLOCKS)) {
            material.tex = "atlas";
            material.uvSpace = "atlas";
            return material;
        }
        String png = saveTexture(outDir, group.location);
        if (png == null) {
            material.comments.add("tex_unresolved");
            BlueprintExporter.LOGGER.warn(
                    "capture: tex_unresolved type={} location={} reason=image-not-readable"
                            + " (fell back to the atlas)",
                    group.type, group.location);
            return material;
        }
        material.tex = group.location.toString();
        material.mapKd = png;
        // A great many entity renderers are built from an "entity_translucent" type even when the
        // skin is fully opaque. Exporting those with d 0.5 turns a solid tank hull into glass, so the
        // image is looked at once and the material stays opaque when it has nothing to blend.
        if ("translucent".equals(material.layer) && !hasTransparency(outDir.resolve(png))) {
            material.layer = "solid";
            material.comments.add("opaque_texture: alpha is 255 everywhere, imported solid");
        }
        return material;
    }

    /**
     * Whether a PNG carries any pixel that is not fully opaque.
     *
     * <p>The image is sampled rather than scanned: a single non-opaque pixel is all that is asked
     * for, and a 4k skin holds millions that are not. A file that cannot be decoded answers
     * {@code true}, so an unusual PNG keeps the alpha settings its render type asked for.</p>
     */
    private static boolean hasTransparency(Path png) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png.toFile());
            if (image == null) {
                return true;
            }
            int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 256);
            for (int y = 0; y < image.getHeight(); y += step) {
                for (int x = 0; x < image.getWidth(); x += step) {
                    if (((image.getRGB(x, y) >>> 24) & 0xFF) < 250) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** {@code solid}, {@code cutout} or {@code translucent} for the .mtl alpha settings. */
    private static String layerOf(Group group) {
        String lower = group.type.toLowerCase(Locale.ROOT);
        if (lower.contains("cutout") || lower.contains("text") || lower.contains("armor")
                || lower.contains("water_mask") || lower.contains("crumbling")) {
            return "cutout";
        }
        if (lower.contains("translucent") || lower.contains("glint") || lower.contains("lightning")
                || lower.contains("additive") || lower.contains("energy_swirl")
                || lower.contains("dragon_explosion") || lower.contains("eyes")
                || lower.contains("beacon")) {
            return "translucent";
        }
        Object state = fget(group.renderType, "state");
        String transparency = shardName(fget(state, "transparencyState"));
        if (transparency != null && (transparency.contains("translucent")
                || transparency.contains("additive") || transparency.contains("lightning")
                || transparency.contains("glint") || transparency.contains("crumbling"))) {
            return "translucent";
        }
        return "solid";
    }

    /**
     * Whether the group's UVs live in the block atlas space, in the bound texture's own space, or
     * somewhere this code cannot name.
     *
     * <p>A block type is always the atlas: its UVs address the stitched sheet. Any other render type
     * addresses its own texture, so UVs inside 0..1 are local; anything outside means the renderer
     * scaled them itself (scrolling, tiling) and the log says {@code unknown} so the part can be
     * found by search rather than by eye.</p>
     */
    private static String uvSpaceOf(Group group) {
        if (group.location != null && group.location.equals(TextureAtlas.LOCATION_BLOCKS)) {
            return "atlas";
        }
        float minU = Float.MAX_VALUE;
        float minV = Float.MAX_VALUE;
        float maxU = -Float.MAX_VALUE;
        float maxV = -Float.MAX_VALUE;
        for (float[][] quad : group.quads) {
            for (float[] vertex : quad) {
                minU = Math.min(minU, vertex[3]);
                maxU = Math.max(maxU, vertex[3]);
                minV = Math.min(minV, vertex[4]);
                maxV = Math.max(maxV, vertex[4]);
            }
        }
        if (minU < -0.001f || maxU > 1.001f || minV < -0.001f || maxV > 1.001f) {
            return "unknown";
        }
        return "local";
    }

    // ------------------------------------------------------------ texture lookup

    /**
     * The texture a render type samples, read back without touching the game's own accessors.
     *
     * <p>In 1.21.1 a {@code CompositeRenderType} keeps its states in {@code state}, and the texture is
     * a {@code TextureStateShard} there whose private {@code texture} is the {@code ResourceLocation}.
     * That is the fast path; modded render types that build their own subclass are searched through
     * field by field, and only if all of that fails is the {@code toString()} text parsed.</p>
     *
     * @return the bound texture, or {@code null} when nothing could be found
     */
    private static ResourceLocation locationOf(RenderType type) {
        if (type == null) {
            return null;
        }
        try {
            Object state = fget(type, "state");
            Object textureState = fget(state, "textureState");
            if (textureState != null) {
                // A vanilla render type always has this shard, and it is the answer even when it is
                // empty: "no texture bound" must not send the search on to the other 13 states, each
                // of which would be asked for a method it does not have.
                return asLocation(fget(textureState, "texture"));
            }
            Object states = fget(state, "states");
            if (states instanceof Iterable<?> list) {
                for (Object shard : list) {
                    ResourceLocation location = fromTextureShard(shard);
                    if (location != null) {
                        return location;
                    }
                }
            }
            ResourceLocation walked = walkForLocation(type);
            if (walked != null) {
                return walked;
            }
            return fromText(String.valueOf(type));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads {@code TextureStateShard#texture} (or {@code cutoutTexture()}) out of a state shard. */
    private static ResourceLocation fromTextureShard(Object shard) {
        if (shard == null) {
            return null;
        }
        ResourceLocation direct = asLocation(fget(shard, "texture"));
        if (direct != null) {
            return direct;
        }
        try {
            Method method = shard.getClass().getDeclaredMethod("cutoutTexture");
            method.setAccessible(true);
            return asLocation(method.invoke(shard));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Unwraps a {@code ResourceLocation}, or an {@code Optional} holding one. */
    private static ResourceLocation asLocation(Object value) {
        if (value instanceof ResourceLocation location) {
            return (ResourceLocation) location;
        }
        if (value instanceof Optional<?> optional && optional.isPresent()) {
            return asLocation(optional.get());
        }
        return null;
    }

    /**
     * Searches a render type's object graph for a texture, for the modded types that keep one in a
     * field of their own shape.
     */
    private static ResourceLocation walkForLocation(Object root) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] { root, 0 });
        seen.add(root);
        int budget = WALK_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            Object[] item = queue.poll();
            Object node = item[0];
            int depth = (Integer) item[1];
            if (node == null || depth >= WALK_DEPTH) {
                continue;
            }
            for (Field field : fieldsOf(node.getClass())) {
                Object value;
                try {
                    value = field.get(node);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                ResourceLocation location = asLocation(value);
                if (location != null) {
                    if (depth == 0 || fieldName.contains("texture") || fieldName.contains("location")
                            || fieldName.contains("atlas") || fieldName.contains("skin")) {
                        return location;
                    }
                    continue;
                }
                if (isTraversable(value) && seen.add(value)) {
                    queue.add(new Object[] { value, depth + 1 });
                }
            }
        }
        return null;
    }

    private static boolean isTraversable(Object value) {
        if (value instanceof Class<?> || value instanceof String || value instanceof Number
                || value instanceof Boolean || value.getClass().isArray()) {
            return false;
        }
        String className = value.getClass().getName();
        return className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")
                || className.startsWith("com.qkdream.");
    }

    /** Last resort: the {@code namespace:path.png} a render type prints in its own description. */
    private static ResourceLocation fromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = LOCATION_PATTERN.matcher(text);
        while (matcher.find()) {
            ResourceLocation location = ResourceLocation.tryParse(matcher.group(1));
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    private static Field field(Class<?> owner, String fieldName) {
        String key = owner.getName() + '#' + fieldName;
        if (FIELDS.containsKey(key)) {
            return FIELDS.get(key);
        }
        Field found = null;
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                found = field;
                break;
            } catch (NoSuchFieldException ignored) {
                // keep looking in the superclass
            } catch (Throwable ignored) {
                break;
            }
        }
        FIELDS.put(key, found);
        return found;
    }

    private static Object fget(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Field field = field(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The {@code name} a state shard (and a render type) carries, e.g. {@code entity_cutout}. */
    private static String shardName(Object shard) {
        Object value = fget(shard, "name");
        return value instanceof String text ? text : null;
    }

    private static Field[] fieldsOf(Class<?> owner) {
        Field[] cached = FIELDS_OF.get(owner);
        if (cached != null) {
            return cached;
        }
        List<Field> collected = new ArrayList<>();
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collected.add(field);
                } catch (Throwable ignored) {
                    // a field that cannot be opened is simply not searched
                }
            }
        }
        Field[] fields = collected.toArray(new Field[0]);
        FIELDS_OF.put(owner, fields);
        return fields;
    }

    // ------------------------------------------------------------ texture files

    /**
     * Copies a texture out of the resource packs, byte for byte.
     *
     * <p>Nothing is re-encoded: a pack's PNG may be indexed, 16 bit or have an odd colour profile,
     * and an ImageIO round trip would change both the bytes and the look. A stitched atlas has no
     * PNG in any pack, so those fall back to reading the bound GL texture.</p>
     *
     * <p>A file that is already in the export folder is reused rather than rewritten. The
     * per-block exporter writes its sprite copies into the same {@code textures/} tree and
     * re-encodes them on the way out (a cutout sheet is binarised there), so a capture that ran
     * after it and copied the pack's raw bytes over that path would change a PNG the neighbouring
     * .mtl already points at. Reuse keeps the per-block product byte for byte the same while the
     * capture still gets a real image for its own material.</p>
     *
     * @return the path written, relative to the export folder, or {@code null} when it failed
     */
    private static String saveTexture(Path outDir, ResourceLocation location) {
        String key = location.toString();
        String cached = TEX_FILES.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String relative = textureRelativePath(location);
        Path target = outDir.resolve(relative);
        boolean ok = false;
        boolean reused = false;
        try {
            Files.createDirectories(target.getParent());
            if (Files.isRegularFile(target) && Files.size(target) > 0L) {
                ok = true;
                reused = true;
            } else {
                Minecraft minecraft = Minecraft.getInstance();
                Optional<Resource> resource = minecraft == null
                        ? Optional.empty()
                        : minecraft.getResourceManager().getResource(location);
                if (resource.isPresent()) {
                    try (InputStream stream = resource.get().open()) {
                        Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    ok = true;
                } else if (location.getPath().startsWith("textures/atlas/")) {
                    // An atlas is stitched at load time; the GPU copy is the only image there is.
                    ok = dumpBoundTexture(location, target, key);
                } else {
                    BlueprintExporter.LOGGER.warn("capture: tex_missing location={} (no pack has it)",
                            key);
                }
            }
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("capture: tex_missing location={} reason={}:{}", key,
                    t.getClass().getSimpleName(), t.getMessage());
            ok = false;
        }
        TEX_FILES.put(key, ok ? relative : "");
        if (!ok) {
            try {
                Files.deleteIfExists(target);
            } catch (Throwable ignored) {
                // a leftover half-copied file is not worth a second failure
            }
            return null;
        }
        try {
            BlueprintExporter.LOGGER.info("capture: png={} bytes={} from={} reused={}", relative,
                    Files.size(target), key, reused);
        } catch (Throwable ignored) {
            // the size is a nicety in the log, not a reason to fail the export
        }
        return relative;
    }

    /**
     * Where a texture lives under the export folder: {@code textures/<namespace>/<path>}.
     *
     * <p>The {@code textures/} prefix inside the location is dropped so the file does not end up in
     * {@code textures/minecraft/textures/...}.</p>
     */
    private static String textureRelativePath(ResourceLocation location) {
        String path = location.getPath();
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        return "textures/" + location.getNamespace() + "/" + path;
    }

    /**
     * Reads a bound texture off the GPU and writes it as a PNG.
     *
     * <p>The input to this is a stitched atlas (the block atlas, the particle atlas), which exists
     * only as a GL texture: there is no PNG of it in any pack. {@code TextureAtlas.getWidth/getHeight}
     * are package private in 1.21.1, so the size is asked of GL instead - the same place the pixels
     * come from - and the read is done on the render thread, which is where this code runs from the
     * client tick.</p>
     *
     * @return whether the image was written
     */
    private static boolean dumpBoundTexture(ResourceLocation location, Path target, String label) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                BlueprintExporter.LOGGER.warn("capture: atlas skip label={} reason=no-client", label);
                return false;
            }
            var texture = minecraft.getTextureManager().getTexture(location);
            if (texture == null || texture.getId() <= 0) {
                BlueprintExporter.LOGGER.warn("capture: atlas skip label={} reason=no-texture-id", label);
                return false;
            }
            // GL is reached reflectively: the lwjgl jar on this build's classpath has no
            // org.lwjgl.opengl package, while the game runtime certainly has one.
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            int texture2d = gl11.getField("GL_TEXTURE_2D").getInt(null);
            int textureWidth = gl11.getField("GL_TEXTURE_WIDTH").getInt(null);
            int textureHeight = gl11.getField("GL_TEXTURE_HEIGHT").getInt(null);
            int rgba = gl11.getField("GL_RGBA").getInt(null);
            int unsignedByte = gl11.getField("GL_UNSIGNED_BYTE").getInt(null);
            int packAlignment = gl11.getField("GL_PACK_ALIGNMENT").getInt(null);
            gl11.getMethod("glBindTexture", int.class, int.class)
                    .invoke(null, texture2d, texture.getId());
            int width = (int) gl11.getMethod("glGetTexLevelParameteri",
                            int.class, int.class, int.class)
                    .invoke(null, texture2d, 0, textureWidth);
            int height = (int) gl11.getMethod("glGetTexLevelParameteri",
                            int.class, int.class, int.class)
                    .invoke(null, texture2d, 0, textureHeight);
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
                BlueprintExporter.LOGGER.warn("capture: atlas skip label={} reason=size {}x{}", label,
                        width, height);
                return false;
            }
            java.nio.ByteBuffer pixels = (java.nio.ByteBuffer) Class
                    .forName("org.lwjgl.BufferUtils")
                    .getMethod("createByteBuffer", int.class)
                    .invoke(null, width * height * 4);
            gl11.getMethod("glPixelStorei", int.class, int.class)
                    .invoke(null, packAlignment, 1);
            gl11.getMethod("glGetTexImage", int.class, int.class, int.class, int.class,
                            java.nio.ByteBuffer.class)
                    .invoke(null, texture2d, 0, rgba, unsignedByte, pixels);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                // GL's origin is the bottom-left corner and a PNG's is the top-left, so the rows are
                // read in reverse; without this the model samples upside down.
                int sourceRow = (height - 1 - y) * width * 4;
                for (int x = 0; x < width; x++) {
                    int index = sourceRow + x * 4;
                    int r = pixels.get(index) & 0xFF;
                    int g = pixels.get(index + 1) & 0xFF;
                    int b = pixels.get(index + 2) & 0xFF;
                    int a = pixels.get(index + 3) & 0xFF;
                    image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(image, "png", target.toFile());
            BlueprintExporter.LOGGER.info("capture: atlas label={} size={}x{} bytes={} path={} ok=true",
                    label, width, height, Files.size(target), target);
            return true;
        } catch (Throwable t) {
            // A context that refuses the read is not a reason to lose the geometry.
            BlueprintExporter.LOGGER.warn("capture: atlas skip label={} reason={}:{}", label,
                    t.getClass().getSimpleName(), t.getMessage());
            return false;
        }
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /** Remembers the last name so the mode is sticky between exports. */
    private static void rememberName(String last) {
        try {
            Path path = Path.of("config", "firecontrolcompat-capture.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, "mode=capture\nlast=" + last + "\n");
        } catch (Throwable t) {
            FireControlCompat.LOGGER.warn("capture: could not remember the mode: {}", t.toString());
        }
    }
}
