package com.qkdream.firecontrolcompat.blueprint.capture;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.blueprint.BlueprintBake;
import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
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
 * <p>Recording never replaces the underlying consumer: every call is forwarded first and recorded
 * second, so the frame the player sees is unchanged and a failure here cannot break rendering.</p>
 */
public final class CaptureExport {

    /** Vertices past this are not recorded any more, so a big contraption cannot exhaust memory. */
    private static final int MAX_VERTICES = 2_000_000;

    /** Largest number of frames a recording may stay open for, so a missed close cannot leak. */
    private static final int MAX_TICKS = 40;

    private static boolean recording;
    private static int ticksOpen;
    private static String name = "capture";
    private static AABB region = null;
    private static int limitHits;

    /** Vertices per render type, in the order the types were first seen. */
    private static final Map<String, Group> GROUPS = new LinkedHashMap<>();

    /** Every render type name seen while recording, including ones with no vertices kept. */
    private static final LinkedHashSet<String> SEEN_TYPES = new LinkedHashSet<>();

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
     */
    public static void arm(String requestedName, double radius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            BlueprintExporter.LOGGER.warn("capture: not in game, nothing armed");
            return;
        }
        name = requestedName == null || requestedName.isBlank() ? "capture" : requestedName;
        Vec3 centre = minecraft.player.position();
        double r = Math.max(2.0, Math.min(256.0, radius));
        region = new AABB(centre.x - r, centre.y - r, centre.z - r,
                          centre.x + r, centre.y + r, centre.z + r);
        GROUPS.clear();
        SEEN_TYPES.clear();
        limitHits = 0;
        ticksOpen = 0;
        recording = true;
        rememberName(name);
        BlueprintExporter.LOGGER.info("capture: open region={} groups=0 limit={}",
                String.format(Locale.ROOT, "[%.1f, %.1f, %.1f .. %.1f, %.1f, %.1f]",
                        region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ),
                MAX_VERTICES);
    }

    /** Called once per client tick: closes the recording when the window is over. */
    public static void tick() {
        if (!recording) {
            return;
        }
        ticksOpen++;
        if (ticksOpen >= 1) {
            close("frame-end");
        }
    }

    /** Wraps a consumer so it records as it forwards. The delegate is always written to. */
    public static VertexConsumer wrap(VertexConsumer delegate, RenderType type) {
        if (!recording || delegate == null || type == null) {
            return delegate;
        }
        try {
            String typeName = typeName(type);
            SEEN_TYPES.add(typeName);
            Group group = GROUPS.computeIfAbsent(typeName, Group::new);
            return new Recorder(delegate, group);
        } catch (Throwable t) {
            // A recording failure must never stop the frame from being drawn.
            return delegate;
        }
    }

    /** A readable name for a render type. */
    private static String typeName(RenderType type) {
        try {
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

    /** A group: every quad recorded for one render type. */
    private static final class Group {
        final String type;
        final List<float[][]> quads = new ArrayList<>();   // [4][8] pos3 + uv2 + n3
        int dropped;

        Group(String type) {
            this.type = type;
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
                        "capture: close reason={} verts=0 faces=0 materials=0 dropped={} atlas=false",
                        why, dropped);
                BlueprintExporter.LOGGER.warn(
                        "capture: nothing recorded - falling back to the per-block exporter"
                                + " (the buffer source may not have been wrapped; groups seen={})",
                        SEEN_TYPES);
                return;
            }
            boolean atlas = write();
            BlueprintExporter.LOGGER.info(
                    "capture: close reason={} verts={} faces={} materials={} dropped={} limit_hits={}"
                            + " atlas={} groups={} ms={}",
                    why, verts, faces, materials, dropped, limitHits, atlas, SEEN_TYPES,
                    System.currentTimeMillis() - started);
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.error("capture: write failed", t);
        } finally {
            GROUPS.clear();
            SEEN_TYPES.clear();
            region = null;
            ticksOpen = 0;
        }
    }

    /**
     * Writes {@code <name>_capture.obj}, its .mtl and the block atlas they refer to.
     *
     * @return whether the atlas was written
     */
    private static boolean write() throws Exception {
        Path outDir = BlueprintBake.outputDir();
        Files.createDirectories(outDir);
        String base = name + "_capture";
        Path obj = outDir.resolve(base + ".obj");
        Path mtl = outDir.resolve(base + ".mtl");

        StringBuilder body = new StringBuilder(1 << 20);
        body.append("# frame capture by firecontrolcompat\n");
        body.append("mtllib ").append(base).append(".mtl\n");
        int baseIndex = 1;
        Map<String, String> materialNames = new LinkedHashMap<>();
        for (Group group : GROUPS.values()) {
            if (group.quads.isEmpty()) {
                continue;
            }
            String material = "M_cap_" + group.type;
            materialNames.put(group.type, material);
            body.append("o ").append(group.type).append('\n');
            body.append("usemtl ").append(material).append('\n');
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

        try (BufferedWriter writer = Files.newBufferedWriter(mtl)) {
            writer.write("# frame capture materials\n");
            for (Map.Entry<String, String> entry : materialNames.entrySet()) {
                String type = entry.getKey();
                writer.write("\nnewmtl " + entry.getValue() + "\n");
                writer.write("# render_type: " + type + "\n");
                writer.write("# uv_space: atlas\n");
                writer.write("Kd 1.000 1.000 1.000\nKa 0.000 0.000 0.000\nKs 0.000 0.000 0.000\n");
                writer.write("map_Kd textures/blocks_atlas.png\n");
                String lower = type.toLowerCase(Locale.ROOT);
                if (lower.contains("translucent")) {
                    writer.write("d 0.500\nTr 0.500\nillum 2\n");
                } else if (lower.contains("cutout")) {
                    writer.write("illum 2\n");
                } else {
                    writer.write("d 1.0\nillum 1\n");
                }
            }
        }
        boolean atlas = dumpAtlas(outDir);
        BlueprintExporter.LOGGER.info("capture: wrote {} and {} ({} bytes) atlas={}", obj.getFileName(),
                mtl.getFileName(), Files.size(obj), atlas);
        for (String type : materialNames.keySet()) {
            String layer = type.toLowerCase(Locale.ROOT).contains("translucent") ? "translucent"
                    : type.toLowerCase(Locale.ROOT).contains("cutout") ? "cutout" : "solid";
            BlueprintExporter.LOGGER.info("capture: material={} layer={} tex=atlas", type, layer);
        }
        return atlas;
    }

    /**
     * Reads the stitched block atlas off the GPU and writes it beside the mesh.
     *
     * <p>The recorded UVs are atlas coordinates, so the atlas itself is the only texture that makes
     * them sample the right texels. `TextureAtlas.getWidth/getHeight` are package private in 1.21.1,
     * so the size is asked of GL instead - the same place the pixels come from - and the read is done
     * on the render thread, which is where this code runs from the client tick.</p>
     *
     * @return whether the image was written
     */
    private static boolean dumpAtlas(Path outDir) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                BlueprintExporter.LOGGER.warn("capture: atlas skip reason=no-client");
                return false;
            }
            var texture = minecraft.getTextureManager().getTexture(
                    net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            if (texture == null || texture.getId() <= 0) {
                BlueprintExporter.LOGGER.warn("capture: atlas skip reason=no-texture-id");
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
                BlueprintExporter.LOGGER.warn("capture: atlas skip reason=size {}x{}", width, height);
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
            Path target = outDir.resolve("textures").resolve("blocks_atlas.png");
            Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(image, "png", target.toFile());
            BlueprintExporter.LOGGER.info("capture: atlas size={}x{} bytes={} path={} ok=true",
                    width, height, Files.size(target), target);
            return true;
        } catch (Throwable t) {
            // A context that refuses the read is not a reason to lose the geometry.
            BlueprintExporter.LOGGER.warn("capture: atlas skip reason={}:{}", t.getClass().getSimpleName(),
                    t.getMessage());
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
