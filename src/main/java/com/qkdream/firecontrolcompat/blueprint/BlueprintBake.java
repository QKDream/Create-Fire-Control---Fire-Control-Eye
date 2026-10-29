package com.qkdream.firecontrolcompat.blueprint;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.qkdream.firecontrolcompat.blueprint.capture.CaptureExport;
import com.qkdream.firecontrolcompat.blueprint.capture.CaptureScope;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bakes a Sable blueprint by driving the real block-model pipeline.
 *
 * <p>Read path, taken from the game itself:
 * <ol>
 *   <li>{@code StructureTemplate.load} - the blueprint is a structure template</li>
 *   <li>every entry of its {@code sub_levels} list is <em>another</em> template,
 *       optionally alongside {@code position} / {@code orientation}</li>
 *   <li>each of those is placed into its own catnip {@code SchematicLevel}</li>
 *   <li>each block is tesselated with {@code ModelBlockRenderer.tesselateBlock},
 *       which is the same call Create's isometric preview and the in-game chunk
 *       renderer make - so every mod model, Copycats+ runtime model core,
 *       block-entity {@code ModelData} and block-entity renderer resolves the
 *       way it does in game</li>
 * </ol>
 *
 * <p>Sprites are picked up separately by walking the model's own quads, because
 * {@code VertexConsumer} in 1.21.1 has no {@code setSprite} and the renderer's
 * {@code putQuadData} is private. The UVs that reach the consumer are already
 * atlas-relative, so pairing them with the atlas the sprite lives in is enough.
 */
public final class BlueprintBake {

    private BlueprintBake() {}

    /**
     * Extra logging for a bake that comes out wrong.
     *
     * <p>On by default for now, because the first bakes came out with a third of the
     * geometry and a bounding box twice the size of the blueprint's own
     * canonical_bound, and the cause is not visible from the .obj alone - the numbers
     * below say whether blocks are missing, misplaced, or emitting geometry away from
     * themselves. A handful of lines per blueprint; turn it off with
     * {@code -Dsableexport.diagnostics=false} once the exporter is trusted.
     */
    private static final boolean DIAGNOSTICS =
        !"false".equalsIgnoreCase(System.getProperty("sableexport.diagnostics", "true"));

    /**
     * Reported once per session: how a block's position is turned into its vertices.
     *
     * <p>The pose probe exists because several arrangements all produce a plausible model
     * and only one is right, and the wrong ones are not obviously wrong:
     *
     * <ul>
     *   <li>adding no block offset at all stacks every block of a sub-level on one point -
     *       an eleven panel skirt became a single 0.19 thick slab;</li>
     *   <li>adding the raw offset through {@code pose.translate} gets it rotated by the
     *       pose's own transform as well, putting the skirt along x instead of along z;</li>
     *   <li>pre-rotating it and then passing it through {@code pose.translate} rotates it
     *       twice, and for this blueprint's 180 degree weld {@code R . R} is {@code -I} -
     *       the skirt landed at z[-10..1] where the blueprint has it at z[1..12];</li>
     *   <li>adding the raw offset a second time through the collector as well doubled the
     *       whole model: 16.39 x 7.73 x 34.19 for a bound of 8.00 x 5.52 x 13.00.</li>
     * </ul>
     *
     * <p>What works is to transform the offset with the quad's own pose inside
     * {@code addVertex}, exactly as the vertex is, and add the result. The probe prints the
     * frame, the block position and the rotated value, which is what tells these apart from
     * a log rather than from another guess.
     */
    /** Whether parts drawn by a renderer are left out. See {@link #excludedFromExport}. */
    private static final boolean EXCLUDE_PARTS =
        !"false".equalsIgnoreCase(System.getProperty("sableexport.excludeParts", "true"));

    private static boolean POSE_PROBE_LOGGED = false;

    /** The UV conversion is reported once per session; the numbers are the same throughout. */
    private static boolean UV_PROBE_LOGGED = false;

    /**
     * What to divide a block-entity renderer's UVs by, or 0 to leave them alone.
     *
     * <p>Set with {@code -Dsableexport.beUvScale=N}. A renderer writes UVs in its texture's own
     * pixels while the exporter had been treating them as 0..1, which samples the wrong part of
     * the tile. The right divisor is the tile's pixel size and varies per texture, so this is
     * settled by measurement rather than argued about: 16 puts the exported track material's
     * span of 0.893 at 0.056, and 32 puts it at 0.028, against 0.5 for the model's own elements.
     */
    private static final float BE_UV_SCALE =
        Float.parseFloat(System.getProperty("sableexport.beUvScale", "1"));

    /** Reported once, so the log says which sprite the path lookup produced. */
    private static boolean SPRITE_LOOKUP_LOGGED = false;

    /** A handful of copying blocks, so the log says whether their material survived placement. */
    private static int COPYCAT_PROBES = 0;

    /** A handful of atlas tiles, so the log says whether the reverse lookup found anything. */
    private static int SPRITE_TILE_LOGGED = 0;

    /**
     * How many {@code sprite-fallback:} lines one bake may write.
     *
     * <p>Bounded because a track renderer alone produces hundreds of quads and the answer is the
     * same for all of them: which tile the pin fell back to and whether the coordinates were
     * local. Twenty is enough to name every kind of part in a selection.
     */
    private static int SPRITE_FALLBACK_LOGGED = 0;

    /**
     * The index given to the blueprint's own body, shifted clear of {@code sub_levels[0]}.
     *
     * <p>Most blueprints keep nothing in the main template - a zero-sized body is normal,
     * because the blueprint tool puts everything in sub-levels - but the index has to be
     * reserved so the two can never be confused for one another in the manifest.
     */
    private static final int MAIN_BODY = -1;

    /**
     * The config file the exporter shares with the pod laser, for the two capture settings.
     *
     * <p>{@code captureOnExport=true|false} decides whether a normal export also arms one frame
     * capture; a missing line means true, so the behaviour arrives with the mod rather than with
     * a file somebody has to find. {@code captureRadius=8..64} is the half size of the recorded
     * region in blocks and defaults to 32 - wide enough for a vehicle, small enough that the
     * neighbouring terrain does not dominate the mesh.</p>
     */
    private static final String POD_CONFIG_FILE = "config/firecontrolcompat-pod.txt";

    /** Half size of the capture region when the config says nothing, in blocks. */
    private static final int CAPTURE_RADIUS_DEFAULT = 32;

    /** The range {@code captureRadius} is clamped into, matching the command's own argument. */
    private static final int CAPTURE_RADIUS_MIN = 8;
    private static final int CAPTURE_RADIUS_MAX = 64;

    /** A blueprint file the GUI can offer for export. */
    public static final class Candidate {
        public final Path path;
        public final String name;

        Candidate(Path path) {
            this.path = path;
            this.name = path.getFileName().toString().replaceAll("\\.nbt$", "");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Blueprints available to export, from every folder the game might keep them in.
     * A pack that redirects its game directory keeps schematics beside the version
     * folders rather than in {@code .minecraft}, so all of them are scanned.
     */
    public static List<Candidate> listBlueprints() {
        List<Candidate> out = new ArrayList<>();
        for (Path dir : candidateDirs()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".nbt"))
                      .sorted()
                      .forEach(p -> out.add(new Candidate(p)));
            } catch (IOException ignored) {
                // an unreadable folder must not stop the others being listed
            }
        }
        return out;
    }

    /** One line of GUI feedback per blueprint. */
    public record Outcome(String name, boolean ok, String detail) {}

    /**
     * Bake several blueprints, reporting after each so a screen can show progress.
     *
     * <p>Runs on the calling (render) thread - the bake needs baked models, which
     * only exist there - so the callback must not block. It only records the line;
     * the loop finishing is what lets the screen carry on.
     */
    public static List<Outcome> bakeAll(List<Candidate> targets,
                                        java.util.function.Consumer<Outcome> progress) {
        List<Outcome> out = new ArrayList<>();
        for (Candidate target : targets) {
            Outcome outcome;
            try {
                int tris = bake(target.path);
                outcome = new Outcome(target.name, true, tris + " triangles");
                BlueprintExporter.LOGGER.info("exported {} ({} triangles)", target.name, tris);
            } catch (Throwable t) {
                outcome = new Outcome(target.name, false, String.valueOf(t));
                BlueprintExporter.LOGGER.error("failed to export {}", target.name, t);
            }
            out.add(outcome);
            if (progress != null) {
                progress.accept(outcome);
            }
        }
        return out;
    }

    public static int run(net.minecraft.commands.CommandSourceStack source, String name) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            source.sendFailure(Component.literal("join a world first"));
            return 0;
        }

        // A pack that redirects its game directory keeps schematics beside the
        // version folders rather than in .minecraft, so search every place they
        // can live - and say where we looked when nothing matches.
        List<Path> dirs = candidateDirs();
        List<Path> targets = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".nbt"))
                      .filter(p -> name == null || p.getFileName().toString().contains(name))
                      .sorted()
                      .forEach(targets::add);
            }
        }
        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("no .nbt matching "
                + (name == null ? "*" : name) + "; searched: " + dirs));
            return 0;
        }
        int done = 0;
        for (Path nbt : targets) {
            try {
                BlueprintExporter.NOTES.clear();
                int tris = bake(nbt);
                Path obj = outputDir().resolve(stem(nbt) + ".obj");
                source.sendSuccess(() -> Component.literal(
                    "sableexport: " + obj + " (" + tris + " faces)"), false);
                // Surface anything that went wrong but did not stop the export, in the
                // chat where it will actually be read. A silently untextured mesh is
                // otherwise indistinguishable from a bug in Blender's importer.
                java.util.List<String> notes = new java.util.ArrayList<>(BlueprintExporter.NOTES);
                for (int i = 0; i < Math.min(notes.size(), 6); i++) {
                    String note = notes.get(i);
                    source.sendSuccess(() -> Component.literal("  note: " + note), false);
                }
                if (notes.size() > 6) {
                    int extra = notes.size() - 6;
                    source.sendSuccess(() -> Component.literal(
                        "  note: ...and " + extra + " more (see the log)"), false);
                }
                done++;
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.error("failed to bake {}", nbt, t);
                source.sendFailure(Component.literal("failed " + nbt.getFileName() + ": " + t));
            }
        }
        return done;
    }

    private static String stem(Path nbt) {
        return nbt.getFileName().toString().replaceAll("\\.nbt$", "");
    }

    /** Every folder a blueprint can sit in for this instance. */
    public static List<Path> candidateDirs() {
        List<Path> dirs = new ArrayList<>();
        Path game = Minecraft.getInstance().gameDirectory.toPath();
        dirs.add(game.resolve("schematics"));
        Path parent = game.getParent();
        Path versions = parent == null ? null : parent.getParent();
        if (versions != null && Files.isDirectory(versions)) {
            try (var stream = Files.list(versions)) {
                var it = stream.filter(Files::isDirectory).iterator();
                while (it.hasNext()) {
                    Path s = it.next().resolve("schematics");
                    if (Files.isDirectory(s) && !dirs.contains(s)) {
                        dirs.add(s);
                    }
                }
            } catch (IOException ignored) {
                // best effort: a locked version folder must not break the export
            }
        }
        return dirs;
    }

    /** Reports where the exporter looks, so a missing blueprint is diagnosable. */
    public static void reportDirs(net.minecraft.commands.CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("sableexport search paths:");
        for (Path dir : candidateDirs()) {
            int n = -1;
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    n = (int) stream.filter(p -> p.getFileName().toString().endsWith(".nbt")).count();
                } catch (IOException ignored) {
                    n = -1;
                }
            }
            sb.append("\n  ").append(dir).append(n < 0 ? "  (missing)" : "  (" + n + " nbt)");
        }
        sb.append("\noutput: ")
          .append(Minecraft.getInstance().gameDirectory.toPath().resolve("sable_export"));
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
    }

    public static Path outputDir() throws IOException {
        Path out = Minecraft.getInstance().gameDirectory.toPath().resolve("sable_export");
        Files.createDirectories(out);
        return out;
    }

    /** The output folder as a string, without throwing - for screen labels. */
    public static String outputDirSafe() {
        try {
            return outputDir().toString();
        } catch (IOException e) {
            return Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("sable_export").toString();
        }
    }

    // ------------------------------------------------------------ format bridge

    /**
     * Rewrite Sable's blueprint layout into the shape {@code StructureTemplate.load}
     * reads.
     *
     * <p>A blueprint is <em>not</em> a Minecraft structure template. The two differ
     * in every field that matters:
     *
     * <pre>
     *   standard StructureTemplate      Sable blueprint
     *   ----------------------------    ----------------------------
     *   palette                         block_palette
     *   blocks[].pos = [x, y, z]        blocks[].local_pos = {x, y, z}
     *   blocks[].state = &lt;int&gt;          blocks[].palette_id
     *   size = [x, y, z]                local_bounds = {min_x..max_z}
     * </pre>
     *
     * <p>Sable's own loader gets away with it because it parses the file itself. A
     * vanilla load on the same bytes finds no {@code size}, no {@code palette} and no
     * {@code pos}, so it reports a zero-sized template - which is what made every
     * export fail with "template has zero size", and would have made the sub-levels
     * silently load as nothing even once that was fixed.
     *
     * <p>Normalising here rather than converting the files on disk means both entry
     * points - the command and the blueprint tool - accept either layout.
     */
    private static CompoundTag normaliseTemplate(CompoundTag src) {
        if (src == null) {
            return new CompoundTag();
        }
        // Already a structure template: leave it completely alone.
        if (src.contains("size", Tag.TAG_LIST) && src.contains("palette", Tag.TAG_LIST)) {
            return src;
        }

        CompoundTag out = new CompoundTag();
        if (src.contains("block_palette")) {
            out.put("palette", src.getList("block_palette", Tag.TAG_COMPOUND).copy());
        } else if (src.contains("palette")) {
            out.put("palette", src.getList("palette", Tag.TAG_COMPOUND).copy());
        }

        ListTag blocks = new ListTag();
        ListTag source = src.getList("blocks", Tag.TAG_COMPOUND);
        // Sable keeps block entities in a side list and points at it by index, where a
        // structure template inlines the NBT per block. Copycats+ read their entire
        // shape from that NBT, so dropping it would silently lose their geometry.
        ListTag blockEntities = src.getList("block_entities", Tag.TAG_COMPOUND);
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int i = 0; i < source.size(); i++) {
            CompoundTag b = source.getCompound(i);
            int x;
            int y;
            int z;
            if (b.contains("pos", Tag.TAG_LIST)) {
                ListTag pos = b.getList("pos", Tag.TAG_INT);
                x = pos.getInt(0);
                y = pos.getInt(1);
                z = pos.getInt(2);
            } else {
                CompoundTag p = b.getCompound("local_pos");
                x = p.getInt("x");
                y = p.getInt("y");
                z = p.getInt("z");
            }
            CompoundTag nb = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(net.minecraft.nbt.IntTag.valueOf(x));
            pos.add(net.minecraft.nbt.IntTag.valueOf(y));
            pos.add(net.minecraft.nbt.IntTag.valueOf(z));
            nb.put("pos", pos);
            nb.putInt("state", b.contains("palette_id")
                ? b.getInt("palette_id") : b.getInt("state"));
            if (b.contains("nbt", Tag.TAG_COMPOUND)) {
                nb.put("nbt", b.getCompound("nbt").copy());
            } else if (b.contains("block_entity_data_id", Tag.TAG_INT)) {
                int id = b.getInt("block_entity_data_id");
                if (id >= 0 && id < blockEntities.size()) {
                    nb.put("nbt", blockEntities.getCompound(id).copy());
                }
            }
            blocks.add(nb);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        out.put("blocks", blocks);

        if (src.contains("entities")) {
            out.put("entities", src.getList("entities", Tag.TAG_COMPOUND).copy());
        }

        // Size: prefer Sable's own bounds, but only when every axis is positive -
        // local_bounds has been seen with a zero on one axis, and a zero size makes
        // the template unusable, so the block extents are the safer answer.
        int sx = maxX + 1;
        int sy = maxY + 1;
        int sz = maxZ + 1;
        if (src.contains("local_bounds", Tag.TAG_COMPOUND)) {
            CompoundTag lb = src.getCompound("local_bounds");
            int bx = lb.getInt("max_x") - lb.getInt("min_x") + 1;
            int by = lb.getInt("max_y") - lb.getInt("min_y") + 1;
            int bz = lb.getInt("max_z") - lb.getInt("min_z") + 1;
            if (bx > 0 && by > 0 && bz > 0) {
                sx = Math.max(sx, bx);
                sy = Math.max(sy, by);
                sz = Math.max(sz, bz);
            }
        }
        ListTag size = new ListTag();
        size.add(net.minecraft.nbt.IntTag.valueOf(Math.max(1, sx)));
        size.add(net.minecraft.nbt.IntTag.valueOf(Math.max(1, sy)));
        size.add(net.minecraft.nbt.IntTag.valueOf(Math.max(1, sz)));
        out.put("size", size);
        out.putInt("DataVersion", 3953);
        return out;
    }

    // ------------------------------------------------------------------- bake

    /** Bake one blueprint by path. Returns the triangle count. */
    public static int bake(Path nbt) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            throw new IOException("join a world first");
        }
        return bakeOne(mc, level, readRoot(nbt), stem(nbt));
    }

    /**
     * Bake a blueprint already in memory, under the given output name.
     *
     * <p>The Blueprint Tool keeps its blueprints as bytes and hands them over after
     * its own preview-stripping pass, so this is the entry point the in-game GUI
     * uses. Going through a CompoundTag rather than a file also avoids assuming
     * those bytes would survive a formal {@code StructureTemplate.load}.
     */
    /**
     * The bake entry.
     *
     * <p>States the thread it was asked on, and moves the werk to the client thread when it was
     * asked from anywhere else - the renderer pass needs baked models and block-entity renderers,
     * which only exist there. `offloaded=` and `wait_ms=` say whether that happened and what it
     * cost, so a slow export from a worker thread is visible rather than mysterious.</p>
     */
    public static int bake(CompoundTag root, String name) throws IOException {
        logThread("bake", name);
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            throw new IOException("no client: the bake needs baked models");
        }
        if (client.isSameThread()) {
            return bakeOnClientThread(root, name);
        }
        final int[] result = new int[1];
        final Throwable[] failure = new Throwable[1];
        final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        long started = System.nanoTime();
        client.execute(() -> {
            try {
                result[0] = bakeOnClientThread(root, name);
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.complete(null);
            }
        });
        try {
            done.get(180, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception timeout) {
            failure[0] = timeout;
        }
        BlueprintExporter.LOGGER.info("blueprint export: offloaded=true wait_ms={} name={} failed={}",
            (System.nanoTime() - started) / 1_000_000L, name, failure[0] != null);
        if (failure[0] instanceof IOException io) {
            throw io;
        }
        if (failure[0] != null) {
            throw new IOException(failure[0]);
        }
        return result[0];
    }

    /**
     * The thread and the resources the export is really running with.
     *
     * <p>`models=` is the one that matters: it is true only when the model manager answers, which is
     * what "baked models are reachable" means. On the server thread it is false and every block
     * would have to be guessed at.</p>
     */
    private static void logThread(String stage, String name) {
        Minecraft client = null;
        boolean sameThread = false;
        boolean models = false;
        try {
            client = Minecraft.getInstance();
            sameThread = client != null && client.isSameThread();
            models = client != null && client.getModelManager() != null
                && client.getModelManager().getMissingModel() != null;
        } catch (Throwable ignored) {
            // a half-initialised client is reported as no models rather than thrown
        }
        BlueprintExporter.LOGGER.info(
            "blueprint export: stage={} name={} thread={} side={} models={} client-thread={}",
            stage, name, Thread.currentThread().getName(),
            net.neoforged.fml.loading.FMLEnvironment.dist.isClient() ? "client" : "server",
            models, sameThread);
    }

    /** The bake itself; always called on the client thread by {@link #bake(CompoundTag, String)}. */
    private static int bakeOnClientThread(CompoundTag root, String name) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            throw new IOException("join a world first");
        }
        return bakeOne(mc, level, root, name);
    }

    private static int bakeOne(Minecraft mc, Level level, CompoundTag root,
                               String name) throws IOException {
        StructureTemplate template = new StructureTemplate();
        CompoundTag body = normaliseTemplate(root);
        try {
            template.load(level.holderLookup(Registries.BLOCK), body);
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("blueprint has no readable main body: {}", t.toString());
        }

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer renderer = dispatcher.getModelRenderer();
        Collector collector = new Collector();
        // Opens the owner-tagging run this export will report on at the end. It only records:
        // every face keeps the block it came from, and the OBJ is written exactly as before.
        CaptureScope.reset();

        // The main body, if there is one. A zero-sized body is normal and not an
        // error: a blueprint whose contents all live in sub_levels has an empty main
        // template, which is exactly what the blueprint tool hands over. Throwing
        // here made every export fail with "template has zero size".
        if (!template.getSize().equals(Vec3i.ZERO)) {
            // Sub-level indices are shifted by one so the blueprint's own body can have an
            // index of its own without colliding with sub_levels[0].
            placeAndRender(level, template, dispatcher, renderer, collector,
                           new Matrix4f(), MAIN_BODY);
        }

        // Sub-levels: each entry is its own template in its own frame.
        ListTag subs = root.getList("sub_levels", Tag.TAG_COMPOUND);
        // Sub-levels: each entry is its own template, and BlueprintAssembly works out
        // where it actually sits on the assembled vehicle. Placing them at the origin
        // left the hull, turret and skirts as separate slabs.
        Map<Integer, BlueprintAssembly.Placement> placement =
            BlueprintAssembly.compute(root, subs.size());

        for (int i = 0; i < subs.size(); i++) {
            CompoundTag sub = subs.getCompound(i);
            StructureTemplate subTemplate = new StructureTemplate();
            try {
                subTemplate.load(level.holderLookup(Registries.BLOCK), normaliseTemplate(sub));
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.warn("sub_level {} failed to load", i, t);
                continue;
            }
            if (subTemplate.getSize().equals(Vec3i.ZERO)) {
                BlueprintExporter.LOGGER.warn("DIAG sub {} normalised to zero size", i);
                continue;
            }
            BlueprintAssembly.Placement p = placement.get(i);
            Matrix4f frame = p == null ? new Matrix4f() : BlueprintAssembly.toMatrix(p);
            if (DIAGNOSTICS) {
                BlueprintExporter.LOGGER.info(
                    "DIAG sub {} placement translation=({}, {}, {}) identity={}",
                    i, frame.m30(), frame.m31(), frame.m32(),
                    p == null || p.isIdentity());
            }
            placeAndRender(level, subTemplate, dispatcher, renderer, collector, frame, i);
        }

        if (collector.faces.isEmpty()) {
            // Say why rather than writing an empty .obj and calling it success. The
            // two causes are a blueprint with nothing in it and a payload whose shape
            // this reader does not understand, and the counts tell them apart.
            throw new IOException("nothing to export: sub_levels=" + subs.size()
                + ", main body size=" + body.getList("size", Tag.TAG_INT)
                + ", keys=" + root.getAllKeys());
        }

        int written = write(collector, name);

        // The end of the tagging run: one line per entity the blueprint carries (there is no
        // render dispatch to wrap in a bake, so these are detected from its own NBT), then the
        // totals. Both are written after the mesh so they describe the file that was produced.
        CaptureScope.noteBlueprintEntities(root);
        CaptureScope.summarise();

        // A frame capture is armed last, once the per-block .obj/.mtl/PNGs are closed on disk, so
        // nothing it does can reach them. It is not waited for: the recording happens in the frames
        // that follow this tick and the client tick closes and writes it.
        armAutoCapture(name);

        if (DIAGNOSTICS) {
            // A bbox far larger than the blueprint's own canonical_bound means some
            // model emitted geometry away from its block, which is worth naming.
            float[] lo = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] hi = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (Face f : collector.faces) {
                for (int k = 0; k < f.count; k++) {
                    lo[0] = Math.min(lo[0], f.pos[k * 3]);
                    hi[0] = Math.max(hi[0], f.pos[k * 3]);
                    lo[1] = Math.min(lo[1], f.pos[k * 3 + 1]);
                    hi[1] = Math.max(hi[1], f.pos[k * 3 + 1]);
                    lo[2] = Math.min(lo[2], f.pos[k * 3 + 2]);
                    hi[2] = Math.max(hi[2], f.pos[k * 3 + 2]);
                }
            }
            BlueprintExporter.LOGGER.info(
                "DIAG total faces={} bbox x[{}..{}] y[{}..{}] z[{}..{}] mats={}",
                written, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2],
                collector.materialCount());

            // One line that is the whole export, in the fields that decide whether it is correct.
            // "Is the export worse than it used to be" is otherwise answered by looking at the model
            // in Blender; this makes it a number a report can carry: how many faces and materials,
            // how many source blocks, block entities and entities, the size the geometry covers, and
            // the format version written. Compare two exports of the same structure and any real
            // regression shows up as a count that moved.
            try {
                net.minecraft.nbt.ListTag subList = root.getList("sub_levels", Tag.TAG_COMPOUND);
                int blockEntities = 0;
                int entities = root.getList("entities", Tag.TAG_COMPOUND).size();
                for (int sub = 0; sub < subList.size(); sub++) {
                    CompoundTag entry = subList.getCompound(sub);
                    blockEntities += entry.getList("block_entities", Tag.TAG_COMPOUND).size();
                    entities += entry.getList("entities", Tag.TAG_COMPOUND).size();
                }
                BlueprintExporter.LOGGER.info(
                    "blueprint export summary: name={} sub_levels={} block_entities={} entities={}"
                        + " faces={} materials={} size={}x{}x{} DataVersion={} materials2={} notes={}",
                    name, subList.size(), blockEntities, entities, written,
                    collector.materialCount(),
                    String.format(java.util.Locale.ROOT, "%.2f", hi[0] - lo[0]),
                    String.format(java.util.Locale.ROOT, "%.2f", hi[1] - lo[1]),
                    String.format(java.util.Locale.ROOT, "%.2f", hi[2] - lo[2]),
                    3953, collector.materialCount(), BlueprintExporter.NOTES.size());
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.warn("could not summarise the export: {}", t.toString());
            }

            // The blueprint states its own size, in the same axes. Comparing against it
            // is what tells a scale error from a placement error, and it is reported
            // rather than guessed at: the model's extent should match this bound to
            // within a block or so.
            CompoundTag bound = root.getCompound("canonical_bound");
            if (!bound.isEmpty()) {
                float dx = bound.getFloat("maxX") - bound.getFloat("minX");
                float dy = bound.getFloat("maxY") - bound.getFloat("minY");
                float dz = bound.getFloat("maxZ") - bound.getFloat("minZ");
                float[] got = new float[] {hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]};
                BlueprintExporter.LOGGER.info(
                    "DIAG canonical size x[{}] y[{}] z[{}] vs exported-size "
                    + "x[{}] y[{}] z[{}]",
                    dx, dy, dz, got[0], got[1], got[2]);
                // canonical_bound is in Minecraft axes; the export is in Blender axes,
                // so the mapping is (x, z, y) with y negated.
                float[] expected = new float[] {dx, dz, dy};
                float worst = 0f;
                for (int axis = 0; axis < 3; axis++) {
                    float ratio = expected[axis] <= 0f ? 1f : got[axis] / expected[axis];
                    worst = Math.max(worst, Math.abs(ratio - 1f));
                }
                if (worst > 0.4f) {
                    BlueprintExporter.NOTES.add(
                        "exported size does not match the blueprint's own bound"
                        + " (off by " + Math.round(worst * 100) + "%)");
                }
            }
        }
        return written;
    }

    /**
     * Arms the one frame capture that follows a normal export.
     *
     * <p>This is the answer to "the capture mode never fired once": the recording machinery was
     * only reachable from {@code /sableexport capture}, which nobody typed, while the parts that
     * report as missing (tracks, tyres, moving structures) are the ones only a frame capture can
     * produce. So an ordinary export now leaves with a capture of its own, named after the export,
     * and the player gets {@code <name>.obj} and {@code <name>_capture.obj} from one action.</p>
     *
     * <p>It never blocks: the window opens here and the recording happens in the render frames
     * after this tick. {@code CaptureExport.tick()} closes it and writes the file. A capture that
     * cannot arm is one warning - the per-block files were already finished above and are not
     * touched either way.</p>
     *
     * @param name file name stem of the export that just finished
     */
    private static void armAutoCapture(String name) {
        try {
            if (!captureOnExport()) {
                BlueprintExporter.LOGGER.info(
                    "capture: auto-export skipped reason=config captureOnExport=false name={}", name);
                return;
            }
            int radius = captureRadius();
            // `arm` logs "capture: armed ..." or "capture: arm failed ..." itself, because it is
            // the only place that knows why it failed (no player, a broken class, a bad state).
            CaptureExport.arm(name + "_capture", radius, "auto-export");
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("capture: arm failed reason={}:{} name={}",
                t.getClass().getSimpleName(), t.getMessage(), name);
        }
    }

    /** One {@code key=value} line of the pod config, or {@code null} when absent or unreadable. */
    private static String podSetting(String key) {
        try {
            Path path = Path.of(POD_CONFIG_FILE);
            if (!Files.isRegularFile(path)) {
                return null;
            }
            for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase(Locale.ROOT).replace(" ", "");
                if (text.isEmpty() || text.startsWith("#") || !text.startsWith(key)) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                return text.substring(equals + 1).trim();
            }
            return null;
        } catch (Throwable t) {
            // A config this mod cannot read is the same as no config at all: the defaults stand.
            BlueprintExporter.LOGGER.info("capture: config unreadable ({}), using the defaults",
                t.toString());
            return null;
        }
    }

    /** {@code captureOnExport}, true unless the config says otherwise. */
    private static boolean captureOnExport() {
        String value = podSetting("captureonexport");
        if (value == null || value.isEmpty()) {
            return true;
        }
        return !("false".equals(value) || "no".equals(value) || "off".equals(value)
            || "0".equals(value));
    }

    /** {@code captureRadius} clamped to 8..64, or 32 when the config is silent. */
    private static int captureRadius() {
        String value = podSetting("captureradius");
        if (value != null && !value.isEmpty()) {
            try {
                return Math.max(CAPTURE_RADIUS_MIN,
                    Math.min(CAPTURE_RADIUS_MAX, Integer.parseInt(value)));
            } catch (NumberFormatException ignored) {
                // a value that is not a number falls through to the default rather than failing
            }
        }
        return CAPTURE_RADIUS_DEFAULT;
    }

    private static void placeAndRender(Level level, StructureTemplate template,
                                       BlockRenderDispatcher dispatcher,
                                       ModelBlockRenderer renderer,
                                       Collector collector, Matrix4f frame, int subLevel) {
        SchematicLevel w = new SchematicLevel(level);
        Vec3i size = template.getSize();
        // Recorded on every face this sub-level produces, so the writer can give it its
        // own object rather than merging it into a per-texture group.
        collector.setSubLevel(subLevel);
        template.placeInWorld(w, BlockPos.ZERO, BlockPos.ZERO,
                              new StructurePlaceSettings(), w.getRandom(), 2);
        for (BlockEntity be : w.getBlockEntities()) {
            be.setLevel(w);
            // `onLoad` as well as `setLevel`, because a mod's block entity reads its own NBT there.
            //
            // This is why the camouflage panels came out as `minecraft:missingno`. A Copycats block
            // is a fake: what it looks like depends on the block it copies, and that is stored in
            // the block entity's NBT under a `Material` key - present in the blueprint, read into a
            // protected `material` field by `onLoad`. The template puts the NBT in place and
            // `setLevel` binds the entity to the level, but neither calls `onLoad`, so the field
            // stayed empty and every copycat drew the missing-texture placeholder.
            //
            // Measured, from the blueprint itself: 71 copycat blocks in `防空悍马.nbt` carry
            // `Material: minecraft:cut_sandstone`, and the export named none of them - it named
            // `minecraft:missingno` instead.
            try {
                be.onLoad();
            } catch (Throwable t) {
                if (DIAGNOSTICS) {
                    BlueprintExporter.LOGGER.debug("onLoad failed for {}: {}",
                                                   be.getType(), t.toString());
                }
            }
            // What a fake block thinks it is copying, right after the template placed it.
            //
            // A Copycats block's appearance is whatever block it mimics, read from its NBT under
            // `Material` by `read` into a field. The export named `minecraft:missingno` for 71 such
            // blocks that the blueprint demonstrably gives `minecraft:cut_sandstone`, so the
            // question is whether the read happened at all. It is asked through the mod's own
            // `getMaterial`, by reflection, because the exporter must not link against Copycats.
            if (DIAGNOSTICS && COPYCAT_PROBES < 8) {
                try {
                    var getMaterial = be.getClass().getMethod("getMaterial");
                    Object material = getMaterial.invoke(be);
                    COPYCAT_PROBES++;
                    BlueprintExporter.LOGGER.info(
                        "DIAG copycat {} at {} -> material={} nbtKeys={}",
                        be.getType(), be.getBlockPos(), material,
                        be.saveWithoutMetadata(w.registryAccess()).getAllKeys());
                } catch (NoSuchMethodException notACopycat) {
                    // Not a copying block, nothing to report.
                } catch (Throwable t) {
                    if (DIAGNOSTICS) {
                        BlueprintExporter.LOGGER.debug("material probe failed: {}", t.toString());
                    }
                }
            }
        }
        int before = collector.faces.size();
        renderLevel(w, dispatcher, renderer, collector, frame);
        // Per-sub-level accounting: how many blocks the template claims, how many are
        // inside the bounds it reports, and how much geometry came out. A sub-level
        // whose block count is far below its template size means the size is wrong;
        // one that renders almost nothing means placement or model lookup failed.
        if (DIAGNOSTICS) {
            int occupied = 0;
            BoundingBox b = w.getBounds();
            for (BlockPos local : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(),
                                                         b.maxX(), b.maxY(), b.maxZ())) {
                BlockState st = w.getBlockState(local.offset(w.anchor));
                if (!st.isAir() && st.getRenderShape() == RenderShape.MODEL) {
                    occupied++;
                }
            }
            BlueprintExporter.LOGGER.info(
                "DIAG sub templateSize={} bounds=[{}, {}, {} .. {}, {}, {}] occupiedBlocks={} tris={}",
                size, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(),
                occupied, collector.faces.size() - before);

            // Where that sub-level's geometry actually landed. Its extent should be the
            // block grid plus one block for the block's own width; anything much larger
            // means blocks are being placed away from their model, which is what breaks a
            // vehicle into loose pieces.
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int index = before; index < collector.faces.size(); index++) {
                Face f = collector.faces.get(index);
                for (int k = 0; k < f.count; k++) {
                    for (int axis = 0; axis < 3; axis++) {
                        float value = f.pos[k * 3 + axis];
                        lo[axis] = Math.min(lo[axis], value);
                        hi[axis] = Math.max(hi[axis], value);
                    }
                }
            }
            if (hi[0] > lo[0]) {
                BlueprintExporter.LOGGER.info(
                    "DIAG sub geometryExtent x[{}..{}] y[{}..{}] z[{}..{}]"
                    + "   (block grid {} x {} x {})",
                    lo[0], hi[0], lo[1], hi[1], lo[2], hi[2],
                    size.getX(), size.getY(), size.getZ());
            }
        }
    }

    /**
     * The rigid transform a sub-level sits at, relative to the vehicle.
     *
     * <p>Sable's exporter writes {@code position} (Vector3d) and {@code orientation}
     * (quaternion) into each sub-level entry and its preview renderer applies
     * exactly these before drawing that sub-level's blocks. Blueprints written by
     * other tools omit them, in which case the grids are already assembled and the
     * frame is the identity.
     */
    private static Matrix4f frameOf(CompoundTag sub) {
        Matrix4f m = new Matrix4f();
        if (sub.contains("position", Tag.TAG_COMPOUND)) {
            CompoundTag p = sub.getCompound("position");
            m.translation(p.getFloat("x"), p.getFloat("y"), p.getFloat("z"));
        }
        if (sub.contains("orientation", Tag.TAG_COMPOUND)) {
            CompoundTag q = sub.getCompound("orientation");
            Quaternionf quat = new Quaternionf(q.getFloat("x"), q.getFloat("y"),
                                               q.getFloat("z"), q.getFloat("w"));
            m.mul(new Matrix4f().rotation(quat));
        }
        return m;
    }

    public static CompoundTag readRoot(Path nbt) throws IOException {
        try (var in = Files.newInputStream(nbt)) {
            return NbtIo.readCompressed(in, NbtAccounter.create(512L * 1024 * 1024));
        }
    }

    // ---------------------------------------------------------------- tesselate

    /**
     * The block a copying block is pretending to be, or null if it is not one.
     *
     * <p>A Copycats panel stores the block it imitates in its block entity's NBT under a
     * `Material` tag, a block-state compound. Its own model is air, so without this the panel
     * contributes nothing but a missing-texture placeholder.
     *
     * <p>Two shapes of the tag are seen in the wild and both are accepted: `Material` as a
     * block-state compound with `Name` and optionally `Properties`, which is what Copycats writes,
     * and `material_data` on some newer blocks.
     *
     * <p>Read through the block-state parser rather than by hand, so properties are applied and a
     * copy of a stair comes out as the right stair.
     */
    private static BlockState copiedMaterial(SchematicLevel w, BlockPos pos, BlockState own) {
        if (own == null || own.isAir()) {
            return null;
        }
        try {
            var entity = w.getBlockEntity(pos);
            if (entity == null) {
                return null;
            }
            net.minecraft.nbt.CompoundTag data = entity.saveWithoutMetadata(w.registryAccess());
            if (data == null || data.isEmpty()) {
                return null;
            }
            net.minecraft.nbt.CompoundTag material = null;
            if (data.contains("Material", Tag.TAG_COMPOUND)) {
                material = data.getCompound("Material");
            } else if (data.contains("material_data", Tag.TAG_COMPOUND)) {
                material = data.getCompound("material_data");
            }
            if (material == null || material.isEmpty()) {
                return null;
            }
            var parsed = net.minecraft.nbt.NbtUtils.readBlockState(
                w.holderLookup(Registries.BLOCK), material);
            if (parsed == null || parsed.isAir() || parsed == own) {
                return null;
            }
            if (DIAGNOSTICS && COPYCAT_DRAWN < 8) {
                COPYCAT_DRAWN++;
                BlueprintExporter.LOGGER.info(
                    "DIAG copycat draw {} at {} -> {} (own model is {})",
                    own.getBlock(), pos, parsed.getBlock(), own.getRenderShape());
            }
            return parsed;
        } catch (Throwable t) {
            if (DIAGNOSTICS) {
                BlueprintExporter.LOGGER.debug("copied material unreadable at {}: {}",
                                               pos, t.toString());
            }
            return null;
        }
    }

    /** A handful of copying blocks, so the log says the copied model was actually drawn. */
    private static int COPYCAT_DRAWN = 0;

    private static void renderLevel(SchematicLevel w,
                                    BlockRenderDispatcher dispatcher,
                                    ModelBlockRenderer renderer,
                                    Collector collector, Matrix4f frame) {
        PoseStack pose = new PoseStack();
        pose.pushPose();
        pose.mulPose(frame);

        RandomSource random = RandomSource.create();
        BoundingBox bounds = w.getBounds();
        w.renderMode = true;

        // The one transform the collector applies, for both the model and the block-entity
        // pass. It is set here rather than left to the pose because the two producers reach
        // the collector by different routes and only agree on this.
        collector.setPoseWithSeparateOffset(frame);

        // Every render type the block renderer knows about, not just the chunk
        // layers: a mod model may register its own RenderType and would otherwise
        // be silently dropped. tesselateBlock itself consults the model, so
        // calling it once per known layer covers them all.

        for (BlockPos local : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                                                     bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            BlockPos pos = local.offset(w.anchor);
            BlockState state = w.getBlockState(pos);
            // A copying block draws the block it copies, not itself.
            //
            // This is what a Copycats camouflage panel is: its own blockstate resolves to
            // `minecraft:block/air` because its appearance comes from a `Material` tag on its block
            // entity, read by the mod. That tag is present in the blueprint and the mod reads it
            // correctly - measured in the log, `material=Block{minecraft:glass}`,
            // `Block{minecraft:chiseled_sandstone}`, `Block{railways:gray_riveted_locometal}` - but
            // nothing renders it, so the panel came out empty with only the missing-texture
            // placeholder where its block's model would be.
            //
            // Doing it here means the copied block's real model and texture are produced by the same
            // code as any other block, so a panel that copies iron bars becomes iron bars.
            BlockState own = state;
            BlockState material = copiedMaterial(w, pos, own);
            if (own.isAir()) {
                continue;
            }
            // Shape first, geometry second. This order is the whole of the "a trapdoor exports as a
            // full cube" fix: a copying block's own model is asked first, and the copied material's
            // model is only used when the block's own model has nothing to say. See the note above
            // renderBlockQuads for why substituting the state unconditionally turns a thin copycat
            // panel into the material's 1x1x1 cube.
            String shapeSource;
            BlockState draw = own;
            BakedModel model = null;
            try {
                if (own.getRenderShape() == RenderShape.MODEL) {
                    BakedModel ownModel = dispatcher.getBlockModel(own);
                    if (ownModel != null && hasQuads(w, pos, own, ownModel, random)) {
                        shapeSource = "baked-quads";
                        model = ownModel;
                    } else if (material != null && !material.isAir()
                            && material.getRenderShape() == RenderShape.MODEL) {
                        BakedModel materialModel = dispatcher.getBlockModel(material);
                        if (materialModel != null && hasQuads(w, pos, material, materialModel, random)) {
                            shapeSource = "copycat-material";
                            draw = material;
                            model = materialModel;
                        } else {
                            shapeSource = "voxelshape";
                        }
                    } else {
                        shapeSource = "voxelshape";
                    }
                } else {
                    // RenderShape is not MODEL: the geometry lives in a renderer and the block entity
                    // pass at the end of this method draws it. Skipped here, exactly as before, so a
                    // chest or a track is never drawn twice - once as a shape box and once by its
                    // own renderer.
                    shapeSource = "entity-renderer";
                }
            } catch (Throwable t) {
                shapeSource = "voxelshape";
            }
            state = draw;
            long seed = state.getSeed(pos);
            random.setSeed(seed);

            pose.pushPose();

            if (DIAGNOSTICS && !POSE_PROBE_LOGGED) {
                POSE_PROBE_LOGGED = true;
                org.joml.Vector3f turned = new org.joml.Vector3f(local.getX(), local.getY(),
                                                                 local.getZ());
                new org.joml.Matrix3f(frame).transform(turned);
                BlueprintExporter.LOGGER.info(
                    "DIAG pose probe: frame.t=({}, {}, {})  local=({}, {}, {})  "
                    + "frame.R*local=({}, {}, {})",
                    frame.m30(), frame.m31(), frame.m32(),
                    local.getX(), local.getY(), local.getZ(),
                    turned.x, turned.y, turned.z);
                // The frame's rotation, row by row. Whether the blocks of this sub-level run
                // along world x or world z is decided entirely by this matrix, and reading it
                // is cheaper than another export per guess.
                org.joml.Matrix3f rot = new org.joml.Matrix3f(frame);
                BlueprintExporter.LOGGER.info(
                    "DIAG frame rotation: [{}, {}, {}] [{}, {}, {}] [{}, {}, {}]",
                    rot.m00(), rot.m01(), rot.m02(),
                    rot.m10(), rot.m11(), rot.m12(),
                    rot.m20(), rot.m21(), rot.m22());
                // ...and where the first two blocks of this sub-level actually land.
                org.joml.Vector3f a = new org.joml.Vector3f(0f, 0f, 0f);
                org.joml.Vector3f b = new org.joml.Vector3f(1f, 0f, 0f);
                new org.joml.Matrix3f(frame).transform(a);
                new org.joml.Matrix3f(frame).transform(b);
                BlueprintExporter.LOGGER.info(
                    "DIAG axis check: frame.R*(0,0,0)=({}, {}, {})  frame.R*(1,0,0)=({}, {}, {})",
                    a.x, a.y, a.z, b.x, b.y, b.z);
            }

            // The block's position in its sub-level is added by the collector rather than
            // applied as a pose. putBulkData transforms each vertex by the quad's own
            // pose, which for a block model is the model's orientation and carries no
            // position at all - so without this every block in the sub-level lands on
            // top of the others, and the coincident faces are what produced the spikes.
            // The block's position in sub-level axes, plus the sub-level's own rotation.
            //
            // The pose does not carry this rotation: measured, an eleven block skirt whose
            // blocks run along local x came out running along world x, at the frame's
            // translation, which is where a single block would be. The frame's rotation has
            // to be applied to the offset before the pose transforms it, which is what the
            // collector now does.
            collector.setBlockOffset(local.getX(), local.getY(), local.getZ(), frame);
            // Drop anything a previous block left half-accumulated. The three vertex
            // lists have to stay in step, and a block whose rendering threw part way
            // through would otherwise leak its vertices into the next block's first face.
            collector.resetQuad();

            if (DIAGNOSTICS && PARTIAL_LOGGED < 16) {
                // Reported for PARTIAL blocks, whatever source won, because the report being chased
                // is "a trapdoor came out as a full cube": what it was drawn from and what its own
                // shape is have to appear together on one line.
                var ownShape = own.getShape(w, pos);
                boolean fullCube = false;
                if (!ownShape.isEmpty()) {
                    var b = ownShape.bounds();
                    fullCube = b.minX <= 1e-4 && b.minY <= 1e-4 && b.minZ <= 1e-4
                        && b.maxX >= 1.0 - 1e-4 && b.maxY >= 1.0 - 1e-4 && b.maxZ >= 1.0 - 1e-4
                        && ownShape.toAabbs().size() == 1;
                }
                if (!fullCube) {
                    PARTIAL_LOGGED++;
                    BlueprintExporter.LOGGER.info(
                        "DIAG shape-src={} block={} props={} shape={} boxes={} faces-before={}",
                        shapeSource, own.getBlock(), own.getProperties(),
                        ownShape.isEmpty() ? "empty" : ownShape.bounds().toString(),
                        ownShape.isEmpty() ? 0 : ownShape.toAabbs().size(),
                        collector.faces.size());
                }
            }
            if (model == null) {
                if ("voxelshape".equals(shapeSource)) {
                    // A MODEL block whose model has no quads and whose material has none either:
                    // emit its own shape. A trapdoor's shape is a thin slab, so this path cannot
                    // produce a 1x1x1 box for a partial block.
                    emitShape(collector, pose, w, pos, own);
                }
                pose.popPose();
                continue;
            }
            try {
                renderBlockQuads(w, model, state, pos, pose, collector, random);
            } catch (Throwable t) {
                // one bad mod model must not kill the whole export
                BlueprintExporter.LOGGER.debug("render failed at {}: {}", pos, t.toString());
            }
            pose.popPose();
        }

        // The other half of the geometry: blocks whose shape lives in a renderer rather than
        // in a model. Run after the model pass so a block that draws both - a model plus a
        // rendered overlay - contributes both.
        try {
            renderBlockEntities(w, pose, collector, frame, collector.subLevel());
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("block entity pass failed: {}", t.toString());
        }

        w.renderMode = false;
        pose.popPose();
    }

    /** Material -> the block ids that contributed to it, for the material line. */
    private static final java.util.Map<String, java.util.LinkedHashSet<String>> MATERIAL_BLOCKS =
        new java.util.LinkedHashMap<>();

    /** Remembers which block a material belongs to, for the diagnostics. */
    private static void noteMaterialBlock(Collector collector, int fromFace, BlockState state) {
        try {
            String blockId = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()));
            for (int i = fromFace; i < collector.faces.size(); i++) {
                MATERIAL_BLOCKS
                    .computeIfAbsent(collector.faces.get(i).material,
                        ignored -> new java.util.LinkedHashSet<>())
                    .add(blockId);
            }
        } catch (Throwable ignored) {
            // a diagnostic that fails is not worth a crash
        }
    }

    /** How many partial-block lines the log may carry per export. */
    private static int PARTIAL_LOGGED = 0;

    /** How many block-entity capture lines the log may carry per export. */
    private static int BER_CAPTURE_LOGGED = 0;

    /** Block id -> the renderer that could not be captured, written beside the mesh. */
    private static final java.util.Map<String, String> UNSUPPORTED_RENDERERS =
        new java.util.LinkedHashMap<>();

    /**
     * Whether a model has any quad at all for this state, with this block entity's ModelData.
     *
     * <p>The question the priority ladder asks. It has to ask the model the same way the render does
     * - the NeoForge extension with ModelData and a render type - because a Copycats panel's own
     * model is an air stub whose quads only exist once its material is in the ModelData.</p>
     */
    private static boolean hasQuads(SchematicLevel w, BlockPos pos, BlockState state,
                                    BakedModel model, RandomSource random) {
        try {
            var ext = (net.neoforged.neoforge.client.extensions.IBakedModelExtension) model;
            net.neoforged.neoforge.client.model.data.ModelData modelData =
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
            BlockEntity be = w.getBlockEntity(pos);
            if (be != null) {
                try {
                    modelData = be.getModelData();
                } catch (Throwable ignored) {
                    // a block entity that cannot describe itself contributes nothing
                }
            }
            modelData = ext.getModelData(w, pos, state, modelData);
            for (RenderType layer : ext.getRenderTypes(state, random, modelData)) {
                for (Direction d : Direction.values()) {
                    if (!ext.getQuads(state, d, random, modelData, layer).isEmpty()) {
                        return true;
                    }
                }
                if (!ext.getQuads(state, null, random, modelData, layer).isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // an unreadable model is treated as empty, which sends the block to its shape
        }
        return false;
    }

    /**
     * Emit a block's own {@code VoxelShape} as quads: one box per {@code AABB}.
     *
     * <p>This is the fallback that keeps a partial block partial. The previous code had no such
     * path, so a block whose model could not be read either vanished or - through the copied-material
     * substitution - came out as the material's full cube. A trapdoor's shape is a thin slab and a
     * fence's is a post plus rails, so emitting the shape is what makes "薄板/柱子" possible without
     * a model at all.</p>
     *
     * <p>UVs are the model's particle sprite, mapped once across each face: the shape carries no
     * texture information, and a flat-coloured panel is a better answer than a cube.</p>
     */
    private static void emitShape(Collector collector, PoseStack pose, SchematicLevel w, BlockPos pos,
                                  BlockState state) {
        try {
            var shape = state.getShape(w, pos);
            if (shape == null || shape.isEmpty()) {
                return;
            }
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            TextureAtlasSprite sprite = model == null ? null
                : model.getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
            if (sprite == null) {
                return;
            }
            collector.setSprite(sprite);
            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();
            for (var box : shape.toAabbs()) {
                emitShapeBox(collector, pose, sprite, box, u0, u1, v0, v1);
            }
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.debug("shape fallback failed at {}: {}", pos, t.toString());
        }
    }

    /** One AABB as six quads, wound counter-clockwise seen from outside. */
    private static void emitShapeBox(Collector collector, PoseStack pose, TextureAtlasSprite sprite,
                                     net.minecraft.world.phys.AABB box,
                                     float u0, float u1, float v0, float v1) {
        float x0 = (float) box.minX;
        float y0 = (float) box.minY;
        float z0 = (float) box.minZ;
        float x1 = (float) box.maxX;
        float y1 = (float) box.maxY;
        float z1 = (float) box.maxZ;
        emitShapeQuad(collector, pose, sprite, u0, u1, v0, v1,
            x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0);
        emitShapeQuad(collector, pose, sprite, u0, u1, v0, v1,
            x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0);
        emitShapeQuad(collector, pose, sprite, u0, u1, v0, v1,
            x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0);
        emitShapeQuad(collector, pose, sprite, u0, u1, v0, v1,
            x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);
        emitShapeQuad(collector, pose, sprite, u0, u1, v0, v1,
            x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1);
        emitShapeQuad(collector, pose, sprite, u0, u1, v0, v1,
            x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);
    }

    /** One quad of a shape box: the four corners, the sprite rect, and the packed normal. */
    private static void emitShapeQuad(Collector collector, PoseStack pose, TextureAtlasSprite sprite,
                                      float u0, float u1, float v0, float v1,
                                      float ax, float ay, float az, float bx, float by, float bz,
                                      float cx, float cy, float cz, float dx, float dy, float dz,
                                      float nx, float ny, float nz) {
        int[] vertices = new int[4 * 8];
        float[] xs = {ax, bx, cx, dx};
        float[] ys = {ay, by, cy, dy};
        float[] zs = {az, bz, cz, dz};
        float[] us = {u0, u1, u1, u0};
        float[] vs = {v1, v1, v0, v0};
        int packed = ((int) (nx * 127) & 0xFF)
            | (((int) (ny * 127) & 0xFF) << 8)
            | (((int) (nz * 127) & 0xFF) << 16);
        int light = 0x00F000F0;
        for (int corner = 0; corner < 4; corner++) {
            int base = corner * 8;
            vertices[base] = Float.floatToRawIntBits(xs[corner]);
            vertices[base + 1] = Float.floatToRawIntBits(ys[corner]);
            vertices[base + 2] = Float.floatToRawIntBits(zs[corner]);
            vertices[base + 3] = 0xFFFFFFFF;
            vertices[base + 4] = Float.floatToRawIntBits(us[corner]);
            vertices[base + 5] = Float.floatToRawIntBits(vs[corner]);
            vertices[base + 6] = light;
            vertices[base + 7] = packed;
        }
        net.minecraft.client.renderer.block.model.BakedQuad quad =
            new net.minecraft.client.renderer.block.model.BakedQuad(vertices, -1, null, sprite, true);
        collector.setUvNeedsAtlasConversion(true);
        emitQuad(collector, pose, quad);
    }

    /**
     * Emit one block's quads, resolving them WITH the block entity's {@code ModelData}.
     *
     * <p>This is the whole reason the exporter does not simply call
     * {@code ModelBlockRenderer.tesselateBlock}. That method ends up asking the model
     * for {@code getQuads(state, dir, random)} - the overload with no {@code ModelData}
     * - so a model whose shape is parameterised by block-entity data renders as its
     * unparameterised default. Copycats+ is entirely that: its blockstate is an
     * {@code minecraft:block/air} stub and the real shape comes from the block entity's
     * {@code material_data}, delivered through NeoForge's {@code getModelData} and
     * {@code getQuads(..., ModelData, RenderType)}.
     *
     * <p>183 of pgz-09's 276 blocks are Copycats+ family, so going through
     * tesselateBlock lost most of the vehicle - 4998 triangles against the add-on's
     * 17780, with the panels, slabs and half-panels drawn as bare defaults.
     */
    private static void renderBlockQuads(SchematicLevel w, BakedModel model,
                                         BlockState state, BlockPos pos, PoseStack pose,
                                         Collector collector, RandomSource random) {
        var ext = (net.neoforged.neoforge.client.extensions.IBakedModelExtension) model;

        // What the block entity contributes, then what the model makes of it.
        net.neoforged.neoforge.client.model.data.ModelData modelData =
            net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
        BlockEntity be = w.getBlockEntity(pos);
        if (be != null) {
            try {
                modelData = be.getModelData();
            } catch (Throwable ignored) {
                // a block entity that cannot describe itself contributes nothing
            }
        }
        modelData = ext.getModelData(w, pos, state, modelData);

        collector.setAtlases(spritesOf(model, state, random, modelData));

        // ChunkRenderTypeSet is iterable but is not a java.util.Collection, so it has
        // to be walked rather than copied.
        java.util.List<RenderType> layers = new java.util.ArrayList<>();
        for (RenderType layer : ext.getRenderTypes(state, random, modelData)) {
            layers.add(layer);
        }
        if (layers.isEmpty()) {
            layers.addAll(RenderType.chunkBufferLayers());
        }

        java.util.List<Direction> dirs = new java.util.ArrayList<>();
        for (Direction d : Direction.values()) {
            dirs.add(d);
        }
        dirs.add(null);

        float[] tint = {1.0f, 1.0f, 1.0f, 1.0f};
        int[] light = {0, 0};
        // The block's own sheet, which is the authority for a block model: a glass pane is cutout and
        // a sheet of glass is translucent because `ItemBlockRenderTypes` says so, not because of which
        // render types this particular baked model reported. Asked first, then the model's own layers
        // refine it (a mod model may draw several of them).
        try {
            hintRenderType(ItemBlockRenderTypes.getRenderType(state, false));
        } catch (Throwable ignored) {
            // a block whose sheet cannot be resolved keeps whatever the model's layers say
        }
        for (Direction dir : dirs) {
            for (RenderType layer : layers) {
                // The render type of the quads about to be emitted decides the material's layer:
                // plain glass is drawn on the translucent sheet even though its texture is cutout.
                hintRenderType(layer);
                java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads;
                try {
                    quads = ext.getQuads(state, dir, random, modelData, layer);
                } catch (Throwable t) {
                    continue;
                }
                if (quads == null) {
                    continue;
                }
                for (net.minecraft.client.renderer.block.model.BakedQuad quad : quads) {
                    collector.setSprite(quad.getSprite());
                    emitQuad(collector, pose, quad);
                }
            }
        }
    }

    /**
     * Bake the geometry that block-entity renderers draw.
     *
     * <p>A block whose {@code RenderShape} is not {@code MODEL} has no model to read: its
     * shape exists only inside a {@code BlockEntityRenderer}, which is handed a
     * {@code PoseStack} and a {@code MultiBufferSource} and writes vertices into whatever
     * consumer the source returns. That is 139 of the 1246 blocks in a typical pack - every
     * Create belt, nixie tube and valve handle - and it is also where the parts that are not
     * blocks live: cannon barrels and tank tracks.
     *
     * <p>So the pipeline is handed a source whose consumer is this collector. The renderer
     * does the work, and there is no need to know how any individual block draws itself.
     *
     * <p>The pose carries the sub-level frame and nothing else. A block-entity renderer writes
     * positions relative to the block it is drawing, and {@code putBulkData} applies whatever
     * pose is current, so the frame is exactly the missing piece; adding the block offset too
     * would double it, the mistake that took several attempts on the model path.
     *
     * <p>Nothing here applies a pose itself: the vertices arrive already in the frame's axes,
     * the same arrangement {@code renderBlockQuads} uses.
     */
    private static int renderBlockEntities(SchematicLevel w, PoseStack pose, Collector collector,
                                           Matrix4f frame, int subLevel) {
        return renderBlockEntities(w, pose, collector, frame, subLevel, BlockPos.ZERO, true);
    }

    /**
     * The same pass, for a caller whose block positions are not small numbers.
     *
     * <p>{@code base} is subtracted from every block position before it becomes a matrix, and the
     * caller folds the same {@code base} into its frame - together that is exactly the untouched
     * arrangement. It exists because a sub-level stores its blocks around twenty million, and a
     * {@code float} cannot hold that exactly, so the offset has to be made small before it is
     * rotated. The blueprint path passes {@link BlockPos#ZERO} and behaves as it always did.
     *
     * <p>{@code affinity} switches off the block-affinity measurement, which is quadratic in
     * "vertices times blocks" and would walk a whole 64-cube of blocks per vertex for a region
     * export. It stays on for a blueprint, where the sub-level is a few hundred blocks.
     */
    private static int renderBlockEntities(SchematicLevel w, PoseStack pose, Collector collector,
                                           Matrix4f frame, int subLevel, BlockPos base,
                                           boolean affinity) {
        return renderBlockEntities(w, pose, collector, frame, subLevel, base, affinity, null);
    }

    /**
     * The same pass, plus the per-block-entity diagnostics a region export writes.
     *
     * @param regionTally the region export's counters, or null for the blueprint path. When it is
     *                    given, this pass also turns Flywheel's "the visual engine draws this"
     *                    answer off for the duration of each renderer call (see
     *                    {@link FlywheelGate}) and writes one {@code region-ber:} line per block
     *                    entity, including the pose error of the geometry it drew.
     */
    private static int renderBlockEntities(SchematicLevel w, PoseStack pose, Collector collector,
                                           Matrix4f frame, int subLevel, BlockPos base,
                                           boolean affinity, RegionTally regionTally) {
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        int before = collector.faces.size();
        int drawn = 0;
        int skipped = 0;
        int excluded = 0;
        java.util.List<String> probes = new java.util.ArrayList<>();
        // Every placed block-entity vertex, for the distance measurement below.
        java.util.List<float[]> placedVertices = new java.util.ArrayList<>();

        for (BlockEntity be : w.getBlockEntities()) {
            // Some renderers produce geometry that is deliberately left out.
            //
            // A block whose shape is drawn by code rather than described by a model - a track
            // link, a tyre, a cannon barrel - comes through here as a large number of small
            // textured pieces that share the block atlas. The atlas cannot be copied out of the
            // GPU, so the pieces arrive untextured, and there are hundreds of them, which makes
            // them useless to adjust by hand. They are left out so that the rest of the model
            // is clean; the parts themselves are modelled separately.
            if (excludedFromExport(be)) {
                excluded++;
                // Named, because an export that is missing a part because a config file said so
                // looks exactly like one that lost it to a bug.
                String excludedId = blockIdOf(be.getBlockState());
                if (regionTally != null
                        && (regionTally.firstBerKind(excludedId) || regionTally.wantBerLine())) {
                    BlueprintExporter.LOGGER.info(
                        "region-ber: id={} ber=- ok=false quads=0 reason=excluded-by-config",
                        excludedId);
                }
                continue;
            }
            var renderer = dispatcher.getRenderer(be);
            if (renderer == null) {
                // A block entity with no renderer draws no geometry, whatever its model says.
                // Named here because it is the first of the three ways a part goes missing.
                String unrenderedId = blockIdOf(be.getBlockState());
                if (regionTally != null
                        && (regionTally.firstBerKind(unrenderedId) || regionTally.wantBerLine())) {
                    BlueprintExporter.LOGGER.info(
                        "region-ber: id={} ber=- ok=false quads=0 reason=no-renderer",
                        unrenderedId);
                }
                continue;
            }
            BlockPos pos = be.getBlockPos();
            // A pose containing only this block's position. The shared `pose` already carries
            // `frame`, so using it would place the geometry twice; the collector is what adds
            // the frame, exactly as it does for a model's faces.
            //
            // The renderer writes relative to its own block and then follows whatever pose it
            // is handed, so this is the missing half for it: with both, a block-entity vertex
            // comes out as `frame . (blockPos + p)`, the same expression the model path
            // produces for a quad's corner.
            // Two transforms are in play and only one may be applied by each side.
            //
            // A renderer reads the pose it is handed and folds it into its vertices, and the
            // collector's `place` multiplies by the matrix set here. If both carry the block's
            // position, it is counted twice - which is what the measurements showed: a
            // sub-level's blocks span four units vertically while the exported parts span
            // twenty-one, a factor no rotation can produce because the frame's rows are unit
            // length and its determinant is one.
            //
            // So they are made disjoint. The renderer is given a pose carrying only its own
            // orientation, and the collector is given the whole transform - sub-level frame,
            // block position and all. A block-entity vertex then comes out as
            // `frame . (blockPos + p)` exactly as a model's face does.
            // Who the vertices written below belong to. Every face recorded between here and
            // `CaptureScope.end()` is stamped with this block, which is what a later map from
            // OBJ group to block id needs; nothing about the export itself changes.
            CaptureScope.beginBlock(pos);
            PoseStack local = new PoseStack();
            collector.setPoseWithOffset(frame, translationOf(pos.subtract(base)));
            BlockEntityCapture capture = new BlockEntityCapture(collector);
            // A renderer's UVs are already in its own sprite's space: see toSpriteSpace.
            collector.setUvNeedsAtlasConversion(false);
            capture.currentBlockLabel = blockEntityMaterialName(be, null);
            capture.setBlock(be.getBlockState());
            int facesBefore = collector.faces.size();
            // The raw coordinates the renderer writes, before any transform is applied.
            //
            // This is the measurement that separates two explanations for geometry landing away
            // from its block: either the renderer writes block-local coordinates and the frame
            // must turn them into world ones, or it writes coordinates that are already placed
            // and the frame must not be applied at all. Reading the consumer's own raw range
            // says which, without another guess - the transform has already been got wrong
            // several times by reasoning about it.
            collector.beginRawProbe();
            boolean ok = false;
            // The gate that decides whether a mechanical part reaches the file at all.
            //
            // Flywheel answers "this world draws its block entities with the visual engine", and
            // a renderer that asks then returns without writing a vertex: CBC's cannon mount has
            // its entire body inside `if (!VisualizationManager.supportsVisualization(level))`,
            // and Create's encased cogwheel is the same. The game does draw those parts - through
            // a visual, which uploads instances to the GPU and emits no vertices anywhere - so an
            // export that only asks the renderer gets nothing, which is exactly the "no barrel, no
            // cogwheel" report. Turning the answer off for the length of one call makes the
            // renderer take the path a client without Flywheel takes: the one that writes into the
            // VertexConsumer this pass hands it. See FlywheelGate for why this is safe.
            boolean gated = regionTally != null && FlywheelGate.engage();
            // A track renderer's belt is named nowhere but in the mod's own code, so the renderer
            // is classified by name and the belt sprite is handed to the collector for the
            // length of this call. See BeltSprite; the classification is name-based because the
            // class itself is not on this mod's compile path, and must not be.
            String rendererName = renderer.getClass().getSimpleName();
            boolean trackRenderer = isTrackRenderer(rendererName)
                || isTrackRenderer(renderer.getClass().getName());
            collector.setTrackHint(trackRenderer, BeltSprite.texture());
            collector.resetBeltQuads();
            try {
                // `local`, not the shared `pose`: the renderer follows the pose it is handed,
                // and the collector adds the frame. Handing it the shared pose - which already
                // contains the frame - put block-entity geometry through the frame twice, which
                // is why it rendered in the wrong place while the model faces were right.
                local.pushPose();
                // Full brightness rather than 0: a renderer that multiplies its colour by the
                // packed light comes out black at 0, and the export carries no lighting anyway.
                //
                // The partial tick is the frame's own rather than 0. A renderer that interpolates
                // - a cannon's pitch, a track's belt scroll, a cogwheel's angle - reads it, and
                // handing it 0 asks for the state the part had at the tick boundary instead of
                // the one on screen. `OverlayTexture.NO_OVERLAY` rather than 0: 0 is a real
                // overlay value (a hurt flash), not "none".
                renderer.render(be, regionTally == null ? 0.0f : framePartialTick(),
                                local, capture,
                                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                local.popPose();
                ok = true;
                // Back to the model convention, so a quad emitted afterwards is not left in
                // atlas coordinates because a renderer happened to run before it.
                collector.setUvNeedsAtlasConversion(true);
                drawn++;
                if (regionTally == null && BER_CAPTURE_LOGGED < 20) {
                    BER_CAPTURE_LOGGED++;
                    var blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(be.getBlockState().getBlock());
                    BlueprintExporter.LOGGER.info(
                        "ber-capture: block={} ber={} ok={} quads={} renderType={}",
                        blockId, renderer.getClass().getSimpleName(), ok,
                        collector.faces.size() - facesBefore,
                        capture.usedRenderTypes.isEmpty() ? "-"
                            : String.join("|", capture.usedRenderTypes));
                }
                if (DIAGNOSTICS && probes.size() < 8) {
                    // The block id, not the block-entity type. `BlockEntityType.toString` prints
                    // an identity hash, which says nothing about which block was drawn - and
                    // the block is what has to be named to leave a renderer out of the export.
                    var blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(be.getBlockState().getBlock());
                    probes.add(String.format(java.util.Locale.ROOT,
                        "%s faces=%d %s", blockId, collector.faces.size() - facesBefore,
                        collector.pinnedUvRange()));
                }
            } catch (Throwable t) {
                // A renderer that needs something the schematic cannot provide - a level
                // that ticks, a player, a rendered entity - must not take the export down, but it
                // must not vanish without a trace either: the block keeps its own model shell and
                // is named here and in the export's notes.
                skipped++;
                String failedId = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(be.getBlockState().getBlock()));
                UNSUPPORTED_RENDERERS.put(failedId, renderer.getClass().getName()
                    + ": " + t.getClass().getSimpleName());
                BlueprintExporter.NOTES.add("renderer failed for " + failedId + " ("
                    + renderer.getClass().getSimpleName() + "): " + t.getMessage()
                    + " - the block is exported as its own model only");
                // The exception itself, not just "it failed". This is the one line that answers
                // "why is the barrel not in the file" when the answer is a missing level, a null
                // cannon or a renderer that wanted a player.
                if (regionTally != null && regionTally.wantBerLine()) {
                    BlueprintExporter.LOGGER.info(
                        "region-ber: id={} ber={} ok=false quads={} reason={}: {}",
                        failedId, renderer.getClass().getSimpleName(),
                        collector.faces.size() - facesBefore,
                        t.getClass().getSimpleName(),
                        t.getMessage() == null ? "-" : t.getMessage());
                }
                if (DIAGNOSTICS) {
                    BlueprintExporter.LOGGER.warn(
                        "block entity {} at {} did not render: {}",
                        be.getType(), pos, t.toString());
                }
            } finally {
                // Released before anything else: the visual engine's own answer has to be true
                // again for the rest of the frame, whatever the renderer did.
                FlywheelGate.release(gated);
                // Closes the scope opened above and writes the block's `capture-scope:` line.
                // In a finally so a renderer that threw still releases the scope: a leaked one
                // would file the next block's faces under this block.
                CaptureScope.end();
                collector.unpinMaterial();
            }
            // The belt gets its own line when a track renderer produced any, because the line
            // below reports the last group the renderer wrote and a track mount draws its wheels
            // after its links. This is the line that answers "did the belt reach the file, and
            // under which image" without depending on draw order.
            int beltQuads = collector.beltQuads();
            if (beltQuads > 0 && regionTally != null) {
                BlueprintExporter.LOGGER.info(
                    "region-ber: id={} ber={} sprite={} tex={} belt-quads={}",
                    blockIdOf(be.getBlockState()), renderer.getClass().getSimpleName(),
                    BeltSprite.texture(), BeltSprite.texture(), beltQuads);
            }
            // The one line that says whether a part of this kind reaches the file and where it
            // lands. `pose-delta` is the centre of the geometry the renderer wrote against the
            // centre of the block's own box, both in export coordinates: a rotated part reads
            // zero, a part drawn around the wrong origin reads the distance it was displaced by.
            String berId = blockIdOf(be.getBlockState());
            boolean berFirst = regionTally != null && regionTally.firstBerKind(berId);
            if (regionTally != null && (berFirst || regionTally.wantBerLine())) {
                float[] centre = collector.placedCentre();
                org.joml.Vector3f expected = new org.joml.Vector3f(
                    pos.getX() - base.getX() + 0.5f,
                    pos.getY() - base.getY() + 0.5f,
                    pos.getZ() - base.getZ() + 0.5f);
                new Matrix4f(frame).transformPosition(expected);
                String delta = centre == null ? "-" : String.format(java.util.Locale.ROOT,
                    "(%.2f,%.2f,%.2f)", centre[0] - expected.x, centre[1] - expected.y,
                    centre[2] - expected.z);
                BlueprintExporter.LOGGER.info(
                    "region-ber: id={} ber={} ok={} quads={} renderTypes=[{}] pose-delta={}"
                        + " sprite={} uv-space={} tex={} flywheel={}",
                    blockIdOf(be.getBlockState()), renderer.getClass().getSimpleName(), ok,
                    collector.faces.size() - facesBefore, compactRenderTypes(capture),
                    delta, collector.resolvedTile() == null ? "none" : collector.resolvedTile(),
                    collector.uvSpace(), collector.materialTexture(), gated ? "gated" : "on");
            }
            if (DIAGNOSTICS && drawn <= 5) {
                // The extent of THIS block's geometry, before and after placement.
                //
                // This is the measurement that localises a scaling: a renderer drawing one
                // block writes a few units, so a per-block extent of twenty says the scale is
                // introduced here; a per-block extent of two says it is not, and the spread
                // comes from where the blocks are put.
                String raw = collector.rawRange();
                String placed = collector.placedRange();
                if (raw != null) {
                    BlueprintExporter.LOGGER.info(
                        "DIAG span sub{} {} BLOCKPOS=({}, {}, {}) raw={} placed={}",
                        subLevel, be.getBlockState().getBlock(), pos.getX(), pos.getY(),
                        pos.getZ(), raw, placed);
                }
            }
            // Keep the placed vertices of this block for the affinity measurement.
            collector.drainPlacedVertices(placedVertices);
        }

        if (DIAGNOSTICS && (drawn > 0 || skipped > 0 || excluded > 0)) {
            BlueprintExporter.LOGGER.info(
                "DIAG sub{} block entities: {} rendered, {} skipped, {} excluded, {} face(s)",
                subLevel, drawn, skipped, excluded, collector.faces.size() - before);
            // The measurement that decides the transform: how far the geometry landed from the
            // blocks it was drawn for. Printed with the variant in use, so one export can be
            // compared against another without anyone judging a render by eye. Skipped for a
            // region export, where the block set is a whole selection and the measurement is
            // quadratic in "vertices times blocks".
            if (affinity) {
                String affinityReport = blockAffinity(w, placedVertices);
                if (affinityReport != null) {
                    BlueprintExporter.LOGGER.info("DIAG affinity sub{}: {}", subLevel, affinityReport);
                }
            }
            for (String probe : probes) {
                BlueprintExporter.LOGGER.info("DIAG   be {}", probe);
            }
            // Where the block entities sit against where the model's blocks sit.
            //
            // These come from different sources: the model pass walks the sub-level's bounds and
            // offsets by `anchor`, while the block-entity pass asks each entity for its own
            // position. If those two disagree - one carrying the anchor and the other not - every
            // rendered part is displaced by the difference, which is what "the tracks float away
            // from the hull" looks like. Printing both ranges settles it without another guess.
            // `getBlockEntities` returns an Iterable rather than a Collection, so emptiness is
            // tested by iterating rather than by calling isEmpty.
            {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                boolean any = false;
                for (BlockEntity entity : w.getBlockEntities()) {
                    BlockPos p = entity.getBlockPos();
                    any = true;
                    minX = Math.min(minX, p.getX());
                    minY = Math.min(minY, p.getY());
                    minZ = Math.min(minZ, p.getZ());
                    maxX = Math.max(maxX, p.getX());
                    maxY = Math.max(maxY, p.getY());
                    maxZ = Math.max(maxZ, p.getZ());
                }
                BoundingBox bounds = w.getBounds();
                if (any) {
                    BlueprintExporter.LOGGER.info(
                        "DIAG sub{} anchor=({}, {}, {})  sub-level bounds x[{}..{}] y[{}..{}] "
                        + "z[{}..{}]  block-entity positions x[{}..{}] y[{}..{}] z[{}..{}]",
                        subLevel, w.anchor.getX(), w.anchor.getY(), w.anchor.getZ(),
                        bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY(),
                        bounds.minZ(), bounds.maxZ(),
                        minX, maxX, minY, maxY, minZ, maxZ);
                } else {
                    BlueprintExporter.LOGGER.info(
                        "DIAG sub{} anchor=({}, {}, {})  sub-level bounds x[{}..{}] y[{}..{}] "
                        + "z[{}..{}]  no block entities",
                        subLevel, w.anchor.getX(), w.anchor.getY(), w.anchor.getZ(),
                        bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY(),
                        bounds.minZ(), bounds.maxZ());
                }
            }
        }
        return drawn;
    }

    // ---------------------------------------------------------------- world region export

    /**
     * The second way in: the blocks and entities inside a box the player selected in the world.
     *
     * <p>The blueprint reader is a reimplementation of somebody else's save format, and every part
     * that save format does not carry - a caterpillar track, a wheel, a sub-level's own assembly -
     * has to be guessed at afterwards. A region export has no format in between: the blocks it
     * draws are the blocks that are there, with the block states and block-entity instances the
     * world itself is holding. So everything downstream - models, {@code ModelData}, block-entity
     * renderers, the sprite and alpha classification, the writer - is the same code the blueprint
     * path uses; only where the blocks come from differs.
     *
     * <p>The box is the selection the Sable blueprint tool already shows (see
     * {@link RegionSelection}), in world coordinates, so a structure the player is standing on is
     * selected where it is seen rather than where Sable stores it.
     */
    public static int bakeRegion(String name, net.minecraft.world.phys.AABB box, String selectionSource)
            throws IOException {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            throw new IOException("no client: the bake needs baked models");
        }
        if (client.isSameThread()) {
            return bakeRegionOnClientThread(client, name, box, selectionSource);
        }
        // Same guarantee the blueprint entry point gives: baked models and block-entity renderers
        // exist on the client thread only, so a call from anywhere else is moved there and waited
        // for, with the wait reported.
        final int[] result = new int[1];
        final Throwable[] failure = new Throwable[1];
        final java.util.concurrent.CompletableFuture<Void> done =
            new java.util.concurrent.CompletableFuture<>();
        long started = System.nanoTime();
        client.execute(() -> {
            try {
                result[0] = bakeRegionOnClientThread(client, name, box, selectionSource);
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.complete(null);
            }
        });
        try {
            done.get(300, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception timeout) {
            failure[0] = timeout;
        }
        BlueprintExporter.LOGGER.info("region export: offloaded=true wait_ms={} name={} failed={}",
            (System.nanoTime() - started) / 1_000_000L, name, failure[0] != null);
        if (failure[0] instanceof IOException io) {
            throw io;
        }
        if (failure[0] != null) {
            throw new IOException(failure[0]);
        }
        return result[0];
    }

    /** Blocks a region may cover, from {@code regionMaxBlocks=} in the pod config, else 64^3. */
    public static int regionMaxBlocks() {
        String value = podSetting("regionmaxblocks");
        if (value != null && !value.isEmpty()) {
            try {
                // A tiny limit would make the feature unusable by an accidental typo, so the
                // floor is one chunk's worth in each direction rather than the parse result.
                return Math.max(4096, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                // a value that is not a number falls back to the default rather than failing
            }
        }
        return REGION_MAX_BLOCKS;
    }

    /**
     * TEMP-DISABLED(blueprint-export): the master switch for the saved-blueprint path.
     *
     * <p>{@code blueprintExportEnabled} in {@code config/firecontrolcompat-pod.txt}, default
     * {@code false}. The {@code .nbt} reader is a reimplementation of somebody else's save format,
     * and every part that format does not carry - caterpillar tracks, bearings, a sub-level's own
     * assembly, textures - has to be guessed at afterwards, which is exactly where that path came
     * out wrong. {@code /sableexport region} reads the live world instead, so it is the path the
     * player is pointed at while this is off.
     *
     * <p>Only the entry points are gated ({@code BlueprintExporter#run} for the command and the two
     * export pages for the buttons). {@link #bake} and {@link #bakeOne} are untouched, so writing
     * {@code blueprintExportEnabled=true} brings the old behaviour back without a rebuild.
     */
    public static boolean blueprintExportEnabled() {
        String value = podSetting("blueprintexportenabled");
        if (value == null || value.isEmpty()) {
            // Absent, empty or unreadable config all mean "off": that is the shipped default.
            return false;
        }
        return !("false".equals(value) || "no".equals(value) || "off".equals(value)
            || "0".equals(value));
    }

    /** The region bake itself; always called on the client thread. */
    private static int bakeRegionOnClientThread(Minecraft mc, String name,
                                                net.minecraft.world.phys.AABB box,
                                                String selectionSource) throws IOException {
        Level level = mc.level;
        if (level == null) {
            throw new IOException("join a world first");
        }
        if (box == null) {
            throw new IOException("no region selected");
        }
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = Math.max(minX, (int) Math.ceil(box.maxX) - 1);
        int maxY = Math.max(minY, (int) Math.ceil(box.maxY) - 1);
        int maxZ = Math.max(minZ, (int) Math.ceil(box.maxZ) - 1);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        int limit = regionMaxBlocks();
        if (volume > limit) {
            throw new IOException("region too large: " + volume + " blocks, limit " + limit);
        }

        // Everything is written with the selection's own corner as the origin. A world-coordinate
        // mesh a few thousand blocks from the origin is unusable in Blender - the camera starts
        // inside nothing and the numbers lose precision - while the blueprint path has always
        // written near the origin.
        Vec3 origin = new Vec3(minX, minY, minZ);
        RegionTally tally = new RegionTally();
        java.util.List<RegionSpace> spaces = new java.util.ArrayList<>();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        Collector collector = new Collector();
        CaptureScope.reset();
        // The cannon probe's budgets are per export, not per session: a player who exports twice
        // has to see the same lines both times, and a fleet of eighty cannons has to be capped
        // within one file rather than across the run.
        CANNON_TEX_LOGGED = 0;
        CANNON_SAVED_LOGGED = 0;

        // 1) The world itself: ordinary blocks at ordinary coordinates. Origin and base are the
        // same corner, so its frame is the identity and the mesh starts at (0, 0, 0).
        RegionSpace root = new RegionSpace("world", 0, new Matrix4f(), new org.joml.Matrix4d(),
            new float[] {0f, 0f, 0f},
            new BlockPos(minX, minY, minZ), origin, new org.joml.Matrix4d(), null);
        scanRegionBlocks(level, root, minX, minY, minZ, maxX, maxY, maxZ, tally);
        spaces.add(root);

        // 2) Every sub-level the box touches. Its blocks live in the plot's storage region, up to
        // twenty million blocks away, so each one gets its own coordinate space: the box is mapped
        // into that space to find which blocks to read, and each block's world position decides
        // whether it is really inside the selection.
        scanRegionSubLevels(level, box, origin, spaces, tally);

        int blockEntities = 0;
        java.util.List<RegionPending> pending = new java.util.ArrayList<>();
        for (RegionSpace space : spaces) {
            blockEntities += space.blockEntityList.size();
            PoseStack pose = new PoseStack();
            pose.pushPose();
            pose.mulPose(space.frame);
            collector.setSubLevel(space.index);
            collector.setPoseWithSeparateOffset(space.frame);
            RegionLevel w = new RegionLevel(level, space);
            w.renderMode = true;
            BlueprintExporter.LOGGER.info(
                "region: space index={} name={} blocks={} block-entities={} frame-t=({}, {}, {})",
                space.index, space.label, space.states.size(), space.blockEntityList.size(),
                String.format(java.util.Locale.ROOT, "%.2f", space.frame.m30()),
                String.format(java.util.Locale.ROOT, "%.2f", space.frame.m31()),
                String.format(java.util.Locale.ROOT, "%.2f", space.frame.m32()));
            // The reference box: where this space's own blocks say their geometry belongs,
            // computed in double. Printed before anything is drawn, so the log says what the
            // space should occupy even when the geometry then fails to appear in it.
            float[] blockBox = null;
            try {
                blockBox = space.blockBounds();
                BlueprintExporter.LOGGER.info(
                    "region-space-check: space={} name={} frame-t=({}) drift=({}) blocks={}"
                        + " bbox-min=({}) bbox-max=({})",
                    space.index, space.label,
                    triple(space.frame.m30(), space.frame.m31(), space.frame.m32()),
                    triple(space.frameDrift), space.states.size(),
                    triple(blockBox, 0), triple(blockBox, 3));
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.info(
                    "region-space-check: space={} name={} blocks={} frame=unavailable ({})",
                    space.index, space.label, space.states.size(), t.getClass().getSimpleName());
            }
            int faceStart = collector.faces.size();
            renderRegionBlocks(w, space, pose, dispatcher, collector, tally, pending);
            int beBefore = collector.faces.size();
            int beDrawn = 0;
            try {
                beDrawn = renderBlockEntities(w, pose, collector, space.frame, space.index,
                    space.base, false, tally);
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.warn("region: block entity pass failed in {}: {}",
                    space.label, t.toString());
            }
            tally.berDrawn += beDrawn;
            // A block whose geometry lives in its renderer draws nothing in the model pass, so it
            // is only a skip if the renderer drew nothing either. Resolved here, once, because
            // that is the first point in the export where both answers are known.
            tally.resolve(pending, space, beDrawn > 0 || collector.faces.size() > beBefore);
            // ...and where the geometry actually landed. This is the line that settles a
            // misalignment without anyone judging a render by eye: both boxes are in export
            // coordinates, and `delta` is the distance between their centres. A space whose
            // frame translation is a few blocks out reads that error here even though its own
            // parts agree with each other - which is the multi-structure fault exactly.
            try {
                float[] verts = vertexBounds(collector, faceStart);
                if (verts != null && blockBox != null) {
                    float[] delta = {centre(verts, 0) - centre(blockBox, 0),
                                     centre(verts, 1) - centre(blockBox, 1),
                                     centre(verts, 2) - centre(blockBox, 2)};
                    float worst = 0f;
                    for (float value : delta) {
                        worst = Math.max(worst, Math.abs(value));
                    }
                    BlueprintExporter.LOGGER.info(
                        "region-space-check: space={} name={} verts={} verts-bbox=({})..({})"
                            + " block-bbox=({})..({}) delta=({}) verdict={}",
                        space.index, space.label, collector.faces.size() - faceStart,
                        triple(verts, 0), triple(verts, 3),
                        triple(blockBox, 0), triple(blockBox, 3),
                        triple(delta), worst <= SPACE_CHECK_TOLERANCE ? "ok" : "drift");
                } else if (verts != null) {
                    BlueprintExporter.LOGGER.info(
                        "region-space-check: space={} name={} verts={} verts-bbox=({})..({})"
                            + " block-bbox=- verdict=no-reference",
                        space.index, space.label, collector.faces.size() - faceStart,
                        triple(verts, 0), triple(verts, 3));
                } else {
                    BlueprintExporter.LOGGER.info(
                        "region-space-check: space={} name={} verts=0 block-bbox=({})..({})"
                            + " verdict=no-geometry",
                        space.index, space.label, triple(blockBox, 0), triple(blockBox, 3));
                }
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.info(
                    "region-space-check: space={} name={} verts=unavailable ({})",
                    space.index, space.label, t.getClass().getSimpleName());
            }
            w.renderMode = false;
            pose.popPose();
        }

        // 3) Entities last, in their own spaces, straight through the entity renderer. The
        // capture path is an active call here rather than a mixin: nothing has to be armed, and a
        // frame is not needed - the renderers are handed a consumer that records instead of a
        // buffer that draws.
        tally.entities += renderRegionEntities(mc, level, box, origin, spaces, collector);

        // Printed before the geometry is written, so the box and the block count of this export are
        // in the log even when the export then refuses for having nothing in it.
        BlueprintExporter.LOGGER.info(
            "region: selection source={} aabb=({}, {}, {})..({}, {}, {}) volume={} blocks={}",
            selectionSource, minX, minY, minZ, maxX, maxY, maxZ, volume, tally.examined);

        if (collector.faces.isEmpty()) {
            throw new IOException("nothing to export: blocks=" + tally.examined
                + ", block entities=" + blockEntities + ", entities=" + tally.entities
                + ", selection=" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ);
        }

        int written = write(collector, name);
        CaptureScope.summarise();
        BlueprintExporter.LOGGER.info(
            "region: baked blocks={} examined={} quads={} entities={} ber={} materials={} skipped={}"
                + " fluids={} spaces={} reasons={}",
            tally.produced, tally.examined, written, tally.entities, tally.berDrawn,
            collector.materialCount(), tally.skipped, tally.fluids, spaces.size(),
            tally.reasons);
        return written;
    }

    /** How many blocks a region export may cover before it is refused: 64 x 64 x 64. */
    private static final int REGION_MAX_BLOCKS = 64 * 64 * 64;

    /** How many {@code region: skip} lines one export may write. */
    private static final int REGION_SKIP_LINES = 20;

    /**
     * How many {@code region-block} and {@code region-ber} lines one export may write, each.
     *
     * <p>Bounded on purpose. A 64-cube holds a quarter of a million blocks, and one line per
     * block would be a log nobody can read and a frame nobody can keep; sixty is enough to
     * name every kind of part in a selection, because the interesting answer is "which shape
     * did this block take" rather than "what did block 200001 do".
     */
    private static final int REGION_DIAG_LINES = 60;

    /**
     * One coordinate space a region export draws from: the world, or one sub-level's storage.
     *
     * <p>The two are kept apart rather than merged because their block positions live in different
     * frames twenty million blocks apart. Each space carries the matrix that turns its own block
     * coordinates into the export's (origin at the selection's corner), so the per-block pipeline
     * never has to know which kind it is drawing.
     */
    private static final class RegionSpace {
        final String label;
        /** Object index in the .obj; 0 is the world, sub-levels follow. */
        final int index;
        /**
         * Base-relative block coordinates of this space -> export coordinates.
         *
         * <p>Base-relative rather than absolute on purpose. A sub-level's blocks are stored at
         * coordinates around 20.48 million, and a {@code float} cannot represent those exactly
         * (its mantissa runs out at 16,777,216), so passing them through the frame's rotation
         * would move a block by up to about a block. Subtracting the space's own corner first
         * keeps every number small, which is the form the blueprint path has always used.
         */
        final Matrix4f frame;
        /** The corner subtracted from every position of this space before the frame. */
        final BlockPos base;
        /** The selection's own corner in world coordinates, so a space point can be tested. */
        final Vec3 origin;
        /**
         * World coordinates to this space's own block coordinates, and back, in {@code double}.
         *
         * <p>{@link #frame} cannot do this job. It is a {@code float} matrix built around a
         * sub-level whose storage sits about 20.48 million blocks out, and a {@code float}
         * runs out of mantissa at 16,777,216 - so the inverse's translation is quantised to
         * steps of two whole blocks. For placement that error is invisible, because the
         * renderer's own pose is what puts a vertex where it belongs; for a query box it is
         * decisive, because a box that is two blocks off finds nothing and reports nothing.
         * Every reverse mapping - the entity query box and the containment test - is therefore
         * done here, in double.
         */
        final org.joml.Matrix4d worldToLocal;
        /** The exact forward map, matching {@link #worldToLocal}. */
        final org.joml.Matrix4d localToWorld;
        /**
         * The frame as it was composed, before it was narrowed to {@code float}.
         *
         * <p>This is the fix for the multi-structure misalignment. The frame is
         * {@code T(-origin) . pose . T(base)}, and for a sub-level both {@code pose}'s
         * translation and {@code base} sit around twenty million blocks out. Composing that
         * product in {@code float} makes every intermediate value round to the grid a
         * {@code float} can still express there - one step in two whole blocks at 2.05e7 -
         * and the two large terms only cancel <em>after</em> the rounding, so each component
         * of the result keeps one to three blocks of error. That error is per space, because
         * every sub-level has its own pose and its own corner: one structure exported alone
         * looks right (a rigid shift of a few blocks is invisible), and two structures in one
         * selection come out visibly out of line with each other.
         *
         * <p>Composing in {@code double} first leaves the cancellation exact; narrowing the
         * already-small result to {@code float} then costs about 1e-5 of a block, which is
         * what {@link #frame} - the matrix every vertex actually goes through - now holds.
         */
        final org.joml.Matrix4d frameExact;
        /**
         * How far the old float-only composition put this space's frame, per axis, in blocks.
         *
         * <p>Kept so the export can print the defect it no longer has. Zero for the world,
         * which was never affected, and a small number for a sub-level whose two large terms
         * happened to round back to the same value.
         */
        final float[] frameDrift;
        /** The sub-level this space belongs to, or null for the world itself. */
        final dev.ryanhcode.sable.sublevel.SubLevel owner;
        final java.util.Map<BlockPos, BlockState> states = new java.util.LinkedHashMap<>();
        final java.util.Map<BlockPos, BlockEntity> blockEntities =
            new java.util.LinkedHashMap<>();
        final java.util.List<BlockEntity> blockEntityList = new java.util.ArrayList<>();

        RegionSpace(String label, int index, Matrix4f frame, org.joml.Matrix4d frameExact,
                    float[] frameDrift, BlockPos base, Vec3 origin,
                    org.joml.Matrix4d localToWorld,
                    dev.ryanhcode.sable.sublevel.SubLevel owner) {
            this.label = label;
            this.index = index;
            this.frame = frame;
            this.frameExact = frameExact == null ? new org.joml.Matrix4d() : frameExact;
            this.frameDrift = frameDrift == null ? new float[] {0f, 0f, 0f} : frameDrift;
            this.base = base;
            this.origin = origin;
            this.localToWorld = localToWorld;
            org.joml.Matrix4d toLocal;
            try {
                toLocal = new org.joml.Matrix4d(localToWorld).invert();
            } catch (Throwable t) {
                // A degenerate pose leaves the space in its own coordinates; every caller
                // re-tests containment, so an identity here costs a wide query, not a wrong answer.
                toLocal = new org.joml.Matrix4d();
            }
            this.worldToLocal = toLocal;
            this.owner = owner;
        }

        /** A world point as this space writes it. */
        org.joml.Vector3d toLocal(double x, double y, double z) {
            org.joml.Vector3d point = new org.joml.Vector3d(x, y, z);
            this.worldToLocal.transformPosition(point);
            return point;
        }

        /** A point of this space's own coordinates, in the world. */
        org.joml.Vector3d toWorld(double x, double y, double z) {
            org.joml.Vector3d point = new org.joml.Vector3d(x, y, z);
            this.localToWorld.transformPosition(point);
            return point;
        }

        /** A position of this space, expressed the way the frame wants it. */
        BlockPos relative(BlockPos pos) {
            return pos.subtract(this.base);
        }

        /**
         * Where this space's own blocks should land in the export, as {@code min, max}.
         *
         * <p>Eight corners per block through {@link #frameExact}, because the frame carries a
         * rotation: mapping only the block's minimum corner would understate the box by up to
         * a block on two axes, and this is the reference the vertex check compares against.
         * Computed in {@code double} deliberately - the whole point of the check is to be the
         * exact answer the float pipeline is measured against.
         *
         * @return {@code {minX, minY, minZ, maxX, maxY, maxZ}} in export coordinates, or null
         *         when the space holds no blocks
         */
        float[] blockBounds() {
            float[] out = null;
            org.joml.Vector3d corner = new org.joml.Vector3d();
            for (BlockPos pos : this.states.keySet()) {
                for (int c = 0; c < 8; c++) {
                    corner.set(
                        pos.getX() - this.base.getX() + ((c & 1) == 0 ? 0.0 : 1.0),
                        pos.getY() - this.base.getY() + ((c & 2) == 0 ? 0.0 : 1.0),
                        pos.getZ() - this.base.getZ() + ((c & 4) == 0 ? 0.0 : 1.0));
                    this.frameExact.transformPosition(corner);
                    if (out == null) {
                        out = new float[] {(float) corner.x, (float) corner.y, (float) corner.z,
                            (float) corner.x, (float) corner.y, (float) corner.z};
                    } else {
                        out[0] = Math.min(out[0], (float) corner.x);
                        out[1] = Math.min(out[1], (float) corner.y);
                        out[2] = Math.min(out[2], (float) corner.z);
                        out[3] = Math.max(out[3], (float) corner.x);
                        out[4] = Math.max(out[4], (float) corner.y);
                        out[5] = Math.max(out[5], (float) corner.z);
                    }
                }
            }
            return out;
        }
    }

    /** Counts one region bake's blocks, and the reasons any of them contributed nothing. */
    private static final class RegionTally {
        int examined;
        int produced;
        int skipped;
        int fluids;
        int berDrawn;
        int entities;
        final java.util.Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
        private int lines;
        /** {@code region-block} lines written so far, against {@link #REGION_DIAG_LINES}. */
        private int blockLines;
        /** {@code region-ber} lines written so far, against {@link #REGION_DIAG_LINES}. */
        private int berLines;

        /** How many distinct block ids may be named outside the per-block caps. */
        private static final int REGION_DIAG_KINDS = 128;
        /** Block ids already named at least once, so no kind of part is ever silent. */
        private final java.util.Set<String> berKinds = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> blockKinds = new java.util.LinkedHashSet<>();

        /**
         * Whether this is the first line about a block id, and one more id is still affordable.
         *
         * <p>The caps below count <em>lines</em>, and a selection is walked in map order, so a
         * part that happens to be late in the walk is described nowhere - which is how the
         * mechanical bearing came to be missing from the log while two hundred copycat panels
         * were each named. One line per distinct id is bounded by the number of kinds of block
         * in the selection rather than by the number of blocks, so it can be unconditional.
         */
        boolean firstBerKind(String id) {
            return firstKind(id, this.berKinds);
        }

        /** The same, for {@code region-block} lines. */
        boolean firstBlockKind(String id) {
            return firstKind(id, this.blockKinds);
        }

        private static boolean firstKind(String id, java.util.Set<String> seen) {
            if (id == null || id.isEmpty() || seen.size() >= REGION_DIAG_KINDS) {
                return false;
            }
            return seen.add(id);
        }

        /** One diagnostic line per block while the cap allows it. */
        boolean wantBlockLine() {
            if (this.blockLines >= REGION_DIAG_LINES) {
                return false;
            }
            this.blockLines++;
            return true;
        }

        /** One diagnostic line per block entity while the cap allows it. */
        boolean wantBerLine() {
            if (this.berLines >= REGION_DIAG_LINES) {
                return false;
            }
            this.berLines++;
            return true;
        }

        /**
         * Settles the blocks the model pass drew nothing for.
         *
         * <p>A block whose shape lives in a renderer - a track link, a tyre, a barrel - emits no
         * model geometry at all and is drawn later, by the block-entity pass. Reporting it as a
         * skipped block would be wrong twice: the count would say the export lost it, and the
         * {@code region: skip} line would name a part that is in the file.
         */
        void resolve(java.util.List<RegionPending> pending, RegionSpace space, boolean rendererDrew) {
            for (RegionPending entry : pending) {
                if (rendererDrew && space.blockEntities.containsKey(entry.pos())) {
                    this.produced++;
                } else {
                    skip(entry.block(), entry.reason());
                }
            }
            pending.clear();
        }

        void skip(String block, String reason) {
            this.skipped++;
            this.reasons.merge(reason, 1, Integer::sum);
            if (this.lines < REGION_SKIP_LINES) {
                this.lines++;
                BlueprintExporter.LOGGER.info("region: skip block={} reason={}", block, reason);
            }
        }
    }

    /** A block the model pass drew nothing for, and why; the renderer pass may still draw it. */
    private record RegionPending(BlockPos pos, String block, String reason) {
    }

    /**
     * How far a space's geometry may sit from its own blocks before the export calls it drifted.
     *
     * <p>Half a block. Every legitimate difference is much smaller than that - a renderer that
     * draws a part a little outside its own cube, a face that only reaches the boundary - while
     * the fault this measures was whole blocks. A threshold between the two is what makes the
     * {@code verdict=} field worth reading.
     */
    private static final float SPACE_CHECK_TOLERANCE = 0.5f;

    /** Three numbers as one log field: two decimals, round half up, no locale in the way. */
    private static String triple(float a, float b, float c) {
        return String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", a, b, c);
    }

    /** The first three values of an array as a log field, or {@code -} when there are none. */
    private static String triple(float[] values) {
        return values == null || values.length < 3 ? "-" : triple(values[0], values[1], values[2]);
    }

    /** Three values of an array starting at {@code from}, as a log field. */
    private static String triple(float[] values, int from) {
        if (values == null || from < 0 || from + 2 >= values.length) {
            return "-";
        }
        return triple(values[from], values[from + 1], values[from + 2]);
    }

    /** One component of a box's centre: {@code axis} 0 is x, 1 is y, 2 is z. */
    private static float centre(float[] box, int axis) {
        return (box[axis] + box[axis + 3]) * 0.5f;
    }

    /**
     * The bounds of every vertex recorded from {@code from} on, as {@code min, max}.
     *
     * <p>Read straight off the faces the collector kept, so it is the geometry that will be
     * written to the file rather than a prediction of it. Faces are skipped when they were
     * rejected by the writer's own checks, which is the same set the OBJ carries.
     *
     * @return {@code {minX, minY, minZ, maxX, maxY, maxZ}}, or null when nothing was recorded
     */
    private static float[] vertexBounds(Collector collector, int from) {
        float[] out = null;
        for (int i = Math.max(0, from); i < collector.faces.size(); i++) {
            Face face = collector.faces.get(i);
            if (face == null || face.pos == null) {
                continue;
            }
            for (int v = 0; v + 2 < face.pos.length; v += 3) {
                float x = face.pos[v];
                float y = face.pos[v + 1];
                float z = face.pos[v + 2];
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                    continue;
                }
                if (out == null) {
                    out = new float[] {x, y, z, x, y, z};
                } else {
                    out[0] = Math.min(out[0], x);
                    out[1] = Math.min(out[1], y);
                    out[2] = Math.min(out[2], z);
                    out[3] = Math.max(out[3], x);
                    out[4] = Math.max(out[4], y);
                    out[5] = Math.max(out[5], z);
                }
            }
        }
        return out;
    }

    /** The world-space blocks inside the selection, read from the client level as it stands. */
    private static void scanRegionBlocks(Level level, RegionSpace space,
                                         int minX, int minY, int minZ,
                                         int maxX, int maxY, int maxZ, RegionTally tally) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state;
                    try {
                        state = level.getBlockState(cursor);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    // Water is a block but has no geometry of its own, and a selection beside a
                    // river would otherwise fill the skip log with it.
                    if (!state.getFluidState().isEmpty()) {
                        tally.fluids++;
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    space.states.put(pos, state);
                    tally.examined++;
                    BlockEntity be = null;
                    try {
                        be = level.getBlockEntity(pos);
                    } catch (Throwable ignored) {
                        // a block entity that cannot be read contributes its model only
                    }
                    if (be != null) {
                        space.blockEntities.put(pos, be);
                        space.blockEntityList.add(be);
                    }
                }
            }
        }
    }

    /**
     * Every sub-level that intersects the selection, mapped into its own storage coordinates.
     *
     * <p>Sable keeps a structure's blocks in the plot region its sub-level owns, at coordinates
     * unrelated to where the structure is drawn (the hand-off measures a difference of 20.48
     * million blocks), so the selection box cannot be used as a block range there. The box's eight
     * corners are mapped <em>through the inverse pose</em> instead, and the resulting range is what
     * gets walked; each block's own world position then decides whether it is inside the
     * selection, which is what makes a rotated or scaled sub-level come out right.
     */
    private static void scanRegionSubLevels(Level level, net.minecraft.world.phys.AABB box,
                                            Vec3 origin, java.util.List<RegionSpace> spaces,
                                            RegionTally tally) {
        java.lang.Iterable<dev.ryanhcode.sable.sublevel.SubLevel> candidates;
        try {
            candidates = dev.ryanhcode.sable.Sable.HELPER.getAllIntersecting(level,
                new dev.ryanhcode.sable.companion.math.BoundingBox3d(box));
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.info(
                "region: sub-level query unavailable ({}); exporting the world space only",
                t.toString());
            return;
        }
        if (candidates == null) {
            return;
        }
        int index = 1;
        for (dev.ryanhcode.sable.sublevel.SubLevel subLevel : candidates) {
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            try {
                scanOneSubLevel(level, box, origin, subLevel, index, spaces, tally);
                index++;
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.warn("region: sub-level skipped ({}): {}",
                    subLevel.getName(), t.toString());
            }
        }
    }

    private static void scanOneSubLevel(Level level, net.minecraft.world.phys.AABB box, Vec3 origin,
                                        dev.ryanhcode.sable.sublevel.SubLevel subLevel, int index,
                                        java.util.List<RegionSpace> spaces, RegionTally tally) {
        var pose = subLevel.logicalPose();
        if (pose == null) {
            return;
        }
        // The box, as seen from inside the sub-level. Its eight corners are enough: the pose is
        // affine, so the image of an axis-aligned box is a parallelepiped whose bounds are the
        // bounds of its image corners.
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? box.minX : box.maxX;
            double y = (corner & 2) == 0 ? box.minY : box.maxY;
            double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
            org.joml.Vector3d local = new org.joml.Vector3d(x, y, z);
            pose.transformPositionInverse(local, local);
            if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)) {
                return;
            }
            minX = Math.min(minX, local.x);
            minY = Math.min(minY, local.y);
            minZ = Math.min(minZ, local.z);
            maxX = Math.max(maxX, local.x);
            maxY = Math.max(maxY, local.y);
            maxZ = Math.max(maxZ, local.z);
        }
        org.joml.Matrix4d baked = new org.joml.Matrix4d();
        pose.bakeIntoMatrix(baked);
        // Three transforms in one matrix: `-base` so the numbers stay small, the sub-level's own
        // pose so the blocks appear where the structure is drawn, and `-origin` so the mesh is
        // written around the selection's corner rather than out in the plot.
        BlockPos base = new BlockPos((int) Math.floor(minX), (int) Math.floor(minY),
                                     (int) Math.floor(minZ));
        // Composed in `double`, narrowed once. See RegionSpace#frameExact for the measurement.
        //
        // The product is T(-origin) . pose . T(base). For a sub-level both `pose`'s translation
        // and `base` sit around twenty million blocks out, so composing this in `float` rounds
        // every intermediate value to the two-block grid a thirty-two bit mantissa can still
        // express there; the two large terms then cancel only after the rounding, and each
        // component of the result keeps one to three blocks of error - a different few blocks
        // for every space, which is exactly what put two structures of one selection out of
        // line with each other while a single structure still looked right.
        org.joml.Matrix4d exact = new org.joml.Matrix4d()
            .translation(-origin.x, -origin.y, -origin.z)
            .mul(baked)
            .translate(base.getX(), base.getY(), base.getZ());
        Matrix4f frame = new Matrix4f(exact);
        // The old float-only composition, kept solely so the export can print the drift it had.
        // Nothing is drawn through it.
        Matrix4f floatOnly = new Matrix4f().translation((float) -origin.x, (float) -origin.y,
                (float) -origin.z)
            .mul(new Matrix4f(baked))
            .translate(base.getX(), base.getY(), base.getZ());
        float[] drift = {floatOnly.m30() - (float) exact.m30(),
                         floatOnly.m31() - (float) exact.m31(),
                         floatOnly.m32() - (float) exact.m32()};
        String label = subLevel.getName() == null || subLevel.getName().isBlank()
            ? "sub" + index : "sub" + index + ":" + subLevel.getName().replace(' ', '_');
        RegionSpace space = new RegionSpace(label, index, frame, exact, drift, base, origin,
            new org.joml.Matrix4d(baked), subLevel);

        int fromX = (int) Math.floor(minX) - 1;
        int fromY = (int) Math.floor(minY) - 1;
        int fromZ = (int) Math.floor(minZ) - 1;
        int toX = (int) Math.ceil(maxX) + 1;
        int toY = (int) Math.ceil(maxY) + 1;
        int toZ = (int) Math.ceil(maxZ) + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state;
                    try {
                        // The block may be in the plot level or in the root level; the helper
                        // asks every level that could hold it.
                        state = SubLevelPositions.blockState(level, cursor);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    org.joml.Vector3d centre = new org.joml.Vector3d(x + 0.5, y + 0.5, z + 0.5);
                    pose.transformPosition(centre, centre);
                    if (!box.contains(centre.x, centre.y, centre.z)) {
                        continue;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        tally.fluids++;
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    space.states.put(pos, state);
                    tally.examined++;
                    try {
                        BlockEntity be = SubLevelPositions.blockEntity(level, pos);
                        if (be != null) {
                            space.blockEntities.put(pos, be);
                            space.blockEntityList.add(be);
                        }
                    } catch (Throwable ignored) {
                        // model only, as above
                    }
                }
            }
        }
        if (!space.states.isEmpty()) {
            spaces.add(space);
        }
    }

    /**
     * The model pass for one space: the same ladder the blueprint path walks, with a verdict per
     * block, because "the export came out empty" and "these fifty blocks had no model" are
     * different answers and only the block count tells them apart.
     */
    private static void renderRegionBlocks(RegionLevel w, RegionSpace space, PoseStack pose,
                                           BlockRenderDispatcher dispatcher, Collector collector,
                                           RegionTally tally, java.util.List<RegionPending> pending) {
        RandomSource random = RandomSource.create();
        for (java.util.Map.Entry<BlockPos, BlockState> entry : space.states.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState own = entry.getValue();
            if (own == null || own.isAir()) {
                continue;
            }
            int facesBefore = collector.faces.size();
            BlockState material = copiedMaterial(w, pos, own);
            BlockState draw = own;
            BakedModel model = null;
            String shapeSource;
            boolean threw = false;
            try {
                if (own.getRenderShape() == RenderShape.MODEL) {
                    BakedModel ownModel = dispatcher.getBlockModel(own);
                    if (ownModel != null && hasQuads(w, pos, own, ownModel, random)) {
                        shapeSource = "baked-quads";
                        model = ownModel;
                    } else if (material != null && !material.isAir()
                            && material.getRenderShape() == RenderShape.MODEL) {
                        BakedModel materialModel = dispatcher.getBlockModel(material);
                        if (materialModel != null
                                && hasQuads(w, pos, material, materialModel, random)) {
                            shapeSource = "copycat-material";
                            draw = material;
                            model = materialModel;
                        } else {
                            shapeSource = "voxelshape";
                        }
                    } else {
                        shapeSource = "voxelshape";
                    }
                } else {
                    // The geometry lives in a renderer, which the block-entity pass runs at the
                    // end of this method. Drawing a shape box here as well would draw a track
                    // twice: once as a box and once as its own renderer.
                    shapeSource = "entity-renderer";
                }
            } catch (Throwable t) {
                shapeSource = "voxelshape";
                threw = true;
            }
            long seed = draw.getSeed(pos);
            random.setSeed(seed);
            pose.pushPose();
            BlockPos local = space.relative(pos);
            collector.setBlockOffset(local.getX(), local.getY(), local.getZ(), space.frame);
            collector.resetQuad();
            if (model == null) {
                if ("voxelshape".equals(shapeSource)) {
                    try {
                        emitShape(collector, pose, w, pos, own);
                    } catch (Throwable t) {
                        threw = true;
                    }
                }
            } else {
                try {
                    renderBlockQuads(w, model, draw, pos, pose, collector, random);
                } catch (Throwable t) {
                    threw = true;
                }
            }
            pose.popPose();
            if (collector.faces.size() > facesBefore) {
                tally.produced++;
            } else {
                String block = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(own.getBlock()));
                pending.add(new RegionPending(pos, block, threw ? "exception"
                    : "entity-renderer".equals(shapeSource) ? "unsupported" : "no-model"));
            }
            // One line per block, capped. The four verdicts this can print are the four ways the
            // model pass can treat a block, and which one a part took is the question every
            // "the barrel is missing" report turns on: a block that came out as `entity-renderer`
            // has no model geometry by definition and depends entirely on its renderer, while one
            // that came out as `voxelshape` was given a box because nothing else answered.
            boolean blockFirst = tally.firstBlockKind(blockIdOf(own));
            if (blockFirst || tally.wantBlockLine()) {
                BlockEntity be = space.blockEntities.get(pos);
                BlueprintExporter.LOGGER.info(
                    "region-block: id={} state={} shape={} quads={} ber={} be-present={}",
                    blockIdOf(own), keyStateOf(own), shapeSource,
                    collector.faces.size() - facesBefore, berClassOf(be), be != null);
            }
            // The cannon probe, on its own counter so a fleet of eighty cannons cannot push the
            // four verdicts above out of the log. Asked per block rather than per texture, because
            // the question is about this block: the sprite its model would draw, and the texture
            // its faces actually ended up filed under.
            if (CANNON_TEX_LOGGED < CANNON_TEX_LIMIT) {
                String id = blockIdOf(own);
                if (isCannonPart(id)) {
                    logCannonTex(id, cannonShapeWord(shapeSource), spriteWordFor(model, draw),
                        collector.texturesSince(facesBefore),
                        collector.faces.size() - facesBefore);
                }
            }
        }
    }

    /** The registry id of a block, which is the only name a log line should carry. */
    private static String blockIdOf(net.minecraft.world.level.block.state.BlockState state) {
        try {
            return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()));
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------ cannon texture probe
    //
    // One defect, one question, asked once per part: does this cannon piece end up with a
    // texture a viewer can open, and if not, which step lost it? The four steps are the ones that
    // can each fail on their own - the model has no quads, the model's sprite is null, the
    // sprite's tile is not in the block atlas, or the material resolved to something no PNG can
    // be written for - so the line names the step rather than only the outcome.
    //
    // Measured before this probe existed: a CBC cannon's mount painted correctly (its textures
    // came out as `textures/block/cannon_mount/*.png`) while the barrel, whose geometry is drawn
    // by the `createbigcannons:pitch_contraption` entity, was filed under
    // `M_solid_minecraft_textures_atlas_blocks.png__entity` with no `map_Kd` at all - white in
    // Blender, with the geometry all present.

    /** The substrings that make a block or an entity part of a cannon's barrel geometry. */
    private static final String[] CANNON_TEX_KEYS = {"cannon", "barrel", "muzzle"};

    /** How many {@code cannon-tex} lines one bake may write, so a fleet cannot flood the log. */
    private static int CANNON_TEX_LOGGED = 0;

    private static final int CANNON_TEX_LIMIT = 240;

    /** How many {@code cannon-tex: saved} lines one bake may write, on its own budget. */
    private static int CANNON_SAVED_LOGGED = 0;

    private static final int CANNON_SAVED_LIMIT = 120;

    /**
     * The model pass's verdict in the four words the probe reports.
     *
     * <p>{@code copycat-material} means the block's own model was empty and the copied material's
     * model answered instead, which is still geometry from baked quads - so the probe says
     * {@code baked-quads} for both and the distinction stays in the {@code region-block} line.
     */
    private static String cannonShapeWord(String shapeSource) {
        if ("baked-quads".equals(shapeSource) || "copycat-material".equals(shapeSource)) {
            return "baked-quads";
        }
        if ("voxelshape".equals(shapeSource) || "entity-renderer".equals(shapeSource)) {
            return shapeSource;
        }
        return "skip";
    }

    /**
     * Whether an id names a cannon part.
     *
     * <p>{@code end} is tested with a boundary rather than as a bare substring: it is the suffix
     * CBC puts on the muzzle block - {@code cannon_end}, {@code *_cannon_barrel_end} - but a bare
     * match would also catch "extended", "suspended" and every {@code end_stone} in the pack.
     */
    private static boolean isCannonPart(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        for (String key : CANNON_TEX_KEYS) {
            if (lower.contains(key)) {
                return true;
            }
        }
        return lower.contains("_end") || lower.endsWith("end") || lower.contains(":end_")
            || lower.contains("/end");
    }

    /** Whether a texture path names a stitched atlas, which no file on disk can answer. */
    private static boolean isAtlasLocation(String texture) {
        if (texture == null || texture.isEmpty()) {
            return false;
        }
        String lower = texture.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(":atlas/") || lower.endsWith("textures/atlas/blocks.png");
    }

    /**
     * The sprite a block's own model would draw, as one word.
     *
     * <p>{@code missingno} is not "no sprite": it is the stand-in the game stitches when a model
     * declares a texture that no pack provides, and it is a different repair from a null sprite.
     * So the two are reported apart - see {@link #cannonTexWhy}.
     */
    private static String spriteWordFor(BakedModel model,
                                        net.minecraft.world.level.block.state.BlockState state) {
        if (model == null) {
            // A `voxelshape` verdict still paints one box, from the block's particle sprite, and
            // this is the same lookup that pass makes - so the word describes what was drawn
            // rather than what the model pass happened to hold.
            try {
                model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            } catch (Throwable ignored) {
                return "none";
            }
            if (model == null) {
                return "none";
            }
        }
        try {
            // A fresh source per lookup: the model pass has already drawn with its own, and the
            // quad list depends on the seed for weighted models.
            RandomSource random = RandomSource.create(0L);
            for (var direction : net.minecraft.core.Direction.values()) {
                for (var quad : model.getQuads(state, direction, random)) {
                    if (quad == null || quad.getSprite() == null) {
                        continue;
                    }
                    var contents = quad.getSprite().contents();
                    if (contents == null) {
                        continue;
                    }
                    return contents.name().getPath().contains("missingno")
                        ? "missingno" : "tile";
                }
            }
            // A model with no directional quads still has a particle sprite, which is the block's
            // own texture in every case that matters.
            var particle = model.getParticleIcon();
            if (particle != null && particle.contents() != null) {
                return particle.contents().name().getPath().contains("missingno")
                    ? "missingno" : "tile";
            }
        } catch (Throwable ignored) {
            // a model that will not enumerate answers as having no sprite
        }
        return "none";
    }

    /**
     * The step that lost the texture, or null when nothing was lost.
     *
     * <p>Ordered by what a reader can act on. Geometry that was never written is the first
     * answer, because no later step was reached. A material pointing at the atlas is the second:
     * that is the reported defect and no sprite word changes it. With no image at all, which step
     * failed is what the sprite word says.
     */
    private static String cannonTexWhy(String shape, String sprite, String texture, int quads) {
        if (quads <= 0) {
            return "no-quads";
        }
        boolean noTexture = texture == null || texture.isEmpty() || "-".equals(texture);
        if (!noTexture && isAtlasLocation(texture)) {
            return "not-in-atlas";
        }
        if (noTexture) {
            return "missingno".equals(sprite) ? "not-in-atlas"
                : "none".equals(sprite) ? "sprite-null" : "material-unresolved";
        }
        if ("missingno".equals(sprite)) {
            return "not-in-atlas";
        }
        if ("none".equals(sprite)) {
            return "sprite-null";
        }
        return null;
    }

    /** One {@code cannon-tex} line per texture a part was filed under, plus a {@code why} line. */
    private static void logCannonTex(String id, String shape, String sprite,
                                     java.util.List<Collector.TextureLine> textures, int quads) {
        java.util.List<Collector.TextureLine> lines = textures;
        if (lines == null || lines.isEmpty()) {
            lines = java.util.List.of(new Collector.TextureLine("-", "unknown", quads));
        }
        for (Collector.TextureLine line : lines) {
            if (CANNON_TEX_LOGGED >= CANNON_TEX_LIMIT) {
                return;
            }
            CANNON_TEX_LOGGED++;
            BlueprintExporter.LOGGER.info(
                "cannon-tex: id={} shape={} sprite={} tex={} uv-space={} quads={}",
                id, shape, sprite, line.texture(), line.uvSpace(), line.faces());
            String why = cannonTexWhy(shape, sprite, line.texture(), line.faces());
            if (why != null) {
                BlueprintExporter.LOGGER.info("cannon-tex: why={}", why);
            }
        }
    }

    /**
     * The block's own state, cut down to the properties that decide a shape.
     *
     * <p>{@code BlockState#toString} prints every property and every property value - a Create
     * shaft prints four - which turns one diagnostic line into three. Four properties, in the
     * definition's own order so two exports of one structure read the same, is what fits.
     */
    private static String keyStateOf(net.minecraft.world.level.block.state.BlockState state) {
        try {
            StringBuilder out = new StringBuilder();
            int written = 0;
            for (var property : state.getProperties()) {
                if (written >= 4) {
                    break;
                }
                if (written > 0) {
                    out.append(',');
                }
                out.append(property.getName()).append('=').append(valueOf(state, property));
                written++;
            }
            return written == 0 ? "-" : out.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** One property's value, in the shape {@code BlockState#getValue} wants. */
    private static <T extends Comparable<T>> String valueOf(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property) {
        return String.valueOf(state.getValue(property));
    }

    /**
     * The render types a block entity's renderer asked for, shortened to their names.
     *
     * <p>{@code RenderType#toString} prints the whole composite state - texture, shader, depth
     * test, five shards - which is three lines per render type in the log and buries the answer
     * the line exists to give. The leading token is the name the game itself shows.
     */
    private static String compactRenderTypes(BlockEntityCapture capture) {
        if (capture == null || capture.usedRenderTypes.isEmpty()) {
            return "";
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String type : capture.usedRenderTypes) {
            String name = type;
            int open = name.indexOf('[');
            if (open >= 0) {
                int stop = name.indexOf(':', open);
                name = stop > open ? name.substring(open + 1, stop) : name.substring(open + 1);
            }
            if (name.length() > 28) {
                name = name.substring(0, 28);
            }
            if (!names.contains(name)) {
                names.add(name);
            }
            if (names.size() >= 4) {
                break;
            }
        }
        return String.join(",", names);
    }

    /**
     * Flywheel's answer to "does this world draw its block entities with the visual engine",
     * switched off for the length of one renderer call and put back afterwards.
     *
     * <p>This is the difference between an export that carries a cannon barrel, a cogwheel and a
     * cannon carriage and one that does not. Four renderers in this pack were read with
     * {@code javap}, and each of them wraps its entire body in
     *
     * <pre>{@code
     * if (!VisualizationManager.supportsVisualization(be.getLevel())) { ...write vertices... }
     * }</pre>
     *
     * <p>- CBC's {@code CannonMountBlockEntityRenderer} (the yaw shaft, the pitch shaft, the
     * rotating mount and the carriage axle), Create's {@code EncasedCogRenderer}, and the same
     * pattern in the shaft and bearing renderers. On a client with Flywheel running that predicate
     * is true, so the method returns without writing a single vertex: the geometry is drawn by a
     * visual instead, which builds instances on the GPU and never hands a
     * {@code VertexConsumer} anything. A capture that only asks the renderer therefore gets
     * nothing, and the parts the player asked about - the barrel, the gears, the bearings - are
     * exactly the parts drawn that way.
     *
     * <p>The predicate is reached through two static calls -
     * {@code VisualizationManager.supportsVisualization} to {@code FlwApiLinkImpl} to
     * {@code VisualizationManagerImpl.supportsVisualization} - and the first thing that method
     * tests is {@code BackendManagerImpl.isBackendOn()}, which is one comparison against a
     * private static field:
     *
     * <pre>{@code
     * private static Backend backend;             // not final
     * public static boolean isBackendOn() { return backend != OFF_BACKEND; }
     * }</pre>
     *
     * <p>Pointing that field at {@code OFF_BACKEND} for the duration of the call makes every
     * renderer take the non-visual path - the same path a client with Flywheel disabled takes,
     * which is a configuration the game supports - and it is set back in a {@code finally}, so a
     * renderer that throws cannot leave the client believing it has no visual engine. Nothing is
     * written to a final field and no class is modified: this is a plain write to a plain static
     * field, through reflection, with the previous value restored.
     *
     * <p>Three things make it acceptable rather than a hack to regret:
     *
     * <ul>
     *   <li>it is only used by the region export, which runs on the client thread between frames,
     *       never inside a frame that the visual engine is drawing;</li>
     *   <li>the value is restored before control returns to anything else, including on a
     *       renderer that throws;</li>
     *   <li>if Flywheel is absent, or the field is not where it is expected, the gate simply
     *       never engages and the export behaves as it did before - {@code require=0}.</li>
     * </ul>
     */
    private static final class FlywheelGate {
        /** Resolved once: whether the field and the sentinel could both be read. */
        private static boolean resolved;
        private static boolean usable;
        private static java.lang.reflect.Field backendField;
        private static Object offBackend;
        /** How many callers hold the gate, so a nested render cannot restore it early. */
        private static int depth;
        /** The backend that was in use when the outermost caller engaged the gate. */
        private static Object saved;

        private static void resolve() {
            if (resolved) {
                return;
            }
            resolved = true;
            try {
                // The implementation class rather than the API: `isBackendOn` is the field test,
                // and the sentinel lives beside it.
                Class<?> impl = Class.forName("dev.engine_room.flywheel.impl.BackendManagerImpl");
                java.lang.reflect.Field field = impl.getDeclaredField("backend");
                field.setAccessible(true);
                java.lang.reflect.Field off = impl.getDeclaredField("OFF_BACKEND");
                off.setAccessible(true);
                backendField = field;
                offBackend = off.get(null);
                usable = offBackend != null;
                BlueprintExporter.LOGGER.info(
                    "region: flywheel gate {} (backend={} off={})",
                    usable ? "ready" : "unavailable", field.getType().getSimpleName(),
                    offBackend == null ? "null" : offBackend.getClass().getSimpleName());
            } catch (Throwable t) {
                usable = false;
                // A modpack without Flywheel is the ordinary case here, not a fault.
                BlueprintExporter.LOGGER.info("region: flywheel gate unavailable ({})",
                    t.getClass().getSimpleName());
            }
        }

        /** @return true when the caller must call {@link #release} with this value */
        static boolean engage() {
            resolve();
            if (!usable) {
                return false;
            }
            try {
                Object current = backendField.get(null);
                if (current == offBackend) {
                    // Already off - the player runs without visuals - so there is nothing to lie
                    // about and nothing to restore.
                    return false;
                }
                if (depth == 0) {
                    saved = current;
                }
                depth++;
                backendField.set(null, offBackend);
                return true;
            } catch (Throwable t) {
                usable = false;
                return false;
            }
        }

        static void release(boolean engaged) {
            if (!engaged) {
                return;
            }
            try {
                if (depth > 0) {
                    depth--;
                }
                if (depth == 0 && backendField != null) {
                    backendField.set(null, saved);
                    saved = null;
                }
            } catch (Throwable t) {
                usable = false;
            }
        }
    }

    /**
     * The partial tick of the frame being drawn, as the game passes it to its own renderers.
     *
     * <p>{@code Minecraft} has no {@code getFrameTime} in this version: the number lives on the
     * delta tracker ({@code Minecraft#getTimer}), and {@code getGameTimeDeltaPartialTick(false)}
     * is the same value the level renderer hands a block entity renderer. A renderer that
     * interpolates - a cannon's pitch, a track belt's scroll, a cogwheel's angle - reads it, and
     * a zero asks for the state at the last tick boundary instead of the state on screen.
     */
    private static float framePartialTick() {
        try {
            var timer = Minecraft.getInstance().getTimer();
            return timer == null ? 0.0f : timer.getGameTimeDeltaPartialTick(false);
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    /**
     * The simple name of the renderer that owns a block entity, or {@code -}.
     *
     * <p>Printed because "the barrel has no geometry" and "the barrel's renderer never ran" are
     * different faults with the same symptom, and only the class name tells them apart.
     */
    /**
     * The {@code [tracks]} belt's own texture file, which nothing in the block pipeline names.
     *
     * <p>A track link is a partial model built in the mod's own code - {@code TRACKWORK_TRACK_LINK}
     * - and the image it draws with is a sprite shift constant, {@code TracksSpriteShifts.BELT =
     * SpriteShifter.get(tracks:block/belt, tracks:block/belt_scroll)}. That name appears in no
     * blockstate, no model file and no render type: the renderer draws with
     * {@code RenderType.cutout()}, whose texture is the block atlas, so every fallback the
     * exporter has - the block's own model sprite, the render type's texture - answers with the
     * suspension mount the block happens to use. The result was a track whose geometry was right
     * and whose image was a wheel mount's, plus a fourth material named after whatever atlas tile
     * the renderer's 0..1 coordinates happened to land in.
     *
     * <p>The constant is read by reflection rather than by import, because this mod must load
     * without {@code [tracks]} installed: a {@code NoClassDefFoundError} at class level would take
     * the whole exporter down on a pack that has no tracks at all. Two routes are tried, the
     * sprite and then the resource location it was made from, and the literal name is the last
     * resort - it is the same value the constant holds, so a pack that renamed the class still
     * gets the right image.
     */
    private static final String BELT_TEXTURE_FALLBACK = "tracks:block/belt";

    private static final class BeltSprite {
        private static final String SHIFTS_CLASS = "dev.qwxon.tracks.index.TracksSpriteShifts";
        private static boolean resolved;
        private static String texture;
        private static String source = "-";
        private static net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;

        /** The belt's texture name, or null when the texture cannot be named at all. */
        static String texture() {
            resolve();
            return texture;
        }

        /** The belt's atlas sprite, or null - the UV conversion needs it, the name does not. */
        static net.minecraft.client.renderer.texture.TextureAtlasSprite sprite() {
            resolve();
            return sprite;
        }

        static String source() {
            resolve();
            return source;
        }

        private static synchronized void resolve() {
            if (resolved) {
                return;
            }
            resolved = true;
            String reflected = reflectedName();
            if (reflected != null) {
                texture = reflected;
                source = "reflect";
            } else {
                texture = BELT_TEXTURE_FALLBACK;
                source = "table";
            }
            sprite = spriteFor(texture);
            if (sprite != null) {
                // The constant always wins, because a sprite knows the exact location it was
                // stitched from; the literal is only consulted when the constant did not answer.
                var id = sprite.contents() == null ? null : sprite.contents().name();
                if (id != null) {
                    texture = id.getNamespace() + ":" + id.getPath();
                }
            } else if (reflected == null) {
                source = "table-no-atlas";
            }
            BlueprintExporter.LOGGER.info("region: belt sprite texture={} source={} atlas={}",
                texture, source, sprite == null ? "none" : "found");
        }

        /** {@code TracksSpriteShifts.BELT}, read without naming the class in this mod's bytecode. */
        private static String reflectedName() {
            try {
                Class<?> shifts = Class.forName(SHIFTS_CLASS, true,
                    BeltSprite.class.getClassLoader());
                Object entry = shifts.getField("BELT").get(null);
                if (entry == null) {
                    return null;
                }
                Object original = null;
                try {
                    original = entry.getClass().getMethod("getOriginal").invoke(entry);
                } catch (Throwable ignored) {
                    // An older catnip: fall through to the resource location below.
                }
                if (original instanceof net.minecraft.client.renderer.texture.TextureAtlasSprite s
                        && s.contents() != null) {
                    var id = s.contents().name();
                    BlueprintExporter.LOGGER.info("region: belt sprite resolved from {}",
                        SHIFTS_CLASS);
                    return id.getNamespace() + ":" + id.getPath();
                }
                Object location = entry.getClass().getMethod("getOriginalResourceLocation")
                    .invoke(entry);
                if (location instanceof net.minecraft.resources.ResourceLocation rl) {
                    BlueprintExporter.LOGGER.info("region: belt sprite resolved from {} location",
                        SHIFTS_CLASS);
                    return rl.getNamespace() + ":" + rl.getPath();
                }
                return null;
            } catch (Throwable t) {
                // Not installed, not initialised, or a different field name: the caller falls
                // back to the literal, and the log says which of the two answered.
                BlueprintExporter.LOGGER.info("region: belt sprite reflection unavailable ({})",
                    t.getClass().getSimpleName());
                return null;
            }
        }

        /**
         * The stitched tile for a texture path.
         *
         * <p>Through the real {@code TextureAtlas}, not {@code Minecraft#getTextureAtlas}: the
         * latter returns one-texel placeholders whose names are wrong, which is the mistake an
         * earlier version of the tile lookup made and had to undo.
         */
        private static net.minecraft.client.renderer.texture.TextureAtlasSprite spriteFor(
                String path) {
            if (path == null || path.isEmpty()) {
                return null;
            }
            try {
                var located = net.minecraft.resources.ResourceLocation.tryParse(path);
                if (located == null) {
                    return null;
                }
                var minecraft = Minecraft.getInstance();
                if (minecraft == null) {
                    return null;
                }
                var texture = minecraft.getTextureManager().getTexture(
                    net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
                if (texture instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas) {
                    var found = atlas.getSprite(located);
                    if (found != null && found.contents() != null) {
                        return found;
                    }
                }
                var lookup = minecraft.getTextureAtlas(
                    net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
                var fallback = lookup == null ? null : lookup.apply(located);
                return fallback != null && fallback.contents() != null ? fallback : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Whether a block-entity renderer is one of the track renderers.
     *
     * <p>A name test rather than a type test, because the class is not on this mod's compile
     * path and must not be: the whole point is that a pack without {@code [tracks]} still
     * exports. {@code [tracks]} names its renderers {@code SableTrackRenderer},
     * {@code AdjustableWheelMountRenderer} and so on, and the substring is what identifies them.
     */
    private static boolean isTrackRenderer(String simpleName) {
        if (simpleName == null) {
            return false;
        }
        String lower = simpleName.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("track") || lower.contains("belt");
    }

    private static String berClassOf(BlockEntity be) {
        if (be == null) {
            return "-";
        }
        try {
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            return renderer == null ? "-" : renderer.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Entities inside the selection, drawn through the entity renderer into the same collector.
     *
     * <p>A frame capture needs an armed window and a hook in the renderer; this needs neither. The
     * dispatcher is called directly with a {@code MultiBufferSource} whose consumer is the
     * collector, which is the same arrangement the block-entity pass already relies on. The one
     * difference is the pose: an entity model's vertices arrive with the entity's own stack
     * already applied, so the consumer puts them into export coordinates itself instead of
     * letting the collector apply a frame it never saw.
     */
    private static int renderRegionEntities(Minecraft mc, Level level,
                                            net.minecraft.world.phys.AABB box, Vec3 origin,
                                            java.util.List<RegionSpace> spaces, Collector collector) {
        var dispatcher = mc.getEntityRenderDispatcher();
        if (dispatcher == null) {
            return 0;
        }
        int drawn = 0;
        // Entities whose renderer was called and wrote no geometry. Counted apart from `drawn`
        // because the summary used to add them together and report a selection as complete.
        int empty = 0;
        // Every place an entity can be stored, asked in that place's own coordinates.
        //
        // The world is not the only store. A selection that sits on a Sable structure has its
        // blocks in the plot grid - the export's own space lines show them at twenty million
        // blocks out - and an entity that belongs to that structure is stored the same way, so
        // a world-space query over the selection is answered with nothing even when the
        // carriage is visibly in front of the player. That is what "entities=0" was.
        //
        // So each space is asked in its own coordinates, and which space an answer came from is
        // kept: it is what says which transform places the model. The scan line is written per
        // space even when the answer is zero, because "nothing there" and "the query never ran"
        // are the same silence in the summary line.
        java.util.Map<net.minecraft.world.entity.Entity, RegionSpace> found =
            new java.util.LinkedHashMap<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int totalFound = 0;
        for (RegionSpace space : spaces) {
            net.minecraft.world.phys.AABB query;
            try {
                query = localBox(space, box, origin);
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.info(
                    "region-entity: scan level={} aabb=- found=0 classes=[] ({})",
                    levelLabel(space), t.getClass().getSimpleName());
                continue;
            }
            java.util.List<net.minecraft.world.entity.Entity> candidates;
            try {
                candidates = level.getEntities(null, query);
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.info(
                    "region-entity: scan level={} aabb={} found=0 classes=[] ({})",
                    levelLabel(space), rangeOf(query), t.getClass().getSimpleName());
                continue;
            }
            java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
            int fresh = 0;
            if (candidates != null) {
                for (net.minecraft.world.entity.Entity entity : candidates) {
                    if (entity == null) {
                        continue;
                    }
                    classes.add(entityTypeId(entity));
                    if (!seen.add(entity.getId())) {
                        continue;
                    }
                    found.put(entity, space);
                    fresh++;
                }
            }
            totalFound += fresh;
            BlueprintExporter.LOGGER.info(
                "region-entity: scan level={} aabb={} found={} classes=[{}]",
                levelLabel(space), rangeOf(query), fresh, String.join(",", classes));
        }

        int[] lines = new int[1];
        for (java.util.Map.Entry<net.minecraft.world.entity.Entity, RegionSpace> entry
                : found.entrySet()) {
            net.minecraft.world.entity.Entity entity = entry.getKey();
            if (entity == null) {
                continue;
            }
            if (entity.isRemoved() || entity == mc.player) {
                logRegionEntity(lines, entity, null, 0, false, "filtered", "-", false);
                continue;
            }
            // Which space the entity's numbers are in is decided by where they land rather than
            // by which query returned them: Sable's own entity getter answers a world-space box
            // with entities from a sub-level's storage as well, and those numbers are not world
            // coordinates. Every space is tried, the one that contains the entity wins, and a
            // rotated or scaled sub-level is handled by the same test as an upright one.
            RegionSpace home = entry.getValue();
            org.joml.Vector3d world = home.toWorld(entity.getX(), entity.getY(), entity.getZ());
            if (!contains(box, world)) {
                home = null;
                for (RegionSpace other : spaces) {
                    org.joml.Vector3d candidate =
                        other.toWorld(entity.getX(), entity.getY(), entity.getZ());
                    if (contains(box, candidate)) {
                        home = other;
                        world = candidate;
                        break;
                    }
                }
            }
            if (home == null) {
                logRegionEntity(lines, entity, world, 0, false, "outside-aabb", "-", false);
                continue;
            }
            // The renderer is looked up once and named in the line below. `no-renderer` and
            // "the renderer ran but wrote nothing" are different faults - the first is a
            // registration problem, the second is a renderer that decided not to draw - and
            // only the class name tells them apart.
            var renderer = dispatcher.getRenderer(entity);
            String rendererName = renderer == null ? "-" : renderer.getClass().getSimpleName();
            if (renderer == null) {
                logRegionEntity(lines, entity, world, 0, false, "no-renderer", rendererName, false);
                continue;
            }
            // The model's own rotation comes from the frame; the translation is taken from the
            // double-precision map instead of the frame's own, which is a float built around a
            // twenty-million-block offset and can be whole blocks out.
            Matrix4f transform = new Matrix4f(home.frame);
            transform.m30((float) (world.x - origin.x))
                .m31((float) (world.y - origin.y))
                .m32((float) (world.z - origin.z));
            collector.setPoseWithSeparateOffset(new Matrix4f());
            collector.setBlockOffset(0f, 0f, 0f, null);
            // An entity model's UVs are in its own texture's 0..1 space, not the block atlas:
            // no sprite means no conversion, which is what `setSprite(null)` arranges.
            collector.setSprite(null);
            collector.setUvNeedsAtlasConversion(true);
            RegionEntityCapture capture = new RegionEntityCapture(collector, transform);
            // The entity's own id becomes the material suffix. Without it every entity's faces
            // were named `__entity`, so a cannon's barrel and a periscope could not be told apart
            // in the outliner - and the cannon probe had no id to print.
            capture.beginEntity(sanitiseLabel(entityTypeId(entity)));
            int before = collector.faces.size();
            // The same gate the block-entity pass uses, for the same reason and in the same
            // place. Create 6.0.10's `ContraptionEntityRenderer#render` asks
            // `VisualizationManager.supportsVisualization(level)` and, when the answer is yes,
            // jumps straight past the loop that draws the contraption's blocks - because
            // Flywheel draws a contraption as a visual, uploading instances and handing no
            // `VertexConsumer` anything. javap puts that branch at bytecode 62-65 of `render`.
            //
            // That is precisely the part this export was missing: a mechanical bearing's
            // rotating assembly is a `create:stationary_contraption`, drawn by that renderer,
            // and a pass that only asks it records `quads=0` while the assembly is plainly on
            // screen. Turning the visual engine's answer off for the length of the call makes
            // the renderer take the non-visual path - the one a client without Flywheel takes -
            // which writes the contraption's blocks and block entities into the buffer source
            // this pass hands it. The answer is restored in the `finally`, including when the
            // renderer throws, so nothing else in the frame sees the lie.
            boolean gated = FlywheelGate.engage();
            try {
                dispatcher.render(entity, 0.0D, 0.0D, 0.0D, entity.getYRot(), 1.0f,
                    new PoseStack(), capture,
                    net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                drawn++;
                // The renderer's last quad is still pending here, because the separately-written
                // path now closes a face on the next vertex rather than on its own fourth one.
                // Closed before anything reads the face list, or the count below would be one
                // short and the cannon probe would miss the last face's texture.
                collector.closePendingQuad();
                int quads = collector.faces.size() - before;
                if (quads == 0) {
                    empty++;
                }
                // `ok` used to be printed for a renderer that wrote nothing, which is how a
                // contraption that never drew its blocks looked exactly like one that did.
                logRegionEntity(lines, entity, world, quads, true,
                    quads > 0 ? "ok" : "ok-no-quads", rendererName, gated);
                logRegionEntityTextures(entity, collector, before);
            } catch (Throwable t) {
                BlueprintExporter.NOTES.add("entity " + entity.getType() + " did not render: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                logRegionEntity(lines, entity, world, collector.faces.size() - before, false,
                    "exception", rendererName, gated);
                if (DIAGNOSTICS) {
                    BlueprintExporter.LOGGER.warn("region: entity {} did not render: {}",
                        entityTypeId(entity), t.toString());
                }
            } finally {
                FlywheelGate.release(gated);
                collector.unpinMaterial();
                collector.resetQuad();
            }
        }
        BlueprintExporter.LOGGER.info(
            "region-entity: summary drawn={} found={} spaces={} empty={}",
            drawn, totalFound, spaces.size(), empty);
        return drawn;
    }

    /** One line per processed entity, capped so a crowded selection cannot flood the log. */
    private static void logRegionEntity(int[] lines, net.minecraft.world.entity.Entity entity,
                                        org.joml.Vector3d world, int quads, boolean ok,
                                        String reason, String renderer, boolean gated) {
        if (lines[0] >= 40) {
            return;
        }
        lines[0]++;
        double x = world == null ? entity.getX() : world.x;
        double y = world == null ? entity.getY() : world.y;
        double z = world == null ? entity.getZ() : world.z;
        BlueprintExporter.LOGGER.info(
            "region-entity: type={} pos=({}, {}, {}) quads={} ok={} reason={} renderer={}"
                + " flywheel={} contraption-blocks={}",
            entityTypeId(entity),
            String.format(java.util.Locale.ROOT, "%.2f", x),
            String.format(java.util.Locale.ROOT, "%.2f", y),
            String.format(java.util.Locale.ROOT, "%.2f", z),
            quads, ok, reason, renderer == null ? "-" : renderer,
            gated ? "gated" : "on", contraptionBlocks(entity));
    }

    /**
     * How many blocks a contraption entity carries, read reflectively, or {@code -}.
     *
     * <p>This is the number that decides whether a contraption that produced no geometry is
     * empty or merely undrawn: a bearing's assembly reports its block count here even when its
     * renderer wrote nothing, so "there was nothing there" and "there was something and it was
     * not drawn" stop reading the same. The classes are Create's, which is not on this mod's
     * compile path, so the whole walk is reflective and every failure is a {@code -} rather
     * than an exception: {@code require=0}.
     */
    private static String contraptionBlocks(net.minecraft.world.entity.Entity entity) {
        try {
            java.lang.reflect.Method getContraption = null;
            for (Class<?> type = entity.getClass(); type != null && getContraption == null;
                    type = type.getSuperclass()) {
                try {
                    getContraption = type.getMethod("getContraption");
                } catch (NoSuchMethodException ignored) {
                    // keep walking up: the method lives on AbstractContraptionEntity
                }
            }
            if (getContraption == null) {
                return "-";
            }
            Object contraption = getContraption.invoke(entity);
            if (contraption == null) {
                return "null";
            }
            Object blocks = contraption.getClass().getMethod("getBlocks").invoke(contraption);
            return blocks instanceof java.util.Map<?, ?> map ? String.valueOf(map.size()) : "-";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** The entity type as a registry id, which is the only name a log reader can search for. */
    private static String entityTypeId(net.minecraft.world.entity.Entity entity) {
        try {
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType());
            return key == null ? String.valueOf(entity.getType()) : key.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * The id as a material-name suffix: letters, digits and one underscore, nothing else.
     *
     * <p>An OBJ material name is a single token and a {@code newmtl} line ends at whitespace, so
     * a name carrying a colon or a space is a name one importer reads and another truncates.
     */
    private static String sanitiseLabel(String id) {
        if (id == null || id.isEmpty()) {
            return "entity";
        }
        StringBuilder out = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return out.toString();
    }

    /**
     * The {@code cannon-tex} lines for one entity, when it drew cannon geometry.
     *
     * <p>This is the line the whole probe exists for. A CBC cannon's barrel is not a block in the
     * selection: it lives inside the {@code createbigcannons:pitch_contraption} entity's
     * contraption, so the block pass never sees it and the only place its texture can be reported
     * is here, after the renderer has written its faces. The entity is reported when either its
     * own id or one of the textures it used names a cannon part - the second test is what catches
     * the barrel, whose blocks are named {@code createbigcannons:block/cannon_barrel/*} while the
     * entity is only "a pitch contraption".
     */
    private static void logRegionEntityTextures(net.minecraft.world.entity.Entity entity,
                                                Collector collector, int before) {
        if (CANNON_TEX_LOGGED >= CANNON_TEX_LIMIT) {
            return;
        }
        String type = entityTypeId(entity);
        boolean named = isCannonPart(type);
        java.util.List<Collector.TextureLine> lines = collector.texturesSince(before);
        if (lines.isEmpty()) {
            if (named) {
                logCannonTex(type, "entity-renderer", "none", null, 0);
            }
            return;
        }
        for (Collector.TextureLine line : lines) {
            if (!named && !isCannonPart(line.texture())) {
                continue;
            }
            boolean usable = line.texture() != null && !"-".equals(line.texture())
                && !isAtlasLocation(line.texture());
            logCannonTex(type, "entity-renderer", usable ? "tile" : "none",
                java.util.List.of(line), line.faces());
        }
    }

    private static boolean contains(net.minecraft.world.phys.AABB box, org.joml.Vector3d point) {
        return point != null && box != null
            && box.contains(point.x, point.y, point.z);
    }

    /**
     * The level a scan line is about: {@code root} for the world, the space's own name otherwise.
     *
     * <p>Named rather than numbered because the question this line exists to answer is exactly
     * "is this entity in the world or in a sub-level", and a bare index does not answer it.
     */
    private static String levelLabel(RegionSpace space) {
        if (space == null) {
            return "-";
        }
        return space.index == 0 ? "root" : space.label;
    }

    /** A box as a log field: two corners, one decimal, locale-free. */
    private static String rangeOf(net.minecraft.world.phys.AABB box) {        if (box == null) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT,
            "(%.1f, %.1f, %.1f)..(%.1f, %.1f, %.1f)",
            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * The part of a space's own coordinates that the selection covers, with a block of slack.
     *
     * <p>The frame maps {@code position - base} to export coordinates, so walking it backwards from
     * the selection gives base-relative numbers; those are shifted back by {@code base}, because
     * {@code Level#getEntities} answers in the space's own coordinates.
     */
    private static net.minecraft.world.phys.AABB localBox(RegionSpace space,
                                                          net.minecraft.world.phys.AABB box,
                                                          Vec3 origin) {
        try {
            // In double, and through the space's exact map rather than the inverse of its float
            // frame: see RegionSpace#worldToLocal. A two-block error in this box is the
            // difference between the entity pass finding a carriage and finding nothing, and
            // the previous version's error was exactly that size.
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int corner = 0; corner < 8; corner++) {
                double x = (corner & 1) == 0 ? box.minX : box.maxX;
                double y = (corner & 2) == 0 ? box.minY : box.maxY;
                double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
                org.joml.Vector3d local = space.toLocal(x, y, z);
                if (!Double.isFinite(local.x) || !Double.isFinite(local.y)
                    || !Double.isFinite(local.z)) {
                    return box.move(space.base.getX(), space.base.getY(), space.base.getZ());
                }
                minX = Math.min(minX, local.x);
                minY = Math.min(minY, local.y);
                minZ = Math.min(minZ, local.z);
                maxX = Math.max(maxX, local.x);
                maxY = Math.max(maxY, local.y);
                maxZ = Math.max(maxZ, local.z);
            }
            // Two blocks of slack, because an entity's position is a point and its bounding box
            // can stick out of the space its centre is in.
            return new net.minecraft.world.phys.AABB(
                minX - 2, minY - 2, minZ - 2, maxX + 2, maxY + 2, maxZ + 2);
        } catch (Throwable t) {
            // A degenerate frame answers the selection's own corners with the space's block range
            // alongside them; every entity is filtered again after the transform, so an over-wide
            // box only costs a query.
            return box.move(space.base.getX(), space.base.getY(), space.base.getZ());
        }
    }

    /**
     * A view of one region space that is a {@code SchematicLevel} to the rest of the pipeline.
     *
     * <p>Everything downstream - {@link #renderBlockQuads}, {@link #renderBlockEntities} - takes a
     * {@code SchematicLevel} because that is what the blueprint path feeds them. What they ask it
     * for is a block state, a block entity and a bounds, so answering those three from the live
     * world is the whole of the adapter: the models, the {@code ModelData}, the renderers and the
     * materials stay the ones the game would draw, which is the point of an export from the world
     * rather than from a saved file.
     */
    private static final class RegionLevel extends net.createmod.catnip.levelWrappers.SchematicLevel {
        private final Level source;
        private final java.util.Map<BlockPos, BlockState> states;
        private final java.util.Map<BlockPos, BlockEntity> entities;
        private final java.util.List<BlockEntity> entityList;
        private final BoundingBox regionBounds;

        RegionLevel(Level level, RegionSpace space) {
            super(level);
            this.source = level;
            this.states = space.states;
            this.entities = space.blockEntities;
            this.entityList = space.blockEntityList;
            this.regionBounds = boundsOf(space);
            this.setBounds(this.regionBounds);
            this.anchor = BlockPos.ZERO;
            this.renderMode = true;
        }

        private static BoundingBox boundsOf(RegionSpace space) {
            if (space.states.isEmpty()) {
                return new BoundingBox(BlockPos.ZERO);
            }
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : space.states.keySet()) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            return BoundingBox.fromCorners(new Vec3i(minX, minY, minZ),
                                           new Vec3i(maxX, maxY, maxZ));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = this.states == null ? null : this.states.get(pos);
            if (state != null) {
                return state;
            }
            // Outside the selection the real world answers. A fence, a pane or a stair along the
            // edge of the region reads its neighbours, and answering "air" for everything outside
            // would export a fence with its rails missing - the selection would look like a cut
            // through the structure rather than a piece of it. Nothing outside the selection is
            // ever drawn: the per-block pass walks the region's own list, not this level.
            if (this.source != null) {
                try {
                    BlockState real = SubLevelPositions.blockState(this.source, pos);
                    if (real != null) {
                        return real;
                    }
                } catch (Throwable ignored) {
                    // fall through to air rather than failing a block lookup
                }
            }
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (this.entities != null && this.entities.containsKey(pos)) {
                return this.entities.get(pos);
            }
            if (this.source != null) {
                try {
                    return SubLevelPositions.blockEntity(this.source, pos);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public java.lang.Iterable<BlockEntity> getBlockEntities() {
            return this.entityList == null ? java.util.List.of() : this.entityList;
        }

        @Override
        public java.lang.Iterable<BlockEntity> getRenderedBlockEntities() {
            return this.entityList == null ? java.util.List.of() : this.entityList;
        }

        @Override
        public BoundingBox getBounds() {
            return this.regionBounds;
        }
    }

    /**
     * A {@code MultiBufferSource} for one entity, in export coordinates.
     *
     * <p>Naming still goes through {@link BlockEntityCapture}: an entity renderer names its texture
     * only through the render type it asks for, which is the same statement a block-entity renderer
     * makes, so the same code can read it.
     */
    private static final class RegionEntityCapture
            implements net.minecraft.client.renderer.MultiBufferSource {
        private final Collector collector;
        private final Matrix4f transform;
        private final BlockEntityCapture naming;

        RegionEntityCapture(Collector collector, Matrix4f transform) {
            this.collector = collector;
            this.transform = transform;
            this.naming = new BlockEntityCapture(collector);
            this.naming.currentBlockLabel = "entity";
        }

        /**
         * Name the entity whose faces are about to be written.
         *
         * <p>The label rides in the material name, so it is what makes a cannon's barrel and a
         * periscope distinguishable in the outliner instead of both reading {@code __entity}. The
         * texture cache is cleared with it, because the same render type on the next entity is a
         * different material - the pin is only re-derived when the cache is empty.
         */
        void beginEntity(String label) {
            this.naming.currentBlockLabel = label == null || label.isEmpty() ? "entity" : label;
            this.naming.setBlock(null);
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) {
            try {
                this.naming.getBuffer(type);
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.debug("region: entity render type unreadable: {}",
                    t.toString());
            }
            return new PosedConsumer(this.collector, this.transform);
        }
    }

    /**
     * Applies the pose an entity model hands over, then the space transform, then records.
     *
     * <p>The collector's own {@code addVertex(Matrix4f, ...)} deliberately ignores its pose,
     * because on the block paths the pose is already baked into the numbers. An entity model is
     * the other case: it does not apply the stack itself and expects the consumer to, so this
     * wrapper is what makes an arm swing or a bone rotation visible in the export at all.
     */
    private static final class PosedConsumer implements VertexConsumer {
        private final Collector sink;
        private final Matrix4f transform;
        private final Matrix3f normal = new Matrix3f();

        PosedConsumer(Collector sink, Matrix4f transform) {
            this.sink = sink;
            this.transform = transform;
            this.normal.set(transform);
        }

        private org.joml.Vector3f place(Matrix4f pose, float x, float y, float z) {
            org.joml.Vector3f point = new org.joml.Vector3f(x, y, z);
            if (pose != null) {
                pose.transformPosition(point);
            }
            this.transform.transformPosition(point);
            return point;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            org.joml.Vector3f point = place(null, x, y, z);
            this.sink.addVertex(point.x, point.y, point.z);
            return this;
        }

        @Override
        public VertexConsumer addVertex(Matrix4f pose, float x, float y, float z) {
            org.joml.Vector3f point = place(pose, x, y, z);
            this.sink.addVertex(point.x, point.y, point.z);
            return this;
        }

        @Override
        public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
            return addVertex(pose == null ? null : pose.pose(), x, y, z);
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.sink.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.sink.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.sink.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.sink.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            org.joml.Vector3f value = new org.joml.Vector3f(x, y, z);
            this.normal.transform(value);
            this.sink.setNormal(value.x, value.y, value.z);
            return this;
        }

        @Override
        public VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) {
            org.joml.Vector3f value = new org.joml.Vector3f(x, y, z);
            if (pose != null) {
                pose.transformNormal(value, value);
            }
            this.normal.transform(value);
            this.sink.setNormal(value.x, value.y, value.z);
            return this;
        }
    }

    /**
     * Block ids whose renderer is left out of the export, read from a list beside the config.
     *
     * <p>These are the shapes drawn by code rather than described by a model: a track's links, a
     * tyre, a cannon barrel. They come through as one textured piece per link, all sharing the
     * block atlas - which cannot be copied out of the GPU - so the pieces are untextured and
     * there are hundreds of them. Modelling them by hand from the mod's own models gives a
     * better result, so they are skipped.
     *
     * <p>The list is explicit rather than a pattern, and that is deliberate. A first attempt
     * matched on names and would have excluded 2131 blocks, among them every railway track in
     * {@code railways} (320 of them, which are rails, not caterpillar tracks), Create's water
     * wheels and crushing wheels, and Chipped's wooden barrels. A pattern cannot tell a
     * caterpillar track from a railway track or a wheel from a water wheel, and silently
     * removing geometry is worse than leaving a part in - a hole is only noticed much later.
     *
     * <p>The file is {@code config/sableblueprintexporter-exclude.txt}, one block id per line,
     * {@code #} for comments. It is created with a starting list on first run. Deleting it, or
     * setting {@code -Dsableexport.excludeParts=false}, includes everything again.
     */
    private static final String EXCLUDE_LIST_FILE = "sableblueprintexporter-exclude.txt";

    /**
     * The same list under this mod's own name, which is what a pack that never ran the standalone
     * exporter would look for. The old name is still read first, so a file the player already edited
     * keeps working; the new name is created with the starting list only when neither exists.
     */
    private static final String EXCLUDE_LIST_FILE_CURRENT = "firecontrolcompat-exclude.txt";

    /** Loaded once; the file does not change while the game runs. */
    private static java.util.Set<String> excludedBlockIds = null;

    /** A matrix holding only a block position, no rotation. */
    private static Matrix4f translationOf(BlockPos pos) {
        Matrix4f out = new Matrix4f();
        out.translate(pos.getX(), pos.getY(), pos.getZ());
        return out;
    }

    /**
     * How far a block-entity's geometry lands from the nearest block that exists.
     *
     * <p>The measurement that found the fault, kept because it is the one number that says
     * whether this transform is right without anyone looking at a render. A part drawn for a
     * block sits on or beside that block, so the distance from its vertices to the closest real
     * block position should be a block or two at most; a wrong one leaves them tens of blocks
     * away.
     *
     * <p>What it reported, and what settled the question: before the fix, geometry belonging to
     * sub-levels whose blocks span four units reached twenty-one, and the transformer had been
     * changed eight times without a comparison to judge against. After it, the sub-level holding
     * the tracks reports a median of 0.71 blocks over 8584 vertices.
     *
     * @return median and worst distance, or null when the sub-level has no block entities.
     */
    private static String blockAffinity(SchematicLevel w, java.util.List<float[]> vertices) {
        if (vertices.isEmpty()) {
            return null;
        }
        java.util.List<BlockPos> blocks = new java.util.ArrayList<>();
        BoundingBox bounds = w.getBounds();
        for (BlockPos local : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                                                     bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            BlockPos pos = local.offset(w.anchor);
            if (!w.getBlockState(pos).isAir()) {
                blocks.add(pos);
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        java.util.List<Float> distances = new java.util.ArrayList<>(vertices.size());
        for (float[] v : vertices) {
            float best = Float.MAX_VALUE;
            for (BlockPos b : blocks) {
                float dx = v[0] - (b.getX() + 0.5f);
                float dy = v[1] - (b.getY() + 0.5f);
                float dz = v[2] - (b.getZ() + 0.5f);
                float d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                }
            }
            distances.add((float) Math.sqrt(best));
        }
        java.util.Collections.sort(distances);
        float median = distances.get(distances.size() / 2);
        float worst = distances.get(distances.size() - 1);
        return String.format(java.util.Locale.ROOT,
                             "median=%.2f worst=%.2f blocks away over %d vertex/vertices",
                             median, worst, vertices.size());
    }

    /**
     * Block ids to leave out of the block-entity pass. Empty by default.
     *
     * <p>The list exists so a part whose export is not wanted can be dropped without a rebuild,
     * but the starting file is empty and that is deliberate. An earlier version shipped with
     * tracks, tyres and cannon barrels already in it, on the reasoning that they came out
     * untextured and were therefore not worth exporting - and that was the wrong conclusion.
     * Those parts are wanted: they are the wheels and tracks that get placed onto a vehicle
     * that has none. The fault was in how they were exported, not in their being exported.
     *
     * <p>Each face now carries the name of the block it came from, so a wheel and a track link
     * are told apart, and each connected piece is written as its own object. That is what makes
     * them usable, and it is what the exclusion was standing in for.
     */
    private static final String[] DEFAULT_EXCLUSIONS = {
        "# Block ids whose renderer should be left out of the export. One per line.",
        "# Comments start with #. Empty by default - nothing is excluded.",
        "#",
        "# Example: to skip the track mod's blocks, uncomment these.",
        "# tracks:track_mount",
        "# tracks:suspension_track",
        "# tracks:track_drive_wheel",
    };

    private static java.util.Set<String> excludedBlockIds() {
        if (excludedBlockIds != null) {
            return excludedBlockIds;
        }
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        // The game's working directory is the instance directory, so "config" resolves to the
        // same folder the rest of the instance uses. Reading it this way avoids depending on a
        // loader-specific class, which differs between Forge and NeoForge.
        // Either name counts as "the player already has this file": the standalone exporter's name
        // first (so an edited list is never ignored), then this mod's own name, and only when both
        // are absent is the starting list written - under the current name.
        java.nio.file.Path path = java.nio.file.Path.of("config", EXCLUDE_LIST_FILE);
        java.nio.file.Path current = java.nio.file.Path.of("config", EXCLUDE_LIST_FILE_CURRENT);
        if (!java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.isRegularFile(current)) {
            path = current;
        }
        if (!java.nio.file.Files.isRegularFile(path)) {
            path = current;
            try {
                java.nio.file.Files.write(path, java.util.List.of(DEFAULT_EXCLUSIONS));
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.warn("could not write {}: {}", path, t.toString());
            }
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(path)) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                ids.add(text);
            }
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("could not read {}: {}", path, t.toString());
        }
        excludedBlockIds = ids;
        if (DIAGNOSTICS) {
            BlueprintExporter.LOGGER.info(
                "DIAG parts excluded from block-entity export: {} id(s) from {}", ids.size(), path);
        }
        return ids;
    }

    private static boolean excludedFromExport(BlockEntity be) {
        if (!EXCLUDE_PARTS) {
            return false;
        }
        try {
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(be.getBlockState().getBlock());
            return excludedBlockIds().contains(id.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The material a block entity's faces should carry: named after the block, not the atlas.
     *
     * <p>Every block-entity renderer draws against the block atlas, so the render type says the
     * same thing for all of them - "the atlas" - and grouping by it puts a wheel, a track link
     * and a barrel under one name. That makes them impossible to tell apart: 4681 of fk2000's
     * faces were one group, with no way to ask which of them were tyres.
     *
     * <p>The block's own id is the thing that distinguishes them, and it is available here. The
     * texture is still recorded separately, so the .mtl can name a file for it when one can be
     * written.
     */
    /**
     * The material for one face drawn by a block-entity renderer.
     *
     * <p>Named after the block <em>and</em> the texture, not the block alone.
     *
     * <p>A renderer usually draws several partial models, each with its own render type and so its
     * own texture. Naming them all after the block put them under one key, so each one's texture
     * overwrote the last and only one survived. Measured on the [tracks] mod's `track_mount`: the
     * belt and the suspension mount are drawn by the same renderer, the exported material ended up
     * pointing at `suspension_mount.png`, and the belt - which the mod's own
     * `TracksSpriteShifts.BELT` names as `tracks:block/belt` - was lost entirely. That is why a
     * track came out with a wheel mount's texture on it.
     *
     * <p>The namespace and path of the texture are folded in, so the belt gets its own material
     * and both images are written out.
     */
    private static String blockEntityMaterialName(BlockEntity be, String texturePath) {
        String block = null;
        try {
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(be.getBlockState().getBlock());
            block = id.getNamespace() + "_" + id.getPath().replace('/', '_');
        } catch (Throwable ignored) {
            // Fall through to the texture-only name below.
        }
        if (texturePath == null || texturePath.isEmpty()) {
            return block != null ? block : materialNameFor(texturePath);
        }
        String texture = materialNameFor(texturePath);
        return block == null ? texture : block + "__" + texture;
    }

    /**
     * A material name for a texture, in the same shape the model path produces.
     *
     * <p>Matches {@code setSprite}'s naming so a block-entity face and a model face that share
     * a texture end up in one material rather than two.
     */
    /** The three transparency classes a material can belong to. */
    private enum Layer {
        SOLID("M_solid"),
        CUTOUT("M_cutout"),
        TRANSLUCENT("M_translucent");

        final String prefix;

        Layer(String prefix) {
            this.prefix = prefix;
        }
    }

    /**
     * One texture's alpha verdict: its layer, the mean alpha of its painted texels, and the share
     * of texels that are fully clear.
     *
     * <p>The third field exists for the cutout mask line: a pane or a fence rail keeps its holes
     * because {@code map_d} points at a binarised sheet, and "how much of the sheet is a hole" is
     * what tells a real mask from one that came out solid.</p>
     */
    private record Alpha(Layer layer, float mean, float holes) {
        /** A verdict with no transparent texels: the answer for a texture that cannot be read. */
        Alpha(Layer layer, float mean) {
            this(layer, mean, 0f);
        }
    }

    /**
     * The dissolve written for a material whose transparency comes from its render layer.
     *
     * <p>A create fluid tank's casing is drawn on the translucent sheet, but its texture is
     * painted at alpha 1.0 everywhere: the transparency in game comes from the RenderType's
     * blend, and an OBJ/MTL has no RenderType at all. A viewer that reads only the image
     * therefore draws that casing opaque, which is the "glass is opaque in Blender" report.
     * Such a material gets this constant written for it instead of its measured alpha.</p>
     */
    private static final float FORCED_ALPHA_D = 0.6f;

    /** {@link #FORCED_ALPHA_D} as the 0..255 alpha stored in the {@code _a.png} copy (0.6 * 255). */
    private static final int FORCED_ALPHA_BYTE = 153;

    /**
     * How far a translucent material's {@code d} may sit from its measured alpha.
     *
     * <p>Every translucent material now gets a {@code d}, not only the ones whose sheet is painted
     * fully opaque. A sheet that averages 0.97 would otherwise be written {@code d 0.970}, which is
     * opaque in everything but name - and that is what made stained glass and ice import solid in
     * Blender, whose own OBJ importer never reads the alpha channel of a {@code map_Kd} image. The
     * floor keeps a faint sheet from vanishing instead of reading as glass.</p>
     */
    private static final float TRANSLUCENT_D_MIN = 0.35f;
    private static final float TRANSLUCENT_D_MAX = 0.95f;

    /**
     * The {@code d} one translucent material is written with.
     *
     * <p>One function for every place that needs the number - the .mtl body, the per-material log
     * and the summary log - so no two of them can disagree about it.</p>
     */
    private static float translucentD(Alpha alpha) {
        float mean = alpha == null ? 1.0f : alpha.mean();
        if (mean >= FORCED_ALPHA_THRESHOLD) {
            // Painted fully opaque: the layer is the only thing saying glass, so the constant is
            // the whole message (and is what the previous forced-alpha rule established the look of).
            return FORCED_ALPHA_D;
        }
        return Math.max(TRANSLUCENT_D_MIN, Math.min(TRANSLUCENT_D_MAX, mean));
    }

    /** {@code forced} or {@code texture}: where a translucent material's {@code d} came from. */
    private static String translucentDSource(Alpha alpha) {
        return alpha != null && alpha.mean() >= FORCED_ALPHA_THRESHOLD ? "forced" : "texture";
    }

    /**
     * A translucent material painted at least this opaque is layer-transparent, not
     * image-transparent. 0.98 leaves room for a stray near-opaque texel in an otherwise solid
     * texture, while every material that blends through its own pixels - stained glass, ice,
     * tinted panes - sits far below it and is left alone.
     */
    private static final float FORCED_ALPHA_THRESHOLD = 0.98f;

    /** Classified once per texture path; an export asks about the same handful many times. */
    private static final java.util.Map<String, Alpha> TEXTURE_ALPHA = new java.util.HashMap<>();

    /**
     * Which layer a texture belongs to, from its own pixels.
     *
     * <p>Decided from the image rather than from a {@code RenderType}, because the render type is
     * not always recoverable while the PNG behind a material always is - and "is this glass" is a
     * property of the image anyway. A single partial-alpha texel is enough to call a texture
     * translucent: that is what stained glass and ice look like, while a fence or a pane is either
     * fully painted or fully clear.</p>
     */
    private static Alpha textureAlpha(String texture) {
        if (texture == null || texture.isEmpty()) {
            return new Alpha(Layer.SOLID, 1.0f);
        }
        Alpha cached = TEXTURE_ALPHA.get(texture);
        if (cached != null) {
            return cached;
        }
        Alpha verdict = new Alpha(Layer.SOLID, 1.0f);
        try {
            java.io.InputStream stream = openTexture(texture);
            if (stream != null) {
                try (stream) {
                    java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(stream);
                    if (image != null) {
                        boolean alphaChannel = image.getColorModel().hasAlpha();
                        boolean transparent = false;
                        boolean partial = false;
                        long sum = 0L;
                        long painted = 0L;
                        long clear = 0L;
                        long total = 0L;
                        for (int y = 0; y < image.getHeight(); y++) {
                            for (int x = 0; x < image.getWidth(); x++) {
                                int alpha = alphaChannel ? (image.getRGB(x, y) >>> 24) & 0xFF : 255;
                                total++;
                                if (alpha < 8) {
                                    transparent = true;
                                    clear++;
                                } else if (alpha < 247) {
                                    partial = true;
                                    sum += alpha;
                                    painted++;
                                } else {
                                    sum += alpha;
                                    painted++;
                                }
                            }
                        }
                        Layer layer = partial ? Layer.TRANSLUCENT
                            : (transparent ? Layer.CUTOUT : Layer.SOLID);
                        float mean = painted == 0L ? 1.0f : (float) sum / (float) painted / 255.0f;
                        float holes = total == 0L ? 0.0f : (float) clear / (float) total;
                        verdict = new Alpha(layer, Math.max(0.02f, Math.min(1.0f, mean)),
                            Math.max(0.0f, Math.min(1.0f, holes)));
                    }
                }
            }
        } catch (Throwable ignored) {
            // an unreadable texture is treated as opaque: a wrong `d` is worse than none
        }
        TEXTURE_ALPHA.put(texture, verdict);
        return verdict;
    }

    /** The layer of a texture, from its own alpha channel. */
    private static Layer textureLayerOf(String texture) {
        return textureAlpha(texture).layer;
    }

    /**
     * What the render type says the material is.
     *
     * <p>This is the game's own answer and it has to be the primary one: plain glass's texture is
     * fully clear or fully opaque, so its alpha channel says "cutout" while the game draws it on the
     * translucent sheet. A mod render type is recognised by name; anything unrecognised is solid,
     * which is the safe direction (an opaque material is never worse than one that blends wrongly).</p>
     */
    private static Layer layerOfRenderType(RenderType type) {
        if (type == null) {
            return Layer.SOLID;
        }
        try {
            if (type == RenderType.translucent() || type == RenderType.translucentMovingBlock()) {
                return Layer.TRANSLUCENT;
            }
            if (type == RenderType.cutout() || type == RenderType.cutoutMipped()) {
                return Layer.CUTOUT;
            }
            if (type == RenderType.solid()) {
                return Layer.SOLID;
            }
        } catch (Throwable ignored) {
            // a render type whose sheet cannot be compared: fall through to the name
        }
        String name = String.valueOf(type).toLowerCase(java.util.Locale.ROOT);
        if (name.contains("translucent")) {
            return Layer.TRANSLUCENT;
        }
        if (name.contains("cutout")) {
            return Layer.CUTOUT;
        }
        return Layer.SOLID;
    }

    /** The stronger of two layers: the render type and the texture can only add transparency. */
    private static Layer strongest(Layer first, Layer second) {
        if (first == null) {
            return second == null ? Layer.SOLID : second;
        }
        if (second == null) {
            return first;
        }
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    /** The layer of a texture alone, for material names when no render type was seen. */
    private static Layer layerOf(String texture) {
        return strongest(layerHintLocal.get(), textureLayerOf(texture));
    }

    /** The render type of the material currently being emitted, set by the model and capture passes. */
    private static final ThreadLocal<Layer> layerHintLocal =
        ThreadLocal.withInitial(() -> Layer.SOLID);

    /** Records what the render type of the next quads says. */
    private static void hintRenderType(RenderType type) {
        layerHintLocal.set(layerOfRenderType(type));
    }

    /** The layer a material belongs to, read back from its own name. */
    private static Layer layerFromName(String material) {
        if (material == null) {
            return Layer.SOLID;
        }
        if (material.startsWith(Layer.TRANSLUCENT.prefix)) {
            return Layer.TRANSLUCENT;
        }
        if (material.startsWith(Layer.CUTOUT.prefix)) {
            return Layer.CUTOUT;
        }
        return Layer.SOLID;
    }

    /**
     * The one layer verdict for a material: what its faces were filed under when the material was
     * chosen, never a second guess at the name.
     *
     * <p>The name is only a fallback for the case where a material reached the writer without a
     * single face, which should not happen - and is worth a warning rather than a silent guess.</p>
     */
    private static Layer layerOfMaterial(String material, Map<String, Layer> layers) {
        Layer recorded = material == null ? null : layers.get(material);
        return recorded != null ? recorded : layerFromName(material);
    }

    /** The layer a material belongs to, given what its render type and its texture say. */
    private static Layer layerFor(String texture) {
        return strongest(layerHintLocal.get(), textureLayerOf(texture));
    }

    /** The texture's stream, trying the same path shapes a model may have written. */
    private static java.io.InputStream openTexture(String texture) {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null) {
                return null;
            }
            net.minecraft.resources.ResourceLocation base =
                net.minecraft.resources.ResourceLocation.tryParse(texture);
            if (base == null) {
                return null;
            }
            String path = base.getPath();
            boolean hasPng = path.toLowerCase(java.util.Locale.ROOT).endsWith(".png");
            boolean hasTextures = path.startsWith("textures/");
            java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
            for (String prefix : hasTextures ? new String[] {""} : new String[] {"textures/", ""}) {
                for (String suffix : hasPng ? new String[] {""} : new String[] {".png", ""}) {
                    candidates.add(prefix + path + suffix);
                }
            }
            for (String candidate : candidates) {
                var location = net.minecraft.resources.ResourceLocation.tryParse(
                    base.getNamespace() + ":" + candidate);
                if (location == null) {
                    continue;
                }
                var resource = minecraft.getResourceManager().getResource(location);
                if (!resource.isEmpty()) {
                    return resource.get().open();
                }
            }
        } catch (Throwable ignored) {
            // no resource manager yet: opaque
        }
        return null;
    }

    /**
     * The material name: layer first, then the namespace and path.
     *
     * <p>{@code M_translucent_minecraft_block_glass} tells whoever opens the .mtl what to expect
     * before they look at a single line of it, which is the point - the report was "glass exports
     * opaque" and the name now says glass, not just a texture path.</p>
     */
    private static String materialNameFor(String texture) {
        return materialNameFor(texture, layerFor(texture));
    }

    /**
     * The material name for a texture in a layer that has already been decided.
     *
     * <p>The layer is passed in rather than recomputed so that the name and the {@code d}/{@code Tr}
     * lines written from it cannot disagree: a material is named {@code M_translucent_...} exactly
     * when the writer treats it as translucent.</p>
     */
    private static String materialNameFor(String texture, Layer layer) {
        Layer resolved = layer == null ? Layer.SOLID : layer;
        if (texture == null || texture.isEmpty()) {
            // A renderer with no recoverable texture still needs a name; the layer prefix keeps it
            // in the right .mtl block instead of dumping it among the opaque ones.
            return resolved.prefix + "_untextured";
        }
        int colon = texture.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : texture.substring(0, colon);
        String path = colon < 0 ? texture : texture.substring(colon + 1);
        return resolved.prefix + "_" + namespace + "_" + path.replace('/', '_');
    }

    /**
     * The texture a {@link RenderType} draws with, if it can be recovered.
     *
     * <p>A block-entity renderer never states which texture it uses: it asks the buffer source
     * for a {@code RenderType} and writes vertices. That render type is the only remaining
     * evidence, and its texture sits in a private field of a private nested
     * {@code RenderStateShard} with no public accessor in 1.21.1 - {@code TextureStateShard}
     * exposes {@code cutoutTexture()}, but the shard is not reachable from {@code RenderType}
     * through the API.
     *
     * <p>So it is read reflectively, and defensively: any failure returns null and the caller
     * falls back to an untextured material. Nothing is guessed at - only a shard's own declared
     * location is used - because a wrong texture would be worse than none.
     *
     * <p>Without it every block-entity face landed under one untextured material: 3997 of
     * pgz-09's 7544 faces, which is the "unknown white model" that was reported.
     */
    private static String renderTypeTexture(RenderType type) {
        if (type == null) {
            return null;
        }
        try {
            // `state` is a METHOD, not a field. That is what the first version of this got
            // wrong: it walked the class hierarchy looking for a field called `state` or
            // `renderState`, found neither, and returned null every time - so every block-entity
            // face fell back to the atlas name and none of them could ever be textured.
            //
            // The shape of the API, read from the class files rather than guessed:
            //
            //   RenderType$CompositeRenderType   package-private class
            //       protected final CompositeState state()          <- the accessor
            //   RenderType$CompositeState        public class
            //       final EmptyTextureStateShard textureState        <- package-private field
            //   RenderStateShard$TextureStateShard  public class
            //       protected Optional<ResourceLocation> cutoutTexture()
            //
            // All three are reachable only by reflection, because the first is package-private
            // and the other two are protected members of a package this mod does not own. The
            // same construction appears in MineToMesh's RenderTypeInspector, which reads
            // `composite.state().textureState.cutoutTexture()` directly - it can, because it
            // declares its reader in a class that Minecraft's access rules allow.
            Object state = null;
            for (Class<?> c = type.getClass(); c != null && state == null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method accessor = c.getDeclaredMethod("state");
                    accessor.setAccessible(true);
                    state = accessor.invoke(type);
                } catch (NoSuchMethodException | IllegalAccessException ignored) {
                    // Next class up the chain, or no state at all.
                }
            }
            if (state == null) {
                if (DIAGNOSTICS) {
                    BlueprintExporter.LOGGER.info(
                        "DIAG render type {} exposes no state; its texture cannot be named",
                        type);
                }
                return null;
            }

            // The texture shard is the field whose type can answer cutoutTexture.
            for (java.lang.reflect.Field field : state.getClass().getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(state);
                    if (value == null) {
                        continue;
                    }
                    java.lang.reflect.Method method;
                    try {
                        // getDeclaredMethod, not getMethod: cutoutTexture is protected, so a
                        // public lookup does not find it and every shard would be skipped.
                        method = value.getClass().getDeclaredMethod("cutoutTexture");
                    } catch (NoSuchMethodException notATextureShard) {
                        continue;
                    }
                    method.setAccessible(true);
                    Object result = method.invoke(value);
                    if (result instanceof java.util.Optional<?> optional && optional.isPresent()
                            && optional.get() instanceof net.minecraft.resources.ResourceLocation id) {
                        return id.getNamespace() + ":" + id.getPath();
                    }
                } catch (Throwable ignored) {
                    // A shard that cannot be read is skipped; the others are still tried.
                }
            }
        } catch (Throwable t) {
            if (DIAGNOSTICS) {
                BlueprintExporter.LOGGER.debug(
                    "could not read the texture of render type {}: {}", type, t.toString());
            }
        }
        return null;
    }

    /**
     * A {@code MultiBufferSource} that hands every render type to one collector.
     *
     * <p>Written directly rather than using {@code MultiBufferSource.BufferSource}, whose
     * constructor is protected and whose whole job is batching through byte buffers for the
     * GPU. Nothing here reaches a GPU: the vertices are wanted as numbers.
     *
     * <p>The render type matters even so, because it is the only statement a block-entity
     * renderer makes about which texture it is drawing with. Its location becomes the
     * material, so a barrel's faces end up in the .mtl under a name whose image can actually
     * be copied out.
     */
    private static final class BlockEntityCapture
            implements net.minecraft.client.renderer.MultiBufferSource {
        private final Collector collector;
        /** One lookup per render type rather than per vertex. */
        private final java.util.Map<RenderType, String> textures = new java.util.HashMap<>();
        /** Every render type this capture was asked for, named, for the log line. */
        final java.util.LinkedHashSet<String> usedRenderTypes = new java.util.LinkedHashSet<>();

        BlockEntityCapture(Collector collector) {
            this.collector = collector;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) {
            if (type != null) {
                hintRenderType(type);
                usedRenderTypes.add(String.valueOf(type));
                // Which render types this block drew with, for the `capture-scope: block` line.
                // The same string `usedRenderTypes` keeps, so the two counts always agree.
                CaptureScope.noteRenderType(String.valueOf(type));
                // The block's own sprite first: the render type can only offer the atlas, whose
                // pixels cannot be copied. See spritePathForBlock.
                String texture = textures.computeIfAbsent(type,
                    t -> spritePathForBlock(currentState, t));
                // One verdict, used for both the name and the .mtl body: max(render type, texture
                // alpha). Computing it twice is what allowed a "M_translucent_..." name to be
                // written with `d 1.0`.
                Layer layer = layerFor(texture);
                String name = materialNameFor(texture);
                if (currentBlockLabel != null) {
                    // Layer prefix first, block after it, so the name still says which block this
                    // piece belongs to AND can be classified on sight - `layerFromName` only looks
                    // at the start of the name, which is where the prefix has to be.
                    name = name + "__" + currentBlockLabel;
                }
                collector.pinMaterial(name, texture, layer, currentBlockLabel);
            }
            return collector;
        }

        /** Called before each block entity is rendered. */
        void setBlock(net.minecraft.world.level.block.state.BlockState state) {
            this.currentState = state;
            this.textures.clear();
        }

        /** Set before each block entity is rendered, so its faces can be named after it. */
        private String currentBlockLabel = "entity_be";

        /** The block being drawn, for the sprite lookup below. */
        private net.minecraft.world.level.block.state.BlockState currentState;
    }

    /**
     * The sprite the game would draw this block's own model with, as a file path.
     *
     * <p>This is the answer to a problem that has resisted several approaches. A block-entity
     * renderer draws against the block atlas, and the render type says so - its texture is
     * `minecraft:atlas/blocks` - but an atlas is stitched in memory and has no file on disk, so
     * nothing can be copied out of it. Measured on a fresh export: the track and wheel parts,
     * 3532 faces of them, came out with no image at all for exactly this reason, while the
     * ordinary model faces were fine.
     *
     * <p>A block's own model is the way in. Its quads carry sprites, and each sprite knows the
     * texture it was stitched from - `tracks:block/belt` - which IS a file. Looking the block's
     * model up, taking its first quad's sprite, and using that sprite's name gives a path that
     * can be copied, and it is the right picture: a renderer drawing a track mount is drawing it
     * in the track mount's own texture.
     *
     * <p>Falls back to the render type's texture, so a renderer that does name a real texture
     * still gets it.
     */
    private static String spritePathForBlock(
            net.minecraft.world.level.block.state.BlockState state, RenderType type) {
        if (state != null) {
            try {
                // `ModelManager.getModel` takes a ModelResourceLocation, not a BlockState, so the
                // state is mapped through the block model shaper first - the same route the
                // renderer takes to find the model it draws.
                var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
                var model = shaper.getBlockModel(state);
                for (var direction : net.minecraft.core.Direction.values()) {
                    var quads = model.getQuads(state, direction, net.minecraft.util.RandomSource
                        .create(0L));
                    if (quads.isEmpty()) {
                        continue;
                    }
                    var sprite = quads.get(0).getSprite();
                    if (sprite != null && sprite.contents() != null) {
                        var id = sprite.contents().name();
                        return id.getNamespace() + ":" + id.getPath();
                    }
                }
                // A model with no directional quads still has a particle sprite, which is the
                // block's own texture in every case that matters.
                var particle = model.getParticleIcon();
                if (particle != null && particle.contents() != null) {
                    var id = particle.contents().name();
                    return id.getNamespace() + ":" + id.getPath();
                }
            } catch (Throwable t) {
                if (DIAGNOSTICS) {
                    BlueprintExporter.LOGGER.debug(
                        "could not read the sprite of {}: {}", state, t.toString());
                }
            }
        }
        return renderTypeTexture(type);
    }

    /**
     * Emit one BakedQuad's four corners, in the order the quad lists them.
     *
     * <p>Deliberately not {@code VertexConsumer.putBulkData}. That default method unpacks
     * the quad's packed vertex data into a four-by-eight int buffer and rebuilds the
     * corners from it in an order of its own choosing, which is fine for the flat
     * axis-aligned faces the chunk renderer feeds it and wrong for anything else: a
     * model whose quad is not in that canonical order comes out as a bow-tie, with two
     * opposite corners exchanged. Blender then measures zero area for it and draws a
     * spike.
     *
     * <p>The measurement that found it, from Blender against the exported file:
     * {@code create_block_andesite_casing} - a cube - had four of its six faces at zero
     * area, and {@code create_block_axis} had all eight. Reading the packed data here and
     * sending the corners in file order keeps the winding the model authored.
     */
    /**
     * The atlas tile whose region contains this coordinate.
     *
     * <p>An atlas coordinate maps to exactly one tile, but no API offers that direction: the atlas
     * is keyed by texture location. So a candidate list is built and the tile whose bounds contain
     * the coordinate wins.
     *
     * <p>Two mistakes this version corrects, both measured. The first version did not deduplicate
     * and reached 3,834,703 candidates, which would have hung the export. It also resolved tiles
     * through `Minecraft.getTextureAtlas`, whose tiles are 1/512 placeholders carrying a wrong
     * name - every tile it "found" was one of those. Here the names are deduplicated, the cap is
     * enforced, and each name is resolved with `TextureAtlas.getSprite`, which returns a real tile.
     */
    /**
     * The one atlas tile a coordinate belongs to.
     *
     * <p>Some renderers hand over a coordinate that sits exactly on a tile <b>corner</b> - a copycat
     * sail's UVs land on its material's {@code u0/v0} - and four tiles then contain that point inside
     * the tolerance. Returning whichever was enumerated first made the choice depend on hash order,
     * which is why the user's export log resolved an orange-concrete sail to a 面包学 light-pod tile:
     * the conversion then divided by the wrong span and the face sampled the wrong image.</p>
     *
     * <p>So the choice is made deterministic and specific: among the containing tiles take the
     * <b>smallest</b> one (the most specific match), then the one whose centre is nearest the point,
     * then the lowest name. A corner therefore always resolves to the tile it is the corner of, and
     * two exports of the same ship can no longer disagree.</p>
     */
    /** Whether an atlas coordinate sits inside a sprite's own rectangle. */
    private static boolean inside(
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, float u, float v) {
        if (sprite == null) {
            return false;
        }
        float spanU = sprite.getU1() - sprite.getU0();
        float spanV = sprite.getV1() - sprite.getV0();
        if (spanU <= 0f || spanV <= 0f) {
            return false;
        }
        return u >= sprite.getU0() - 1e-4f && u <= sprite.getU1() + 1e-4f
            && v >= sprite.getV0() - 1e-4f && v <= sprite.getV1() + 1e-4f;
    }

    private static net.minecraft.client.renderer.texture.TextureAtlasSprite spriteContaining(
            float u, float v) {        net.minecraft.client.renderer.texture.TextureAtlasSprite best = null;
        double bestSpan = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        String bestName = null;
        for (var sprite : atlasCandidates()) {
            if (sprite == null || sprite.contents() == null) {
                continue;
            }
            float spanU = sprite.getU1() - sprite.getU0();
            float spanV = sprite.getV1() - sprite.getV0();
            if (spanU <= 0f || spanV <= 0f) {
                continue;
            }
            if (u < sprite.getU0() - 1e-4f || u > sprite.getU1() + 1e-4f
                    || v < sprite.getV0() - 1e-4f || v > sprite.getV1() + 1e-4f) {
                continue;
            }
            double span = (double) spanU * spanV;
            double centreU = ((double) sprite.getU0() + sprite.getU1()) * 0.5;
            double centreV = ((double) sprite.getV0() + sprite.getV1()) * 0.5;
            double distance = (centreU - u) * (centreU - u) + (centreV - v) * (centreV - v);
            String name = sprite.contents().name().toString();
            boolean better;
            if (best == null) {
                better = true;
            } else if (Math.abs(span - bestSpan) > 1e-12) {
                better = span < bestSpan;
            } else if (Math.abs(distance - bestDistance) > 1e-12) {
                better = distance < bestDistance;
            } else {
                better = name.compareTo(bestName) < 0;
            }
            if (better) {
                best = sprite;
                bestSpan = span;
                bestDistance = distance;
                bestName = name;
            }
        }
        return best;
    }

    /**
     * How many textures to resolve into tiles.
     *
     * <p>The cap has to be generous, and the first value tried was not. At 6000 the log reported
     * `tile candidates: 6000 (limit 6000)` - the list was truncated, and the tile that was wanted
     * was not in it, so the lookup matched some unrelated sprite instead. Enumeration order is not
     * controlled, so a cap that is hit is a cap that silently loses the answer.
     *
     * <p>The cost is one `getSprite` call per texture, which is a hash lookup, and it happens once
     * per export. Fifty thousand is far above the number of block textures any pack carries.
     */
    private static final int ATLAS_CANDIDATE_LIMIT = 50000;

    /**
     * Smallest span a real atlas tile can have.
     *
     * <p>A {@code getTextureAtlas} lookup answers one-texel placeholders whose span measured 1/512;
     * a real 16x16 tile on the largest atlas this pack builds (8192) is 1/512 wide as well, so the
     * test is a floor rather than a comparison: anything at or above it is a real tile and anything
     * below it is the placeholder. Chosen at 1/256 so a 16x16 tile on a 16384 atlas still counts as
     * real while a single texel never does.</p>
     */
    private static final float PLACEHOLDER_SPAN = 1f / 256f;

    /** Every tile that could be named, collected once, deduplicated by name. */
    private static java.util.List<net.minecraft.client.renderer.texture.TextureAtlasSprite>
            atlasCandidates() {
        if (ATLAS_CANDIDATES != null) {
            return ATLAS_CANDIDATES;
        }
        java.util.List<net.minecraft.client.renderer.texture.TextureAtlasSprite> out =
            new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        var minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            ATLAS_CANDIDATES = out;
            return out;
        }
        // The real atlas, through the texture manager. `getTextureAtlas` would give placeholders.
        net.minecraft.client.renderer.texture.TextureAtlas atlas = null;
        try {
            var texture = minecraft.getTextureManager().getTexture(
                net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            if (texture instanceof net.minecraft.client.renderer.texture.TextureAtlas real) {
                atlas = real;
            }
        } catch (Throwable t) {
            if (DIAGNOSTICS) {
                BlueprintExporter.LOGGER.warn("no block atlas for the tile lookup: {}",
                                              t.toString());
            }
        }
        if (atlas == null) {
            ATLAS_CANDIDATES = out;
            return out;
        }

        // Every texture file the resource manager offers under textures/block. This is what finds
        // a texture that only a mod's code names.
        try {
            java.util.Map<net.minecraft.resources.ResourceLocation,
                         net.minecraft.server.packs.resources.Resource> names =
                minecraft.getResourceManager().listResources("textures/block",
                    path -> path.getPath().endsWith(".png"));
            for (var name : names.keySet()) {
                if (out.size() >= ATLAS_CANDIDATE_LIMIT) {
                    break;
                }
                String path = name.getPath();
                int start = path.indexOf("textures/") + "textures/".length();
                if (start < "textures/".length() || path.length() <= start) {
                    continue;
                }
                String trimmed = path.substring(start, path.length() - ".png".length());
                String key = name.getNamespace() + ":" + trimmed;
                if (!seen.add(key)) {
                    continue;
                }
                addTile(atlas, key, out);
            }
        } catch (Throwable t) {
            if (DIAGNOSTICS) {
                BlueprintExporter.LOGGER.warn(
                    "could not enumerate block textures for the tile lookup: {}", t.toString());
            }
        }

        // Every block's own sprite, which catches textures registered without a file under the
        // usual path.
        try {
            var shaper = minecraft.getModelManager().getBlockModelShaper();
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                if (out.size() >= ATLAS_CANDIDATE_LIMIT) {
                    break;
                }
                for (var state : block.getStateDefinition().getPossibleStates()) {
                    if (out.size() >= ATLAS_CANDIDATE_LIMIT) {
                        break;
                    }
                    try {
                        var model = shaper.getBlockModel(state);
                        if (model == null) {
                            continue;
                        }
                        // The sprite is only taken if its own name is new, which is what keeps
                        // this bounded - the previous version appended the same sprite for every
                        // state and reached millions of entries.
                        var particle = model.getParticleIcon();
                        if (particle != null && particle.contents() != null
                                && seen.add(particle.contents().name().toString())) {
                            out.add(particle);
                        }
                        for (var direction : net.minecraft.core.Direction.values()) {
                            var quads = model.getQuads(state, direction,
                                net.minecraft.util.RandomSource.create(0L));
                            for (var quad : quads) {
                                var sprite = quad.getSprite();
                                if (sprite != null && sprite.contents() != null
                                        && seen.add(sprite.contents().name().toString())) {
                                    out.add(sprite);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // One odd state must not stop the scan.
                    }
                }
            }
        } catch (Throwable t) {
            if (DIAGNOSTICS) {
                BlueprintExporter.LOGGER.warn(
                    "could not enumerate block models for the tile lookup: {}", t.toString());
            }
        }

        ATLAS_CANDIDATES = out;
        if (DIAGNOSTICS) {
            BlueprintExporter.LOGGER.info("DIAG tile candidates: {} (limit {})",
                                          out.size(), ATLAS_CANDIDATE_LIMIT);
        }
        return out;
    }

    /** Resolve one texture name to a real tile and add it, skipping placeholders. */
    private static void addTile(net.minecraft.client.renderer.texture.TextureAtlas atlas,
                                String key,
                                java.util.List<net.minecraft.client.renderer.texture
                                    .TextureAtlasSprite> out) {
        try {
            var id = net.minecraft.resources.ResourceLocation.tryParse(key);
            if (id == null) {
                return;
            }
            var sprite = atlas.getSprite(id);
            if (sprite == null || sprite.contents() == null) {
                return;
            }
            // A one-texel span is a placeholder, not a tile. Rejecting it here is what stops the
            // wrong name being reported as a find.
            if (sprite.getU1() - sprite.getU0() <= 1.001f / 512f) {
                return;
            }
            out.add(sprite);
        } catch (Throwable ignored) {
            // A texture that will not resolve is simply not a candidate.
        }
    }

    private static java.util.List<net.minecraft.client.renderer.texture.TextureAtlasSprite>
        ATLAS_CANDIDATES = null;

    private static void emitQuad(Collector collector, PoseStack pose,
                                 net.minecraft.client.renderer.block.model.BakedQuad quad) {
        // A model's quad carries atlas coordinates, so the conversion applies to it.
        collector.setUvNeedsAtlasConversion(true);
        int[] data = quad.getVertices();
        int stride = data.length / 4;
        // Buffered first, because a cutout or translucent face has to be written twice - once as
        // given and once reversed - and the second copy needs the same numbers.
        float[] px = new float[4];
        float[] py = new float[4];
        float[] pz = new float[4];
        float[] pu = new float[4];
        float[] pv = new float[4];
        float[] pnx = new float[4];
        float[] pny = new float[4];
        float[] pnz = new float[4];
        for (int corner = 0; corner < 4; corner++) {
            int base = corner * stride;
            float x = Float.intBitsToFloat(data[base]);
            float y = Float.intBitsToFloat(data[base + 1]);
            float z = Float.intBitsToFloat(data[base + 2]);
            float u = Float.intBitsToFloat(data[base + 4]);
            float v = Float.intBitsToFloat(data[base + 5]);
            long normal = data[base + 7] & 0xFFFFFFFFL;
            float nx = ((byte) (normal & 0xFF)) / 127.0f;
            float ny = ((byte) ((normal >> 8) & 0xFF)) / 127.0f;
            float nz = ((byte) ((normal >> 16) & 0xFF)) / 127.0f;
            // The position is handed over raw. `collector.addVertex` applies the sub-level
            // frame itself, so applying the pose here as well - which is what this did while
            // the collector had no frame of its own - would place every face twice.
            org.joml.Vector3f point = new org.joml.Vector3f(x, y, z);
            org.joml.Vector3f direction = pose.last().transformNormal(
                new org.joml.Vector3f(nx, ny, nz), new org.joml.Vector3f());
            // Snapped to the nearest axis before it reaches the writer.
            //
            // Vertices are deduplicated on (position, uv, normal), so a normal differing
            // in the last decimal between two corners of the same face makes every corner
            // unique. Nothing then welds, and the mesh arrives as confetti: 3331 separate
            // components for 3547 faces, one connected piece per quad. A block face has
            // only six possible directions, so rounding here costs nothing and is what
            // keeps the surface continuous.
            px[corner] = point.x;
            py[corner] = point.y;
            pz[corner] = point.z;
            pu[corner] = u;
            pv[corner] = v;
            pnx[corner] = snapAxis(direction.x);
            pny[corner] = snapAxis(direction.y);
            pnz[corner] = snapAxis(direction.z);
        }
        for (int corner = 0; corner < 4; corner++) {
            collector.addVertex(px[corner], py[corner], pz[corner], -1, pu[corner], pv[corner],
                                0, 0, pnx[corner], pny[corner], pnz[corner]);
        }
        // The reverse copy of a cutout or translucent face is added by Collector.addFace, which is
        // where every face is built - including the ones a block-entity renderer writes, which this
        // path never sees.
    }

    /** Round a normal component to -1, 0 or 1, whichever it is nearest. */
    private static float snapAxis(float value) {
        if (value > 0.5f) {
            return 1.0f;
        }
        if (value < -0.5f) {
            return -1.0f;
        }
        return 0.0f;
    }

    /** Every sprite this model's quads use, as atlas id -> sprite. */
    private static Map<net.minecraft.resources.ResourceLocation, TextureAtlasSprite>
            spritesOf(BakedModel model, BlockState state, RandomSource random,
                      net.neoforged.neoforge.client.model.data.ModelData modelData) {
        Map<net.minecraft.resources.ResourceLocation, TextureAtlasSprite> out = new LinkedHashMap<>();
        var ext = (net.neoforged.neoforge.client.extensions.IBakedModelExtension) model;
        java.util.List<Direction> dirs = new java.util.ArrayList<>();
        for (Direction d : Direction.values()) {
            dirs.add(d);
        }
        dirs.add(null);
        for (Direction d : dirs) {
            try {
                for (var quad : ext.getQuads(state, d, random, modelData, null)) {
                    if (quad.getSprite() != null) {
                        out.putIfAbsent(quad.getSprite().atlasLocation(), quad.getSprite());
                    }
                }
            } catch (Throwable ignored) {
                // models that cannot enumerate quads fall back to the block atlas
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- write

    private static int write(Collector collector, String name) throws IOException {
        Path outDir = outputDir();
        Path obj = outDir.resolve(name + ".obj");
        Path mtl = outDir.resolve(name + ".mtl");

        // Material key = sprite, so one material per distinct texture.
        Map<String, List<Face>> byMaterial = new LinkedHashMap<>();
        Map<String, String> materialAtlas = new LinkedHashMap<>();
        // One layer verdict per material, taken from the faces that built it. This is the single
        // source the .mtl body, the sprite PNG and the summary log all read: the earlier code
        // recovered the layer from the material NAME, and a block-entity name such as
        // "cbcmoreshells_steel_ammo_rack__M_translucent_cbcmoreshells_block_..._console_bottom"
        // does not start with "M_translucent", so glass was exported with `d 1.0` under a name
        // that said translucent.
        Map<String, Layer> materialLayer = new LinkedHashMap<>();
        for (Face f : collector.faces) {
            byMaterial.computeIfAbsent(f.material, k -> new ArrayList<>()).add(f);
            materialAtlas.putIfAbsent(f.material, f.atlasPath);
            materialLayer.merge(f.material, f.layer, BlueprintBake::strongest);
        }

        // ...and one OBJECT per sub-level, not one per material.
        //
        // A sub-level is Sable's unit of physical structure - its own rigid body - so
        // grouping by it is what lets a skirt or a turret be selected and turned on its own
        // in Blender. Grouping only by material, as this did, merged every sub-level into
        // thirty-eight texture buckets and threw the structure away: the parts were all
        // present and none of them could be moved independently.
        //
        // Materials stay as `usemtl` groups inside the object rather than becoming objects
        // of their own, so each sub-level imports as exactly one mesh. The name carries no
        // spaces, because an OBJ `o` line is whitespace delimited and a material key like
        // "create:block/andesite" contains none but a display name might.
        Map<String, List<Face>> byObject = new LinkedHashMap<>();
        Map<Integer, String> objectNames = new LinkedHashMap<>();
        // Block-entity faces are collected first, so each sub-level's can be split into pieces
        // once rather than face by face.
        Map<Integer, List<Face>> blockEntityFaces = new LinkedHashMap<>();
        for (Face f : collector.faces) {
            if (f.blockEntitySubLevel >= 0) {
                blockEntityFaces.computeIfAbsent(f.subLevel, k -> new ArrayList<>()).add(f);
                continue;
            }
            // Model faces stay in one object per sub-level, which is Sable's own unit of
            // physical structure, so a hull arrives as a hull.
            String objectName = objectNames.computeIfAbsent(f.subLevel, BlueprintBake::objectName);
            byObject.computeIfAbsent(objectName, k -> new ArrayList<>()).add(f);
        }

        // Then the block entities become one object per piece.
        //
        // This is what makes a track usable and a barrel findable. A track is a chain of links
        // that touch, so it comes out as a single object that can be picked up whole; a cannon
        // or a barrel touches nothing, so it comes out on its own. Grouping by texture alone -
        // as this did - merged every part sharing a sprite into one anonymous mass, and
        // grouping by sub-level alone merged the tracks into the hull. Neither could be
        // adjusted.
        for (Map.Entry<Integer, List<Face>> entry : blockEntityFaces.entrySet()) {
            String prefix = objectNames.computeIfAbsent(entry.getKey(), BlueprintBake::objectName);
            Map<String, List<Face>> pieces = groupBlockEntityPieces(entry.getValue());
            // When one texture covers the lot - which is what happens when the renderers all
            // draw from the block atlas - the texture name says nothing and would only add a
            // long suffix to every object. The sub-level and its index are enough to identify
            // the piece, and a short name is what makes the outliner usable.
            boolean singleTexture = pieces.size() <= 1
                || entry.getValue().stream().map(f -> f.material).distinct().count() <= 1;
            int pieceIndex = 0;
            for (Map.Entry<String, List<Face>> piece : pieces.entrySet()) {
                String objectNameForPiece = singleTexture
                    ? prefix + "_part" + pieceIndex
                    : prefix + "_" + piece.getKey();
                byObject.put(objectNameForPiece, piece.getValue());
                pieceIndex++;
            }
        }

        // Deduplicate vertices on (position, uv, normal). Sharing them is what turns the
        // export from a cloud of loose triangles into a connected surface in Blender -
        // the difference between "a pile of triangles" and "a model".
        Map<String, Integer> vertexIds = new LinkedHashMap<>();
        List<String> vertexLines = new ArrayList<>();
        List<String> uvLines = new ArrayList<>();
        List<String> normalLines = new ArrayList<>();

        int written = 0;
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, List<Face>> objectEntry : byObject.entrySet()) {
            body.append("\no ").append(objectEntry.getKey()).append('\n');
            // Faces are grouped by material inside the object so Blender gets one mesh per
            // sub-level with several material slots, rather than one object per texture.
            Map<String, List<Face>> perMaterial = new LinkedHashMap<>();
            for (Face face : objectEntry.getValue()) {
                perMaterial.computeIfAbsent(face.material, k -> new ArrayList<>()).add(face);
            }
            for (Map.Entry<String, List<Face>> e : perMaterial.entrySet()) {
                body.append("usemtl ").append(e.getKey()).append('\n');
                for (Face face : e.getValue()) {
                    StringBuilder indices = new StringBuilder();
                    for (int i = 0; i < face.count; i++) {
                        // MC is Y-up / Z-forward, Blender is Z-up / -Y forward.
                        float x = face.pos[i * 3];
                        float y = face.pos[i * 3 + 2];
                        float z = -face.pos[i * 3 + 1];
                        float u = face.uv[i * 2];
                        float v = 1.0f - face.uv[i * 2 + 1];
                        float nx = face.norm[i * 3];
                        float ny = face.norm[i * 3 + 2];
                        float nz = -face.norm[i * 3 + 1];

                        String key = String.format(Locale.ROOT, "%.5f|%.5f|%.5f|%.6f|%.6f|%.3f|%.3f|%.3f",
                                                   x, y, z, u, v, nx, ny, nz);
                        Integer id = vertexIds.get(key);
                        if (id == null) {
                            id = vertexIds.size() + 1;
                            vertexIds.put(key, id);
                            vertexLines.add(String.format(Locale.ROOT, "v %.5f %.5f %.5f", x, y, z));
                            uvLines.add(String.format(Locale.ROOT, "vt %.6f %.6f", u, v));
                            normalLines.add(String.format(Locale.ROOT, "vn %.4f %.4f %.4f", nx, ny, nz));
                        }
                        if (i > 0) {
                            indices.append(' ');
                        }
                        indices.append(id).append('/').append(id).append('/').append(id);
                    }
                    body.append("f ").append(indices).append('\n');
                    written++;
                }
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(obj)) {
            w.write("# Sable blueprint export: " + name);
            w.write("\n# read from the blueprint and baked through the game's own block");
            w.write("\n# model pipeline, so every mod model resolves as it does in game");
            w.write("\n# 1 unit = 1 block, Blender axes (X right, Y forward, Z up)");
            w.write("\n# " + vertexIds.size() + " vertices / " + written + " faces");
            w.write("\nmtllib " + mtl.getFileName() + "\n\n");
            for (String line : vertexLines) {
                w.write(line);
                w.write('\n');
            }
            for (String line : uvLines) {
                w.write(line);
                w.write('\n');
            }
            for (String line : normalLines) {
                w.write(line);
                w.write('\n');
            }
            w.write(body.toString());
        }

        // The assembly manifest.
        //
        // OBJ has no concept of a parent, so the hierarchy cannot live in the file. It is
        // written beside it instead, in Blender's axes, and the importer builds the empty
        // parent from it. Without this the sub-levels arrive as siblings with no
        // relationship, which is not what the blueprint describes: Sable welds them into
        // one body whose parts keep their own frames.
        writeManifest(outDir.resolve(name + ".parts.json"), name, collector);

        // Neither used to happen: every material pointed at "textures/atlas/blocks.png.png"
        // (the atlas location already ends in .png, and ".png" was appended again) inside
        // an output folder that held nothing but the .obj and .mtl. Blender then imported
        // a correctly built mesh with no texture on it at all, which reads as "untextured
        // triangles" no matter how good the geometry is.
        // Write each sprite's own PNG and record the path that was actually produced, so
        // the .mtl can name a file that exists. The earlier version emitted the material
        // name and a path derived from the atlas location, which produced
        // "textures/atlas/blocks.png.png" - a file that could never exist - and then the
        // material name again after that; either way Blender imported the mesh with no
        // texture at all.
        Map<String, String> materialFile = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : materialAtlas.entrySet()) {
            String textureFile = writeSpritePng(outDir, entry.getValue(),
                layerOfMaterial(entry.getKey(), materialLayer));
            materialFile.put(entry.getKey(), textureFile);
        }

        // The last step of the cannon probe: the file a cannon part's material points at, and
        // whether it was actually produced. A material that names a cannon texture and still has
        // no file is the `png-not-saved` case, and it has to be said out loud here because this is
        // the only point in the pipeline that knows both halves - what the material was named
        // after and what the writer managed to put on disk.
        for (Map.Entry<String, String> entry : materialAtlas.entrySet()) {
            if (CANNON_SAVED_LOGGED >= CANNON_SAVED_LIMIT) {
                break;
            }
            String texture = entry.getValue();
            String file = materialFile.get(entry.getKey());
            boolean cannonMaterial = isCannonPart(entry.getKey()) || isCannonPart(texture);
            if (!cannonMaterial) {
                continue;
            }
            if (file == null || file.isEmpty()) {
                CANNON_SAVED_LOGGED++;
                BlueprintExporter.LOGGER.info("cannon-tex: why=png-not-saved tex={} material={}",
                    texture == null || texture.isEmpty() ? "-" : texture, entry.getKey());
                continue;
            }
            long bytes = -1L;
            try {
                Path pngPath = outDir.resolve(file);
                if (Files.isRegularFile(pngPath)) {
                    bytes = Files.size(pngPath);
                }
            } catch (Throwable ignored) {
                // A size that cannot be read is reported as -1 rather than hiding the line.
            }
            CANNON_SAVED_LOGGED++;
            BlueprintExporter.LOGGER.info("cannon-tex: saved={} bytes={}", file, bytes);
        }

        // Materials whose transparency has to be written for them, because their own pixels do not
        // carry it. Decided once here and then read by the .mtl body, the map_Kd line and both log
        // lines, so every one of them agrees on which materials were forced.
        //
        // The rule is deliberately blind to names: it is the layer the faces were filed under plus
        // the alpha actually measured in the image. A block called "glass" whose texture is painted
        // opaque is exactly the case this is for, and a stained glass whose pixels are already 40%
        // is not touched at all.
        Map<String, String> forcedAlphaFile = new LinkedHashMap<>();
        // One copy per source sprite: two materials can share one texture - a block-entity name
        // carries its block as well as its sprite - and both must point at the same `_a.png`.
        Map<String, String> alphaOfSource = new LinkedHashMap<>();
        java.util.Set<String> takenTextures = new java.util.LinkedHashSet<>();
        for (String file : materialFile.values()) {
            if (file != null && !file.isEmpty()) {
                takenTextures.add(file);
            }
        }
        for (String mat : byMaterial.keySet()) {
            String texturePath = materialAtlas.get(mat);
            Layer layer = layerOfMaterial(mat, materialLayer);
            Alpha alpha = texturePath == null
                ? new Alpha(Layer.SOLID, 1.0f) : textureAlpha(texturePath);
            if (forcedAlpha(layer, alpha)) {
                String source = materialFile.get(mat);
                String alphaFile = source == null ? null : alphaOfSource.get(source);
                if (alphaFile == null) {
                    alphaFile = writeForcedAlphaPng(outDir, source, takenTextures);
                    if (alphaFile != null) {
                        takenTextures.add(alphaFile);
                        if (source != null) {
                            alphaOfSource.put(source, alphaFile);
                        }
                    }
                }
                forcedAlphaFile.put(mat, alphaFile == null ? "" : alphaFile);
            }
        }

        // The mask every cutout material points `map_d` at.
        //
        // The sprite that was just written is only binarised when its own material was filed as
        // cutout; a texture two materials share - one cutout, one solid - keeps whichever version
        // was written last, and a mask has to be strictly 0 or 255. So the mask is derived here
        // from the sprite that exists on disk, once per sprite, and is therefore independent of
        // the order the materials were written in.
        Map<String, String> cutoutMaskFile = new LinkedHashMap<>();
        Map<String, String> maskOfSource = new LinkedHashMap<>();
        for (String mat : byMaterial.keySet()) {
            if (layerOfMaterial(mat, materialLayer) != Layer.CUTOUT) {
                continue;
            }
            String source = materialFile.get(mat);
            if (source == null || source.isEmpty()) {
                continue;
            }
            String mask = maskOfSource.get(source);
            if (mask == null) {
                mask = writeCutoutMaskPng(outDir, source, takenTextures);
                if (mask != null) {
                    takenTextures.add(mask);
                    maskOfSource.put(source, mask);
                }
            }
            if (mask != null) {
                cutoutMaskFile.put(mat, mask);
            }
        }

        // How many materials landed in each layer, and how many of them carry transparency. This is
        // the one line that answers "did glass export as glass": a structure with stained glass or a
        // pane in it must report translucent >= 1.
        Map<Layer, Integer> layerCount = new LinkedHashMap<>();
        int alphaImages = 0;
        int doubleSided = 0;
        int forcedCount = 0;
        int forcedLogged = 0;
        int alphaDCount = 0;
        int alphaDLogged = 0;
        int cutoutMaskCount = 0;
        int cutoutMaskLogged = 0;
        try (BufferedWriter w = Files.newBufferedWriter(mtl)) {
            w.write("# Sable blueprint export materials\n");
            for (String mat : byMaterial.keySet()) {
                String texturePath = materialAtlas.get(mat);
                Layer layer = layerOfMaterial(mat, materialLayer);
                Alpha alpha = texturePath == null ? new Alpha(Layer.SOLID, 1.0f) : textureAlpha(texturePath);
                layerCount.merge(layer, 1, Integer::sum);
                String file = materialFile.get(mat);
                boolean forced = forcedAlphaFile.containsKey(mat);
                // A forced material points map_Kd at the 60%-alpha copy, so a viewer that blends by
                // image alpha also sees glass, even though it never reads `d`.
                String mapFile = forced ? forcedAlphaFile.get(mat) : file;
                w.write("\nnewmtl " + mat + "\n");
                w.write("# layer: " + layer.name().toLowerCase(java.util.Locale.ROOT) + "\n");
                if (layer != Layer.SOLID) {
                    w.write("# double_sided: true\n");
                    doubleSided++;
                }
                w.write("Kd 1.000 1.000 1.000\nKa 0.000 0.000 0.000\nKs 0.000 0.000 0.000\n");
                if (layer == Layer.TRANSLUCENT) {
                    // Both forms, because viewers differ: `d` is the dissolved amount Blender reads,
                    // `Tr` is the same value inverted for viewers that predate it. illum 2 says the
                    // material has a transparency channel rather than being lit opaque.
                    //
                    // Every translucent material gets a `d` now, not only the forced ones. The old
                    // rule wrote the measured alpha for everything else, so a stained glass sheet
                    // averaging 0.97 came out `d 0.970` - solid to the eye - and the `_a.png` copy
                    // was the only other channel, which Blender's own OBJ importer never reads off
                    // a map_Kd image. `translucentD` is the single source of the number, so this
                    // line, its log line and the summary below cannot disagree.
                    float d = translucentD(alpha);
                    if (forced) {
                        w.write(String.format(java.util.Locale.ROOT,
                            "# layer-translucency: forced(%.3f)\n", FORCED_ALPHA_D));
                    }
                    w.write(String.format(java.util.Locale.ROOT, "d %.3f\n", d));
                    w.write(String.format(java.util.Locale.ROOT, "Tr %.3f\n", 1.0f - d));
                    w.write("illum 2\n");
                    alphaImages++;
                    alphaDCount++;
                    if (alphaDLogged < 30) {
                        alphaDLogged++;
                        BlueprintExporter.LOGGER.info(
                            "material: alpha-d name={} alpha_mean={} d={} source={} png={}",
                            mat,
                            String.format(java.util.Locale.ROOT, "%.3f", alpha.mean()),
                            String.format(java.util.Locale.ROOT, "%.3f", d),
                            translucentDSource(alpha),
                            mapFile == null || mapFile.isEmpty() ? "-" : mapFile);
                    }
                } else if (layer == Layer.CUTOUT) {
                    // Opaque material with holes in its geometry: no d/Tr, because a viewer that
                    // reads them would blend the whole plank instead of cutting the holes out - but
                    // the holes have to be stated another way for Blender's own OBJ importer,
                    // which takes them from `map_d` and never from the alpha channel of the
                    // map_Kd image. Without it a pane or a fence rail imports as a solid sheet.
                    //
                    // map_d points at a strictly binary mask written for this material (white =
                    // opaque, black = hole, in both RGB and alpha so either kind of reader cuts the
                    // same holes). The map_Kd sprite itself cannot be relied on: it is only
                    // binarised when its own material was filed as cutout, and two materials can
                    // share one sprite.
                    w.write("illum 2\n");
                    String maskFile = cutoutMaskFile.getOrDefault(mat, mapFile);
                    if (maskFile != null && !maskFile.isEmpty()) {
                        w.write("map_d " + maskFile + "\n");
                    }
                    alphaImages++;
                    cutoutMaskCount++;
                    if (cutoutMaskLogged < 30) {
                        cutoutMaskLogged++;
                        BlueprintExporter.LOGGER.info(
                            "material: cutout-mask name={} map_d={} holes={}", mat,
                            maskFile == null || maskFile.isEmpty() ? "-" : maskFile,
                            String.format(java.util.Locale.ROOT, "%.3f", alpha.holes()));
                    }
                } else {
                    w.write("d 1.0\nillum 1\n");
                }
                if (mapFile != null && !mapFile.isEmpty()) {
                    // Relative to the output folder, with forward slashes, which is what
                    // Blender's OBJ importer resolves against the .mtl's own directory.
                    w.write("map_Kd " + mapFile + "\n");
                }
                if (forced) {
                    forcedCount++;
                    // One line per forced material, capped: a big structure can hold more than thirty
                    // of them and the count at the end still says the whole truth.
                    if (forcedLogged < 30) {
                        forcedLogged++;
                        BlueprintExporter.LOGGER.info(
                            "material: forced-alpha name={} layer={} alpha_mean={} d={} png={}",
                            mat, layer.name().toLowerCase(java.util.Locale.ROOT),
                            String.format(java.util.Locale.ROOT, "%.3f", alpha.mean()),
                            String.format(java.util.Locale.ROOT, "%.3f", FORCED_ALPHA_D),
                            mapFile == null || mapFile.isEmpty() ? "-" : mapFile);
                    }
                }
                // The one line that answers "is the glass material actually the glass": which blocks
                // it belongs to, the layer the sheet said, which image was written, and the UV space
                // the face coordinates are in (local = 0..1 of that image, which is what this mode
                // produces; the frame capture mode reports tlas instead).
                BlueprintExporter.LOGGER.info(
                    "material: block={} layer={} tex={} uv-space={} faces={} alpha_mean={}",
                    MATERIAL_BLOCKS.getOrDefault(mat, new java.util.LinkedHashSet<>()),
                    layer.name().toLowerCase(java.util.Locale.ROOT),
                    file == null ? "-" : file, "local", byMaterial.get(mat).size(),
                    String.format(java.util.Locale.ROOT, "%.3f", alpha.mean()));
            }
        }
        // The summary that answers "did anything need its transparency written for it": zero means
        // every translucent material already carried its own alpha in its pixels.
        BlueprintExporter.LOGGER.info("material: forced-alpha count={}", forcedCount);
        // ...and the two counts that answer "will Blender's own importer read it": one for every
        // translucent material that was given a `d`, one for every cutout material that was given
        // a `map_d`. Both are expected to equal their layer totals.
        BlueprintExporter.LOGGER.info("material: alpha-d count={} translucent={} clamp={}..{}",
            alphaDCount, layerCount.getOrDefault(Layer.TRANSLUCENT, 0),
            String.format(java.util.Locale.ROOT, "%.2f", TRANSLUCENT_D_MIN),
            String.format(java.util.Locale.ROOT, "%.2f", TRANSLUCENT_D_MAX));
        BlueprintExporter.LOGGER.info("material: cutout-mask count={} cutout={}",
            cutoutMaskCount, layerCount.getOrDefault(Layer.CUTOUT, 0));
        BlueprintExporter.LOGGER.info(
            "blueprint materials: total={} solid={} cutout={} translucent={} alpha_pngs={}"
                + " double_sided={}",
            byMaterial.size(), layerCount.getOrDefault(Layer.SOLID, 0),
            layerCount.getOrDefault(Layer.CUTOUT, 0), layerCount.getOrDefault(Layer.TRANSLUCENT, 0),
            alphaImages, doubleSided);
        // The self-check the report asked for: a material whose name claims one layer while the
        // .mtl body writes another is the exact shape of the "glass exports opaque" bug, so it is
        // counted and named here instead of being left for the next reader of the .mtl to notice.
        int mismatched = 0;
        String firstMismatch = null;
        for (String mat : byMaterial.keySet()) {
            Layer writtenLayer = layerOfMaterial(mat, materialLayer);
            Layer namedLayer = layerFromName(mat);
            if (writtenLayer != namedLayer) {
                mismatched++;
                if (firstMismatch == null) {
                    firstMismatch = mat
                        + " name=" + namedLayer.name().toLowerCase(java.util.Locale.ROOT)
                        + " written=" + writtenLayer.name().toLowerCase(java.util.Locale.ROOT);
                }
            }
        }
        if (mismatched > 0) {
            BlueprintExporter.LOGGER.warn("material: MISMATCH count={} first={}", mismatched,
                firstMismatch);
        } else {
            BlueprintExporter.LOGGER.info("material: layer-check ok count={}", byMaterial.size());
        }
        for (Map.Entry<String, String> entry : materialAtlas.entrySet()) {
            String texturePath = entry.getValue();
            Layer entryLayer = layerOfMaterial(entry.getKey(), materialLayer);
            if (texturePath == null || entryLayer == Layer.SOLID) {
                continue;
            }
            Alpha alpha = textureAlpha(texturePath);
            boolean forced = forcedAlphaFile.containsKey(entry.getKey());
            String mapFile = forced
                ? forcedAlphaFile.get(entry.getKey()) : materialFile.get(entry.getKey());
            BlueprintExporter.LOGGER.info(
                "blueprint material[{}]: name={} map_Kd={} alpha_mean={} d={}"
                    + " double_sided={} holes={}",
                entryLayer.name().toLowerCase(java.util.Locale.ROOT), entry.getKey(),
                mapFile,
                String.format(java.util.Locale.ROOT, "%.3f", alpha.mean()),
                String.format(java.util.Locale.ROOT, "%.3f", translucentD(alpha)),
                entryLayer != Layer.SOLID,
                String.format(java.util.Locale.ROOT, "%.3f", alpha.holes()));
        }
        // The parts a renderer could not describe, named beside the mesh. A hole with no explanation
        // is what the report was about, so the alternative to geometry is an explicit statement that
        // this block needs its renderer rebuilt rather than silence.
        if (!UNSUPPORTED_RENDERERS.isEmpty()) {
            Path unsupported = outDir.resolve(name + ".unsupported.json");
            try (BufferedWriter w = Files.newBufferedWriter(unsupported)) {
                w.write("{\n  \"note\": \"these blocks were exported as their own model only; their"
                        + " block-entity renderer could not be captured\",\n  \"blocks\": {\n");
                int index = 0;
                for (Map.Entry<String, String> entry : UNSUPPORTED_RENDERERS.entrySet()) {
                    w.write("    \"" + entry.getKey().replace("\"", "") + "\": \""
                            + entry.getValue().replace("\"", "").replace("\n", " ") + "\"");
                    w.write(++index < UNSUPPORTED_RENDERERS.size() ? ",\n" : "\n");
                }
                w.write("  }\n}\n");
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.warn("could not write {}: {}", unsupported, t.toString());
            }
            BlueprintExporter.LOGGER.info("ber-capture: {} block(s) had no usable renderer, listed in"
                + " {}", UNSUPPORTED_RENDERERS.size(), unsupported.getFileName());
        }
        BlueprintExporter.LOGGER.info("wrote {} ({} faces)", obj, written);
        return written;
    }

    /**
     * Write the assembly manifest that says how the sub-levels relate.
     *
     * <p>OBJ cannot express a parent, so the structure is described beside the mesh and the
     * Blender side rebuilds it: a parent empty for the whole blueprint, one child empty per
     * sub-level, and the mesh parented to its own. That is what makes a skirt or a turret
     * separately movable, which is the point of exporting per sub-level at all.
     *
     * <p>Coordinates are in Blender's axes, converted the same way the vertices are
     * ({@code x, z, -y}), so the reader does not have to know Minecraft's convention. The
     * parent is the sub-level with the most faces - in every blueprint seen so far that is
     * the hull, and it is the one that must not move.
     */
    private static void writeManifest(Path path, String name, Collector collector)
            throws IOException {
        Map<Integer, int[]> counts = new java.util.TreeMap<>();
        Map<Integer, float[]> sum = new java.util.TreeMap<>();
        for (Face face : collector.faces) {
            int[] box = counts.computeIfAbsent(face.subLevel, k -> new int[1]);
            box[0]++;
            float[] acc = sum.computeIfAbsent(face.subLevel, k -> new float[3]);
            for (int i = 0; i < face.count; i++) {
                acc[0] += face.pos[i * 3];
                acc[1] += face.pos[i * 3 + 1];
                acc[2] += face.pos[i * 3 + 2];
            }
        }

        int parent = -1;
        int best = -1;
        for (Map.Entry<Integer, int[]> entry : counts.entrySet()) {
            if (entry.getValue()[0] > best) {
                best = entry.getValue()[0];
                parent = entry.getKey();
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"blueprint\": \"").append(name).append("\",\n");
        json.append("  \"units\": \"1 unit = 1 block, Blender axes (X right, Y forward, Z up)\",\n");
        json.append("  \"parent\": \"").append(parent < 0 ? "" : objectName(parent))
            .append("\",\n");
        json.append("  \"parts\": [\n");
        boolean first = true;
        for (Map.Entry<Integer, int[]> entry : counts.entrySet()) {
            int index = entry.getKey();
            int faces = entry.getValue()[0];
            float[] acc = sum.get(index);
            int vertices = 0;
            for (Face face : collector.faces) {
                if (face.subLevel == index) {
                    vertices += face.count;
                }
            }
            // Centroid in Minecraft axes, then converted.
            float cx = vertices == 0 ? 0f : acc[0] / vertices;
            float cy = vertices == 0 ? 0f : acc[1] / vertices;
            float cz = vertices == 0 ? 0f : acc[2] / vertices;
            if (!first) {
                json.append(",\n");
            }
            first = false;
            json.append(String.format(Locale.ROOT,
                "    {\"object\": \"%s\", \"index\": %d, \"faces\": %d, "
                + "\"centroid\": [%.4f, %.4f, %.4f], \"is_parent\": %s}",
                objectName(index), index, faces, cx, cz, -cy, index == parent));
        }
        json.append("\n  ]\n}\n");

        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            w.write(json.toString());
        }
    }

    /** The OBJ object name for a sub-level; must match what {@code write} emits. */
    /**
     * Group block-entity faces into pieces, by texture and by where they are.
     *
     * <p>Block-entity geometry is dozens of independent renderers, and where it ends up matters:
     *
     * <ul>
     *   <li>A track is a chain of identical links that touch each other. It has to arrive as one
     *       object, or it is a hundred loose pieces that cannot be moved without selecting them
     *       one at a time - and its links share a texture with everything else nearby, so
     *       grouping by texture alone merges it into an anonymous mass.</li>
     *   <li>A barrel or a cannon is a separate part with no neighbour. It has to arrive on its
     *       own so it can be picked up.</li>
     * </ul>
     *
     * <p>Both come out of one rule: group by texture, then split each group into connected
     * pieces under the same nearness test used for welding. Links that touch stay together;
     * parts that do not touch separate. Nothing needs to know in advance which is which.
     *
     * <p>The nearness test is a small tolerance, measured rather than guessed. On a real export
     * of 20737 faces the face pairs that share a position sit at 0.02 blocks, and the next
     * group out is at 0.05; a first attempt used 1.5 blocks, which is wide enough to bridge a
     * one-block gap and would have joined a track to whatever sat beside it. Sharing a vertex
     * is what identifies a joint, so the tolerance only has to absorb the rounding in the
     * written file - a twentieth of a block is generous for that.
     */
    private static final float PIECE_LINK_DISTANCE = 0.05f * 0.05f;

    private static Map<String, List<Face>> groupBlockEntityPieces(List<Face> faces) {
        // Texture first: two parts with different textures are never the same piece, and this
        // keeps the comparison cheap.
        Map<String, List<Face>> byTexture = new LinkedHashMap<>();
        for (Face f : faces) {
            byTexture.computeIfAbsent(f.material == null ? "unnamed" : f.material,
                                      k -> new ArrayList<>()).add(f);
        }

        Map<String, List<Face>> pieces = new LinkedHashMap<>();
        for (Map.Entry<String, List<Face>> texture : byTexture.entrySet()) {
            List<List<Face>> components = connectedComponents(texture.getValue());
            int index = 0;
            for (List<Face> component : components) {
                pieces.put(texture.getKey() + (components.size() > 1 ? "_" + index : ""),
                           component);
                index++;
            }
        }
        return pieces;
    }

    /**
     * Split faces into groups that touch each other, directly or through another face.
     *
     * <p>Two faces are neighbours when any vertex of one coincides with any vertex of the other,
     * within a tolerance for the rounding in the file. That is the same test used to weld
     * sub-levels, where matching world positions identify a shared joint; here the shared
     * position is what says two pieces are bolted together rather than merely near each other.
     *
     * <p>Growth is done with a queue rather than recursion, so a track of several hundred links
     * cannot overflow the stack.
     */
    private static List<List<Face>> connectedComponents(List<Face> faces) {
        int count = faces.size();
        boolean[] visited = new boolean[count];
        List<List<Face>> components = new ArrayList<>();
        for (int start = 0; start < count; start++) {
            if (visited[start]) {
                continue;
            }
            List<Face> component = new ArrayList<>();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int current = queue.poll();
                component.add(faces.get(current));
                for (int other = 0; other < count; other++) {
                    if (visited[other]) {
                        continue;
                    }
                    if (facesTouch(faces.get(current), faces.get(other))) {
                        visited[other] = true;
                        queue.add(other);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    /** Whether any vertex of one face lies within the linking distance of any of the other's. */
    private static boolean facesTouch(Face a, Face b) {
        for (int i = 0; i < a.count; i++) {
            float ax = a.pos[i * 3];
            float ay = a.pos[i * 3 + 1];
            float az = a.pos[i * 3 + 2];
            for (int j = 0; j < b.count; j++) {
                float dx = ax - b.pos[j * 3];
                float dy = ay - b.pos[j * 3 + 1];
                float dz = az - b.pos[j * 3 + 2];
                if (dx * dx + dy * dy + dz * dz <= PIECE_LINK_DISTANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A material name shortened into something usable as an object name.
     *
     * <p>An OBJ {@code o} line is whitespace delimited, so the name must contain no spaces, and
     * it should still say which texture the part uses. Only the last path segment is kept - the
     * namespace and directory add nothing when several materials share them - and the result is
     * capped so that the object list stays readable.
     */
    private static String materialObjectName(String material) {
        if (material == null || material.isEmpty()) {
            return "unnamed";
        }
        String text = material;
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < text.length()) {
            text = text.substring(slash + 1);
        }
        text = text.replaceAll("[^A-Za-z0-9_]+", "_");
        if (text.length() > 40) {
            text = text.substring(0, 40);
        }
        return text.isEmpty() ? "unnamed" : text;
    }

    private static String objectName(int subLevel) {
        if (subLevel == MAIN_BODY) {
            return "body";
        }
        return String.format("sub%02d", subLevel);
    }

    /**
     * Whether a material needs its transparency written for it rather than read from its texture.
     *
     * <p>Only the layer verdict and the measured alpha decide this - never the material or block
     * name. A block called {@code *_glass*} is not special: what makes glass glass in game is the
     * render layer it is drawn on, and a texture that is painted fully opaque tells the exporter
     * nothing about it. Materials that already blend through their own pixels keep their measured
     * alpha and are not touched.</p>
     */
    private static boolean forcedAlpha(Layer layer, Alpha alpha) {
        return layer == Layer.TRANSLUCENT && alpha != null && alpha.mean() >= FORCED_ALPHA_THRESHOLD;
    }

    /**
     * Write the {@code <name>_a.png} copy of a sprite with its alpha flattened to 60%.
     *
     * <p>A texel that is already more transparent than that keeps its own alpha, so a texture with
     * real holes in it does not gain a pane in them; for the material this rule exists for - a
     * translucent-layer casing painted fully opaque - every texel simply becomes 0.6.</p>
     *
     * <p>This exists because the two ways of expressing transparency in an MTL are read by
     * different viewers. Blender's OBJ importer reads {@code d} (it stores it as the Principled
     * Alpha socket and switches the material to blended), but it never looks at the alpha channel
     * of a {@code map_Kd} image, so {@code d} is what makes the material translucent there; other
     * viewers honour the image alpha and ignore the numbers. Writing both means either kind of
     * reader sees glass, and they do not stack in Blender: the alpha of a {@code map_Kd} image is
     * not consumed at all, so the result there is the 0.6 that {@code d} says, not 0.36.</p>
     *
     * <p>The copy is made from the PNG that was just written rather than from the resource pack, so
     * a cutout binarisation or a channel fix applied on the way out is preserved. Returns the
     * output-relative path, or null when there is no image to copy.</p>
     */
    private static String writeForcedAlphaPng(Path outDir, String writtenPath,
                                              java.util.Set<String> taken) {
        if (writtenPath == null || writtenPath.isEmpty()) {
            return null;
        }
        int dot = writtenPath.lastIndexOf('.');
        String stem = dot < 0 ? writtenPath : writtenPath.substring(0, dot);
        String extension = dot < 0 ? ".png" : writtenPath.substring(dot);
        // `_a` is the documented name. The suffix only climbs when that name is already a real
        // sprite of this export, so a texture that happens to be called `glass_a.png` can never be
        // overwritten by the forced copy of `glass.png`.
        String alphaPath = stem + "_a" + extension;
        for (int n = 2; taken != null && taken.contains(alphaPath); n++) {
            alphaPath = stem + "_a" + n + extension;
        }
        try {
            Path source = outDir.resolve(writtenPath);
            if (Files.notExists(source)) {
                return null;
            }
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(source.toFile());
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    // 0.6 on every painted texel - which is the whole image for the material this
                    // exists for - and never brighter than what was already there, so a hole that
                    // was fully transparent stays a hole instead of turning into a pane.
                    int value = Math.min((argb >>> 24) & 0xFF, FORCED_ALPHA_BYTE);
                    out.setRGB(x, y, (argb & 0x00FFFFFF) | (value << 24));
                }
            }
            Path target = outDir.resolve(alphaPath);
            Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(out, "png", target.toFile());
            return alphaPath.replace('\\', '/');
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("forced-alpha copy failed for {}: {}", writtenPath,
                t.toString());
            return null;
        }
    }

    /**
     * Write the strictly binary {@code <name>_mask.png} a cutout material points {@code map_d} at.
     *
     * <p>A mask is not the sprite: an OBJ viewer uses {@code map_d} as a dissolve sheet, so the
     * value it reads has to be 0 for a hole and full for a painted texel. This writes white
     * ({@code 0xFFFFFFFF}) where the sprite's alpha is at least half, and transparent black
     * everywhere else - both the RGB and the alpha channel then say the same thing, so a viewer
     * that reads luminance and one that reads alpha cut the same holes, and no soft edge survives
     * to import as a grey fringe around the hole.</p>
     *
     * <p>Derived from the PNG that was just written rather than from the resource pack, so a
     * channel fix applied on the way out is preserved. Returns the output-relative path, or null
     * when there is no image to read.</p>
     */
    private static String writeCutoutMaskPng(Path outDir, String writtenPath,
                                             java.util.Set<String> taken) {
        if (writtenPath == null || writtenPath.isEmpty()) {
            return null;
        }
        int dot = writtenPath.lastIndexOf('.');
        String stem = dot < 0 ? writtenPath : writtenPath.substring(0, dot);
        String extension = dot < 0 ? ".png" : writtenPath.substring(dot);
        String maskPath = stem + "_mask" + extension;
        for (int n = 2; taken != null && taken.contains(maskPath); n++) {
            maskPath = stem + "_mask" + n + extension;
        }
        try {
            Path source = outDir.resolve(writtenPath);
            if (Files.notExists(source)) {
                return null;
            }
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(source.toFile());
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int alpha = (image.getRGB(x, y) >>> 24) & 0xFF;
                    out.setRGB(x, y, alpha >= 128 ? 0xFFFFFFFF : 0x00000000);
                }
            }
            Path target = outDir.resolve(maskPath);
            Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(out, "png", target.toFile());
            return maskPath.replace('\\', '/');
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("cutout mask copy failed for {}: {}", writtenPath,
                t.toString());
            return null;
        }
    }

    /**
     * Copy a block texture out of the resource packs into the export folder.
     *
     * <p>Read from the resource packs rather than from the atlas on the GPU: the atlas
     * is one stitched texture whose pixels would need a GL read-back, while the source
     * PNG is already on disk and is the same image.
     *
     * <p>Several candidate paths are tried because the string a model uses and the
     * string the resource manager wants are not the same thing: a model says
     * {@code ns:block/foo} and may or may not include the {@code textures/} segment or
     * the {@code .png} suffix, depending on how the mod authored it. Returns the
     * output-relative path that was written, or null.
     */
    private static String writeSpritePng(Path outDir, String texturePath, Layer layer) {
        if (texturePath == null || texturePath.isEmpty()) {
            return null;
        }
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        var manager = minecraft.getResourceManager();

        net.minecraft.resources.ResourceLocation base =
            net.minecraft.resources.ResourceLocation.tryParse(texturePath);
        if (base == null) {
            return null;
        }
        String namespace = base.getNamespace();
        String path = base.getPath();
        boolean hasPng = path.toLowerCase(java.util.Locale.ROOT).endsWith(".png");
        boolean hasTextures = path.startsWith("textures/");

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        for (String prefix : hasTextures ? new String[] {""} : new String[] {"textures/", ""}) {
            for (String suffix : hasPng ? new String[] {""} : new String[] {".png", ""}) {
                candidates.add(prefix + path + suffix);
            }
        }

        for (String candidate : candidates) {
            net.minecraft.resources.ResourceLocation location =
                net.minecraft.resources.ResourceLocation.tryParse(namespace + ":" + candidate);
            if (location == null) {
                continue;
            }
            var resource = manager.getResource(location);
            if (resource.isEmpty()) {
                continue;
            }
            try {
                Path target = outDir.resolve(candidate);
                Files.createDirectories(target.getParent());
                // Always rewritten through ImageIO rather than copied byte for byte, for two
                // reasons. An image without an alpha channel would arrive opaque in a viewer that
                // needs one, and a cutout texture has to be binarised: a fence's texture is drawn
                // with soft edges, and keeping the half-transparent texels lets a viewer blend them
                // into a grey fringe around every hole. A cutout face is opaque or absent, so
                // >=128 becomes 255 and everything else becomes 0 (with the colour cleared, so no
                // stray hue leaks into the transparent side of a mip).
                try (var stream = resource.get().open()) {
                    java.awt.image.BufferedImage source = javax.imageio.ImageIO.read(stream);
                    if (source == null) {
                        try (var retry = resource.get().open()) {
                            Files.copy(retry, target,
                                       java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        return candidate.replace('\\', '/');
                    }
                    boolean needsAlpha = !source.getColorModel().hasAlpha();
                    int width = source.getWidth();
                    int height = source.getHeight();
                    java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                        width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int argb = source.getRGB(x, y);
                            int alpha = needsAlpha ? 255 : (argb >>> 24) & 0xFF;
                            if (layer == Layer.CUTOUT) {
                                if (alpha >= 128) {
                                    out.setRGB(x, y, argb | 0xFF000000);
                                } else {
                                    out.setRGB(x, y, 0x00000000);
                                }
                            } else {
                                out.setRGB(x, y, (argb & 0x00FFFFFF) | (alpha << 24));
                            }
                        }
                    }
                    javax.imageio.ImageIO.write(out, "png", target.toFile());
                }
                return candidate.replace('\\', '/');
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.warn("copy failed for {}: {}", candidate, t.toString());
            }
        }
        BlueprintExporter.NOTES.add("no texture resource for " + texturePath
                                    + " (tried " + String.join(", ", candidates) + ")");
        return null;
    }

    private static void writeInt(java.io.ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeChunk(java.io.ByteArrayOutputStream out, String tag, byte[] payload)
            throws IOException {
        writeInt(out, payload.length);
        byte[] tagBytes = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(tagBytes);
        out.write(payload);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(tagBytes);
        crc.update(payload);
        writeInt(out, (int) crc.getValue());
    }

    // ------------------------------------------------------------------ helper

    /**
     * One baked face - a quad where the model had one, a triangle otherwise - in model
     * units and Blender axes.
     *
     * <p>Keeping the quad rather than splitting it into two triangles matters: an OBJ
     * made only of triangles gives Blender a diagonal crease across every square face
     * and the shading follows it.
     */
    static final class Face {
        final int count;                 // 3 or 4
        final float[] pos;               // count * 3
        final float[] uv;                // count * 2
        final float[] norm;              // count * 3
        final String material;
        final String atlasPath;
        /**
         * The transparency class this face was filed under when its material was chosen.
         *
         * <p>It travels with the face because the name alone is not a reliable statement: a
         * block-entity material is named after its block as well as its texture, and a check
         * that only looked at the first characters of the name read "solid" for a name that
         * contains "M_translucent_" - which is how stained glass was exported opaque while its
         * material was called translucent.</p>
         */
        final Layer layer;
        /**
         * Which sub-level this face came from.
         *
         * <p>A sub-level is Sable's unit of physical structure: its own rigid body, moved
         * as one. Carrying the index through to the writer lets each one become its own
         * object in Blender, so a skirt or a turret can be picked up and turned on its own
         * instead of being welded into a single soup of thirty-eight texture groups.
         */
        final int subLevel;

        /**
         * The sub-level a block-entity face belongs to, or -1 for a model face.
         *
         * <p>Block-entity geometry is written out one object per texture rather than one object
         * per sub-level, because that is the only form it is useful in: a track or a barrel is
         * a pile of separate renderers, each with its own sprite, and merged into its sub-level
         * it is an anonymous mass. Split by texture, each part arrives under a name that says
         * what it is and can be moved on its own.
         *
         * <p>Model faces are left grouped by sub-level, which is Sable's own unit of physical
         * structure, so a hull stays one object.
         */
        final int blockEntitySubLevel;

        /**
         * Who this face belongs to, or null when nothing was open when it was recorded.
         *
         * <p>Carried on the face rather than derived later because the information only exists
         * while the geometry is being written: by the time the writer sees a face, the block or
         * entity that produced it is long gone. This round writes it nowhere - the OBJ stays
         * byte-identical - and it is the field a map from group to block/entity id will read.</p>
         */
        final String owner;

        /**
         * Which coordinate space this face's UVs were in, as one word: {@code atlas},
         * {@code local} or {@code unknown}.
         *
         * <p>Recorded while the geometry is written, because it is only knowable then: a
         * renderer's coordinates either fell in the block atlas - in which case the tile they
         * landed in was found and they were converted to that tile's own 0..1 - or they were
         * already in a texture's own space. The region export's {@code cannon-tex} line reports
         * it, so "the part has no image" and "the part's coordinates were never atlas
         * coordinates" can be told apart from the log alone.
         */
        String uvSpace = "unknown";

        Face(int count, String material, String atlasPath, int subLevel) {
            this(count, material, atlasPath, subLevel, -1, Layer.SOLID);
        }

        Face(int count, String material, String atlasPath, int subLevel,
             int blockEntitySubLevel, Layer layer) {
            this.count = count;
            this.pos = new float[count * 3];
            this.uv = new float[count * 2];
            this.norm = new float[count * 3];
            this.material = material;
            this.atlasPath = atlasPath;
            this.subLevel = subLevel;
            this.blockEntitySubLevel = blockEntitySubLevel;
            this.layer = layer == null ? Layer.SOLID : layer;
            this.owner = CaptureScope.current();
        }
    }

    /** The three things a face is filed under, decided together so they cannot disagree. */
    private record Pinned(String material, String texture, Layer layer) {
    }

    /**
     * A {@link VertexConsumer} that records whole triangles, plus the atlas the
     * currently drawn quads belong to.
     */
    static final class Collector implements VertexConsumer {
        final List<Face> faces = new ArrayList<>();

        private final List<float[]> quad = new ArrayList<>(4);
        private final List<float[]> uvQuad = new ArrayList<>(4);
        private final List<float[]> nQuad = new ArrayList<>(4);
        private Matrix4f lastPose = new Matrix4f();
        private Matrix3f lastNormal = new Matrix3f();
        /** The block's position in its sub-level, in sub-level axes. */
        private float offsetX;
        private float offsetY;
        private float offsetZ;
        /** Which sub-level the faces being recorded belong to. */
        private int currentSubLevel;

        void setSubLevel(int subLevel) {
            this.currentSubLevel = subLevel;
        }

        /** Which sub-level the faces being recorded belong to. */
        int subLevel() {
            return this.currentSubLevel;
        }
        /** Where the block being rendered sits in its sub-level. */
        private Map<net.minecraft.resources.ResourceLocation, TextureAtlasSprite> atlases =
            new LinkedHashMap<>();
        /** Direction of the quad currently being written, for material grouping. */
        private String currentMaterial = "unknown";

        /** Transparency class of the material being emitted, which decides double-siding. */
        private Layer currentLayer = Layer.SOLID;

        /** Cutout and translucent geometry is written twice, back to back, so it reads from both sides. */
        boolean doubleSided() {
            return effectiveMaterial().layer() != Layer.SOLID;
        }

        /** The layer of the current material, for the .mtl. */
        Layer layer() {
            return this.currentLayer;
        }
        private String currentAtlasPath;
        /** Sprite of the quad currently being written, for per-material grouping. */
        private TextureAtlasSprite currentSprite;
        /** True while the pose already contains the block offset. */
        private boolean blockOffsetInPose;
        /**
         * The sub-level frame, and the only transform this collector applies itself.
         *
         * <p>Both producers hand over positions that already carry whatever the game applied,
         * and the frame is what turns those into the blueprint's coordinates. Keeping it here
         * rather than in a pose means neither path has to know what the other did:
         *
         * <ul>
         *   <li>a baked quad arrives as {@code p} in block-local space, so the vertex is
         *       {@code R_frame . (p + offset) + t_frame};</li>
         *   <li>a block-entity renderer's raw vertex is {@code p} in its own block's space, so
         *       the vertex is {@code R_frame . (p + offset) + t_frame} again - the same
         *       expression, with {@code offset} the block it sits in.</li>
         * </ul>
         *
         * <p>Which means the whole thing is one line: add the offset, apply the frame. The
         * several earlier arrangements - offset through the pose, offset pre-rotated by the
         * frame, offset added twice - were all attempts to work around not having this.
         */
        private Matrix4f vertexFrame = new Matrix4f();

        void setVertexFrame(Matrix4f frame) {
            this.vertexFrame = frame == null ? new Matrix4f() : new Matrix4f(frame);
        }

        /**
         * Place a vertex, given how much of the transform it already carries.
         *
         * <p>{@code stage} is how far along the pipeline the incoming position is:
         *
         * <ul>
         *   <li>{@code 0} - raw, in the block's own space. A baked quad's vertex is this.</li>
         *   <li>{@code 1} - the caller already applied the frame's rotation about the vertex's
         *       own origin, which is what {@code putBulkData} does when handed a frame-only
         *       pose. The translation is still missing.</li>
         *   <li>{@code 2} - fully placed by the frame, as a block-entity renderer's vertices
         *       are once its pose has been applied. Only the block offset is missing.</li>
         * </ul>
         */
        private org.joml.Vector3f place(float x, float y, float z, int stage) {
            org.joml.Vector3f offset = new org.joml.Vector3f(offsetX, offsetY, offsetZ);
            // The offset is in sub-level axes, so the frame rotates it into the blueprint's.
            new org.joml.Matrix3f(vertexFrame).transform(offset);
            org.joml.Vector3f world = new org.joml.Vector3f(x, y, z);
            if (stage == 0) {
                vertexFrame.transformPosition(world);
            } else if (stage == 1) {
                world.add(vertexFrame.m30(), vertexFrame.m31(), vertexFrame.m32());
            }
            world.add(offset);
            return world;
        }
        /**
         * Set when a block-entity renderer hands over raw vertices rather than a BakedQuad.
         *
         * <p>Those never reach {@link #setSprite}, so without this a block entity's faces
         * would be filed under the material of whatever block was drawn last - or under a
         * name that no texture can be found for. The block-entity pass sets this to the
         * render type's own texture instead.
         */
        private boolean materialPinned;
        /**
         * UV bounds seen while a material is pinned.
         *
         * <p>A block-entity renderer never says which texture it used. The UVs are the only
         * remaining evidence: they are either in 0..1, meaning one texture for the whole
         * quad, or spread across a stitched atlas, in which case the sprite can be recovered
         * from which cell they fall in. Knowing which of the two is happening is the
         * difference between naming a texture and guessing at one, so it is measured rather
         * than assumed.
         */
        private float pinnedUMin = Float.MAX_VALUE;
        private float pinnedUMax = -Float.MAX_VALUE;
        private float pinnedVMin = Float.MAX_VALUE;
        private float pinnedVMax = -Float.MAX_VALUE;

        /**
         * The block this group's material was pinned for.
         *
         * <p>Kept apart from {@link #currentMaterial} because the texture can be replaced after
         * the pin - see {@link #effectiveMaterial} - and the replacement name still has to say
         * which block the faces came from, or a wheel and a belt from the same renderer would be
         * indistinguishable again.
         */
        private String pinnedBlockLabel;

        /**
         * The tile the quad being built resolved to, and its bounds in atlas space.
         *
         * <p>Per quad rather than per material group, because one group can hold several parts: a
         * track's renderer draws its belt and its wheels through the same two render types, and the
         * belt and the wheels are different tiles. Naming the whole group after whichever tile the
         * last vertex happened to land in is what left the belt under a wheel mount's image.
         */
        private String quadTile;
        /** Set when two vertices of one quad resolved to different tiles: no single image fits. */
        private boolean quadTileMixed;
        /** The texture of the last face written, for the region export's diagnostic line. */
        private String lastFaceTexture;

        /**
         * Name the material for the next quad explicitly.
         *
         * <p>For geometry that arrives without a sprite: a block-entity renderer writes
         * straight into a VertexConsumer and never says which texture it used, but the
         * RenderType it asked for carries the location. Using that is what gives a barrel or
         * a belt a material the .mtl can resolve, rather than a stale name from the previous
         * block.
         *
         * <p>{@code blockLabel} is kept beside the name rather than only inside it, because the
         * texture can still change after this call - see {@link #effectiveMaterial} - and the
         * name it changes to has to carry the same block.
         */
        void pinMaterial(String material, String texturePath, Layer layer, String blockLabel) {
            // The material is changing, so the group that was running has finished and its UV
            // conversion can be reported.
            flushUvSummary(this.currentMaterial);
            if (material != null && !material.isEmpty()) {
                this.currentMaterial = material;
            }
            this.currentAtlasPath = texturePath;
            this.pinnedBlockLabel = blockLabel;
            // A new group starts with no tile of its own; whatever the last group's coordinates
            // resolved to must not name this one's texture.
            this.resolvedTilePath = null;
            // The caller computed this from the same inputs it named the material from, so the
            // name and the layer cannot drift apart here.
            if (layer != null) {
                this.currentLayer = layer;
            } else if (texturePath != null && !texturePath.isEmpty()) {
                this.currentLayer = layerFor(texturePath);
            }
            this.materialPinned = true;
            this.pinnedUMin = Float.MAX_VALUE;
            this.pinnedUMax = -Float.MAX_VALUE;
            this.pinnedVMin = Float.MAX_VALUE;
            this.pinnedVMax = -Float.MAX_VALUE;
        }

        /**
         * The material, texture and layer the next face is actually filed under.
         *
         * <p>A block-entity renderer's render type names the block atlas, so the name pinned when
         * the buffer is asked for can only ever be a best guess - and for a track it is the wrong
         * guess twice over. Measured on the [tracks] mod's {@code track_mount}: the pinned name
         * came from the block's own model, so the belt's faces were filed under
         * {@code suspension_mount}, while the belt itself - {@code TracksSpriteShifts.BELT} =
         * {@code tracks:block/belt} - is a partial model built in code and appears in no
         * blockstate, no model file and no render type at all. The result was a track whose
         * geometry was right and whose image was a wheel mount's.
         *
         * <p>The coordinates are the way out, and {@link #toSpriteSpace} already takes it: they
         * are atlas coordinates, and the one tile that contains them is the sprite the renderer
         * actually drew. So once that tile is known, it - not the pin - is the texture the faces
         * belong to.
         *
         * <p>Guarded, because a renderer that draws with its own texture hands over coordinates in
         * 0..1 and a lookup of those finds some unrelated tile by coincidence. The whole quad's UV
         * window has to sit inside the tile that was found; a window spilling out of it means the
         * coordinates were never atlas coordinates and the pin stands.
         */
        private Pinned effectiveMaterial() {
            String material = this.currentMaterial;
            String texture = this.currentAtlasPath;
            Layer layer = this.currentLayer;
            // Only a pinned group can be renamed: the model path names a face from the sprite
            // that came with its own quad, which is never a guess.
            String tile = this.materialPinned ? resolvedTileForQuad() : null;
            if (tile != null && !tile.equals(texture)) {
                layer = layerFor(tile);
                // Named by the same function the model path uses, so a track belt drawn by a
                // renderer and a model face of the same texture end up in one material.
                material = materialNameFor(tile, layer);
                if (this.pinnedBlockLabel != null) {
                    material = material + "__" + this.pinnedBlockLabel;
                }
                texture = tile;
            }
            return new Pinned(material, texture, layer);
        }

        /**
         * The one tile the quad being built sits in, or null.
         *
         * <p>Null when the renderer's coordinates were in its own 0..1 space - a lookup of those
         * lands in some unrelated tile by coincidence - and null when the corners disagree, which
         * means no single image is right for the face.
         */
        private String resolvedTileForQuad() {
            if (this.quadTile == null || this.quadTileMixed) {
                return null;
            }
            return this.quadTile;
        }

        /** The UV rectangle the pinned geometry used, for diagnosing its texture. */
        String pinnedUvRange() {
            if (this.pinnedUMin > this.pinnedUMax) {
                return "no uv";
            }
            return String.format(java.util.Locale.ROOT, "u[%.3f..%.3f] v[%.3f..%.3f]",
                                 this.pinnedUMin, this.pinnedUMax,
                                 this.pinnedVMin, this.pinnedVMax);
        }

        // ------------------------------------------------------------ raw coordinate probe
        //
        // The two candidate explanations for a part landing away from its block are that the
        // renderer writes block-local coordinates (and the frame must place them) or that it
        // writes placed coordinates (and the frame must not be applied). The raw range tells
        // them apart: local coordinates fall inside one block, placed ones fall in the range of
        // the blocks the part spans.
        private float rawMinX = Float.MAX_VALUE;
        private float rawMinY = Float.MAX_VALUE;
        private float rawMinZ = Float.MAX_VALUE;
        private float rawMaxX = -Float.MAX_VALUE;
        private float rawMaxY = -Float.MAX_VALUE;
        private float rawMaxZ = -Float.MAX_VALUE;

        /** Start recording the coordinates as the renderer writes them. */
        void beginRawProbe() {
            this.rawMinX = Float.MAX_VALUE;
            this.rawMinY = Float.MAX_VALUE;
            this.rawMinZ = Float.MAX_VALUE;
            this.rawMaxX = -Float.MAX_VALUE;
            this.rawMaxY = -Float.MAX_VALUE;
            this.rawMaxZ = -Float.MAX_VALUE;
        }

        /** @return the raw range, or null if nothing was written. */
        String rawRange() {
            if (this.rawMinX > this.rawMaxX) {
                return null;
            }
            return String.format(java.util.Locale.ROOT, "x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f]",
                                 this.rawMinX, this.rawMaxX, this.rawMinY, this.rawMaxY,
                                 this.rawMinZ, this.rawMaxZ);
        }

        private void noteRaw(float x, float y, float z) {
            this.rawMinX = Math.min(this.rawMinX, x);
            this.rawMinY = Math.min(this.rawMinY, y);
            this.rawMinZ = Math.min(this.rawMinZ, z);
            this.rawMaxX = Math.max(this.rawMaxX, x);
            this.rawMaxY = Math.max(this.rawMaxY, y);
            this.rawMaxZ = Math.max(this.rawMaxZ, z);
        }

        private float placedMinX = Float.MAX_VALUE;
        private float placedMinY = Float.MAX_VALUE;
        private float placedMinZ = Float.MAX_VALUE;
        private float placedMaxX = -Float.MAX_VALUE;
        private float placedMaxY = -Float.MAX_VALUE;
        private float placedMaxZ = -Float.MAX_VALUE;

        /** @return the range after the frame and offset, or null. */
        String placedRange() {
            if (this.placedMinX > this.placedMaxX) {
                return null;
            }
            return String.format(java.util.Locale.ROOT,
                                 "x[%.2f..%.2f] y[%.2f..%.2f] z[%.2f..%.2f]",
                                 this.placedMinX, this.placedMaxX, this.placedMinY,
                                 this.placedMaxY, this.placedMinZ, this.placedMaxZ);
        }

        private void notePlaced(float x, float y, float z) {
            this.placedMinX = Math.min(this.placedMinX, x);
            this.placedMinY = Math.min(this.placedMinY, y);
            this.placedMinZ = Math.min(this.placedMinZ, z);
            this.placedMaxX = Math.max(this.placedMaxX, x);
            this.placedMaxY = Math.max(this.placedMaxY, y);
            this.placedMaxZ = Math.max(this.placedMaxZ, z);
            this.placedPoints.add(new float[] {x, y, z});
        }

        private final java.util.List<float[]> placedPoints = new java.util.ArrayList<>();

        /** Hand over this block's placed vertices and start a new block. */
        void drainPlacedVertices(java.util.List<float[]> into) {
            into.addAll(this.placedPoints);
            this.placedPoints.clear();
        }

        /**
         * Recover a single sprite from the block atlas, when the UVs point into it.
         *
         * <p>A block-entity renderer drawing against the block atlas reports the atlas itself
         * as its texture, and an atlas is not a file: it is stitched at runtime, so nothing can
         * be copied out of it and the .mtl has no image to name. The faces then import
         * untextured, which is what the "unknown white model" was.
         *
         * <p>There is no API that maps an atlas coordinate back to a sprite, and walking the
         * atlas to find one by range would mean a texture lookup per sprite per quad - far too
         * slow for an export of several thousand faces. So the atlas is left named as the
         * atlas: the faces at least group together and their UVs are correct in 0..1 terms,
         * which is what UV normalisation fixes. A proper sprite split would need the atlas's
         * own contents, which the client exposes only through its sprite map.
         *
         * <p>The method is kept, and called, so the one place that would change is obvious.
         */
        private void resolveAtlasSprite() {
            // Intentionally conservative: see the note above. UV normalisation is what makes
            // atlas-sampled block-entity geometry line up; naming a sprite is an improvement
            // that needs the atlas contents, which is not available in the way it looks.
        }

        /**
         * Let the next sprite decide the material again.
         *
         * <p>The face that is still open is closed first, and while the pin is still active - a
         * quad only learns its texture from the pin, so clearing that before writing the face
         * would file the last quad of every renderer under the atlas again.
         */
        void unpinMaterial() {
            flush();
            this.materialPinned = false;
            this.trackHint = false;
            this.trackTexture = null;
        }

        // ------------------------------------------------------------ track belt

        /**
         * Whether the renderer now running is a track renderer, and which texture its belt uses.
         *
         * <p>Set around the renderer call and cleared in {@code finally}, so a renderer's
         * classification cannot leak into the next block. {@code beltTexture} is the name
         * {@link BeltSprite} resolved - by reflection when {@code [tracks]} is installed, by the
         * literal table otherwise - and is null when nothing could name it.
         */
        void setTrackHint(boolean track, String beltTexture) {
            this.trackHint = track;
            this.trackTexture = beltTexture;
        }

        private boolean trackHint;
        private String trackTexture;

        /**
         * Whether the vertex being converted came in as a 0..1 coordinate.
         *
         * <p>Recorded per vertex, per quad. It is what the fallback line reports: a renderer that
         * hands over 0..1 coordinates against a texture nobody can name is a different fault from
         * one that hands over atlas coordinates that fell between tiles.
         */
        private boolean quadUvLocal;

        /** How many faces were filed under the belt in the current renderer call. */
        private int beltQuads;

        void resetBeltQuads() {
            this.beltQuads = 0;
        }

        int beltQuads() {
            return this.beltQuads;
        }

        /** Record which tile this quad belongs to, and whether the quad disagrees with itself. */
        private void noteQuadTile(String name) {
            if (name == null) {
                return;
            }
            if (this.quadTile == null) {
                this.quadTile = name;
            } else if (!this.quadTile.equals(name)) {
                this.quadTileMixed = true;
            }
        }

        /**
         * Scale pinned UVs from atlas pixels into 0..1, if that is what they are.
         *
         * <p>Geometry drawn by a block-entity renderer against the block atlas carries UVs in
         * the atlas's own pixel space - measured values like u[370.375..375.625] - while a
         * model's faces carry 0..1. Written out as they are, the atlas is sampled at hundreds
         * of times its own size, so every face lands on one pixel and the model renders as a
         * flat wash. That is what the "unknown white model" was.
         *
         * <p>The scale comes from the atlas texture itself rather than a constant, because the
         * atlas is resized when a pack adds sprites and assuming 256 would be wrong on any
         * large modpack.
         */
        void normalisePinnedUvs() {
            if (!this.materialPinned || this.pinnedUMin > this.pinnedUMax) {
                return;
            }
            float width = atlasWidth();
            float height = atlasHeight();
            if (width <= 0f || height <= 0f) {
                return;
            }
            // Only when the values are plainly outside 0..1 for the whole quad: a block-entity
            // renderer drawing its own texture uses 0..1 already, and scaling that would break
            // it. The two cases are told apart by the range rather than by the material name,
            // because the name is not always available.
            boolean looksLikePixels = this.pinnedUMin < -1.001f || this.pinnedUMax > 1.001f
                || this.pinnedVMin < -1.001f || this.pinnedVMax > 1.001f;
            if (!looksLikePixels) {
                return;
            }
            for (int i = 0; i < uvQuad.size(); i++) {
                float[] uv = uvQuad.get(i);
                uvQuad.set(i, new float[] {uv[0] / width, uv[1] / height});
            }
        }

        /**
         * Whether the pinned name is an atlas, which no file on disk can answer.
         *
         * <p>A block-entity or entity renderer's render type names the block atlas rather than a
         * texture - measured, the location is {@code minecraft:textures/atlas/blocks.png} - and
         * an atlas is stitched in memory, so a {@code map_Kd} pointing at it produces no image at
         * all. That is the whole of "the part came out white": measured on a region export of a
         * CBC cannon, 288 faces of the barrel's pitch contraption were filed under
         * {@code M_solid_minecraft_textures_atlas_blocks.png__entity} with no {@code map_Kd}
         * line, while the mount beside it resolved normally.
         *
         * <p>Null counts as unusable too - a renderer that names nothing leaves the pin empty -
         * because in both cases the coordinates are the only evidence left.
         */
        private boolean pinNamesAtlas() {
            if (this.currentAtlasPath == null || this.currentAtlasPath.isEmpty()) {
                return true;
            }
            String path = this.currentAtlasPath.toLowerCase(java.util.Locale.ROOT);
            return path.startsWith("minecraft:textures/atlas/")
                || path.startsWith("minecraft:atlas/");
        }

        /**
         * Recover a real tile for a quad whose pinned material cannot become an image.
         *
         * <p>A CBC cannon's barrel is drawn by its pitch contraption, whose geometry is a set of
         * baked block models - so the UVs are block-atlas coordinates, not the renderer's own
         * 0..1 space the entity pass cleared the sprite for. With no sprite to convert against,
         * {@link #toSpriteSpace} hands the coordinates through untouched, no tile is ever named,
         * and the material keeps the atlas location, which cannot be written out.
         *
         * <p>The coordinates are still the answer, the same way they are for the [tracks] belt:
         * the tile whose rectangle holds the quad's UV centroid is the sprite the renderer drew.
         * Three guards keep this from inventing a texture where there is none. The pin must name
         * an atlas - a renderer that names a real PNG keeps it. Every corner of the quad must sit
         * inside the tile that was found - a window spilling out of it means the coordinates were
         * never atlas coordinates. And the tile must be a real tile rather than a one-texel
         * placeholder, or the conversion would collapse the face onto a single texel.
         *
         * <p>Runs after {@link #normalisePinnedUvs}, so a renderer that hands over atlas pixels
         * rather than 0..1 is already reduced when the lookup happens.
         */
        private void resolveAtlasPinTile() {
            if (!this.materialPinned || this.uvQuad.size() < 3 || !pinNamesAtlas()) {
                return;
            }
            if (this.quadTile != null || this.quadTileMixed) {
                // The per-vertex path already answered for this quad.
                return;
            }
            float sumU = 0f;
            float sumV = 0f;
            for (float[] uv : this.uvQuad) {
                sumU += uv[0];
                sumV += uv[1];
            }
            float centreU = sumU / this.uvQuad.size();
            float centreV = sumV / this.uvQuad.size();
            if (centreU < 0f || centreU > 1f || centreV < 0f || centreV > 1f) {
                // Outside the atlas entirely: no tile can hold it.
                return;
            }
            net.minecraft.client.renderer.texture.TextureAtlasSprite tile =
                spriteContaining(centreU, centreV);
            if (tile == null || tile.contents() == null) {
                return;
            }
            float spanU = tile.getU1() - tile.getU0();
            float spanV = tile.getV1() - tile.getV0();
            if (spanU < PLACEHOLDER_SPAN || spanV < PLACEHOLDER_SPAN) {
                return;
            }
            for (float[] uv : this.uvQuad) {
                if (!inside(tile, uv[0], uv[1])) {
                    return;
                }
            }
            var id = tile.contents().name();
            String name = id.getNamespace() + ":" + id.getPath();
            this.resolvedTilePath = name;
            noteQuadTile(name);
            // Converted into the tile's own 0..1, which is the space the PNG written for this
            // material is addressed in.
            for (int i = 0; i < this.uvQuad.size(); i++) {
                float[] uv = this.uvQuad.get(i);
                this.uvQuad.set(i, new float[] {
                    (uv[0] - tile.getU0()) / spanU,
                    (uv[1] - tile.getV0()) / spanV,
                });
            }
        }

        /** The block atlas's width in pixels, or 0 when it cannot be read. */
        private static float atlasWidth() {
            return atlasSize(true);
        }

        private static float atlasHeight() {
            return atlasSize(false);
        }

        private static float atlasSize(boolean width) {
            try {
                // getTextureAtlas returns the lookup function itself, not the atlas object:
                // `Function<ResourceLocation, TextureAtlasSprite>`. That is all that is needed,
                // since a sprite knows the atlas's dimensions through its own UVs.
                var lookup = Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
                if (lookup == null) {
                    return 0f;
                }
                // "missingno" always exists on the block atlas.
                var probe = lookup.apply(net.minecraft.resources.ResourceLocation
                    .withDefaultNamespace("missingno"));
                if (probe == null || probe.contents() == null) {
                    return 0f;
                }
                // getU1() is the sprite's right edge as a fraction of the atlas, so the atlas
                // width in pixels is the sprite's own width divided by that fraction.
                float span = width ? probe.getU1() - probe.getU0() : probe.getV1() - probe.getV0();
                if (span <= 0f) {
                    return 0f;
                }
                float spritePixels = width ? probe.contents().width()
                                           : probe.contents().height();
                if (spritePixels <= 0f) {
                    return 0f;
                }
                return spritePixels / span;
            } catch (Throwable t) {
                return 0f;
            }
        }

        /** Note the UV of an incoming vertex while a block entity's material is pinned. */
        private void noteUv(float u, float v) {
            if (!this.materialPinned) {
                return;
            }
            if (u < this.pinnedUMin) {
                this.pinnedUMin = u;
            }
            if (u > this.pinnedUMax) {
                this.pinnedUMax = u;
            }
            if (v < this.pinnedVMin) {
                this.pinnedVMin = v;
            }
            if (v > this.pinnedVMax) {
                this.pinnedVMax = v;
            }
        }

        /**
         * Record which sprite the next quad belongs to.
         *
         * <p>1.21.1's {@code VertexConsumer} has no {@code setSprite} hook, so the
         * sprite has to be handed over by whoever has the {@code BakedQuad} - that is
         * the caller, not the consumer.
         */
        void setSprite(TextureAtlasSprite sprite) {
            this.currentSprite = sprite;
            if (sprite != null && sprite.contents() != null) {
                var id = sprite.contents().name();
                // A sprite always wins over a pinned name, and this reverses an earlier
                // mistake.
                //
                // The pin exists because a block-entity renderer that writes raw vertices
                // never says which texture it used, so the render type is the only clue - and
                // when that render type is the block atlas, the clue is useless: the atlas is
                // stitched at runtime, so it is not a file and nothing can be copied from it.
                // 3997 of pgz-09's 7544 faces ended up under exactly that name, untextured.
                //
                // But a great many block-entity renderers draw through `putBulkData`, which
                // hands over the quad's own sprite. That sprite IS the answer - a real, named,
                // copyable texture - and ignoring it while a pin was active threw it away for
                // the atlas name. Letting it through is what recovers those faces.
                this.currentAtlasPath = id.getNamespace() + ":" + id.getPath();
                this.currentLayer = layerFor(this.currentAtlasPath);
                this.currentMaterial = this.currentLayer.prefix + "_"
                    + id.getNamespace() + "_" + id.getPath().replace('/', '_');
            }
        }

        void setPose(Matrix4f pose, Matrix3f normal) {
            this.lastPose = pose;
            this.lastNormal = normal;
        }

        /**
         * Switch to the block-entity convention: the pose carries the whole transform.
         *
         * <p>The two paths into this collector disagree about where the model matrix lives,
         * and both have to be honoured:
         *
         * <ul>
         *   <li>{@code putBulkData} re-passes the current pose to {@code addVertex} as the
         *       quad's own pose, so a vertex arrives already placed and the block offset must
         *       be added separately - which is what the model path does;</li>
         *   <li>a block-entity renderer usually calls {@code addVertex(x, y, z)} with no pose
         *       at all, three floats relative to its own block, so the matrix set here is the
         *       only thing that places it.</li>
         * </ul>
         *
         * <p>So the frame is set as the pose and the offset is folded into it, and this flag
         * tells {@code addVertex} not to add the offset a second time. Without it a
         * block-entity's faces landed in block-local space: the geometry was present - 3913
         * faces for one sub-level - and stacked at the origin.
         */
        void setPoseWithOffset(Matrix4f frame, Matrix4f offset) {
            this.vertexFrame = new Matrix4f(frame).mul(offset);
            // The offset is inside the matrix now, so the separate copy must be cleared or it
            // is added a second time.
            this.offsetX = 0f;
            this.offsetY = 0f;
            this.offsetZ = 0f;
        }

        /** Back to the model convention, where the offset is added per vertex. */
        void setPoseWithSeparateOffset(Matrix4f frame) {
            this.vertexFrame = frame == null ? new Matrix4f() : new Matrix4f(frame);
        }

        /**
         * Forget any stored transform, so vertices arrive already positioned.
         *
         * <p>The renderer reaches the consumer through
         * {@code VertexConsumer.putBulkData(PoseStack.Pose, BakedQuad, ...)}, whose
         * default implementation applies the pose itself. Anything this collector
         * applies on top would be a second application.
         */
        void clearPose() {
            this.lastPose = null;
            this.lastNormal = null;
        }

        /**
         * Close the face that is being accumulated, leaving nothing half-written.
         *
         * <p>It used to discard instead of closing, which was correct while every quad flushed
         * itself on its fourth vertex - and wrong once the separately-written path stopped doing
         * that, because then the last quad a renderer drew was the one it lost. {@code flush}
         * already drops a vertex list shorter than three, so a genuinely partial face is still
         * thrown away here.
         */
        void resetQuad() {
            flush();
            quad.clear();
            uvQuad.clear();
            nQuad.clear();
            this.quadTile = null;
            this.quadTileMixed = false;
        }

        /**
         * Close the face a renderer left open, without touching the material pin.
         *
         * <p>For the callers that read {@code faces} straight after a render call - the region
         * entity pass does, to report the entity and its textures. The pin is deliberately left
         * alone: the caller's own cleanup clears it after the numbers have been taken.
         */
        void closePendingQuad() {
            flush();
        }

        /** How many distinct materials the bake produced, for the diagnostics line. */
        int materialCount() {
            java.util.Set<String> mats = new java.util.HashSet<>();
            for (Face f : faces) {
                mats.add(f.material);
            }
            return mats.size();
        }

        void setAtlases(Map<net.minecraft.resources.ResourceLocation, TextureAtlasSprite> map) {
            this.atlases = map;
            // A fallback only. setSprite runs before every quad and overwrites both of
            // these with the sprite's own name and texture path, which is what the
            // material and the copied image have to agree on. The keys here are atlas
            // locations (".../atlas/blocks.png"), which describe no one texture, so
            // there is nothing useful to derive a material from.
            this.currentMaterial = "atlas_untextured";
            this.currentAtlasPath = null;
        }

        /**
         * The overload every model actually goes through.
         *
         * <p>1.21.1's {@code VertexConsumer} has no {@code endVertex}: a vertex arrives
         * whole, in one call carrying position, colour, uv, overlay, light and normal.
         * {@code putBulkData} invokes exactly this one.
         *
         * <p>{@code lastPose} is deliberately NOT applied here. The pose putBulkData
         * passes to the three-argument {@code addVertex} is the BakedQuad's own
         * orientation, which for a block model is the identity - it does not carry the
         * block's position. Applying it (or, as an earlier version did, clearing it and
         * applying nothing) left every block of a sub-level stacked at the origin, and
         * the resulting pile of coincident bow-tie faces is what looked like a spike
         * with a sawtooth edge. The block offset is added in {@code setBlockOffset}
         * instead.
         */
        @Override
        public void addVertex(float x, float y, float z, int color,
                              float u, float v, int overlay, int light,
                              float nx, float ny, float nz) {
            // The offset is rotated by the pose and then added.
            //
            // Vertices arrive here already transformed - `putBulkData` applies the pose
            // itself - but the offset does not, and it needs the same rotation or a
            // sub-level's blocks run along whichever axis its local grid happened to use.
            // Measured: an eleven block skirt at local x came out spanning world x at the
            // frame's translation, i.e. laid out the way one block would be.
            //
            // Its own translation is included in `offsetX/Y/Z` (see setBlockOffset), so only
            // the 3x3 part is applied here.
            org.joml.Vector3f place = place(x, y, z, 0);
            // Record what arrived, before the frame and the block offset were applied. This is
            // what says whether the renderer writes local or already-placed coordinates.
            noteRaw(x, y, z);
            // ...and what it becomes afterwards, so the two can be compared.
            notePlaced(place.x, place.y, place.z);
            // This is the route a BakedQuad takes - `emitQuad` calls straight into here - so the
            // sprite conversion must happen at this point too, not only in setUv. Applying it
            // only in setUv is why every model face kept its atlas coordinates and every
            // texture came out as one flat colour.
            float[] mapped = toSpriteSpace(u, v);
            noteUv(mapped[0], mapped[1]);
            quad.add(new float[] {place.x, place.y, place.z});
            uvQuad.add(mapped);
            nQuad.add(new float[] {nx, ny, nz});
            if (quad.size() >= 4) {
                flush();
            }
        }

        /**
         * The block's position in its sub-level, in the sub-level's own rotation.
         *
         * <p>{@code R_frame . local}, with no translation. The frame's translation is already
         * in the vertex: {@code putBulkData} transforms each vertex by the sub-level's pose,
         * so a vertex arrives as {@code R . p + t}. Adding {@code t} again here would shift
         * every block of the sub-level by one frame offset.
         *
         * <p>The rotation is needed because {@code local} is in sub-level axes while the
         * vertex it is added to is already in world axes. Leaving it out - which is what the
         * pose was silently doing, since it applies its rotation to the vertex but not to an
         * offset added alongside - made a sub-level's blocks run along whichever axis its
         * local grid used: an eleven block skirt spanned world x at the frame's translation,
         * laid out the way a single block would be.
         */
        void setBlockOffset(float x, float y, float z, Matrix4f frame) {
            if (frame == null) {
                this.offsetX = x;
                this.offsetY = y;
                this.offsetZ = z;
                return;
            }
            org.joml.Vector3f turned = new org.joml.Vector3f(x, y, z);
            new org.joml.Matrix3f(frame).transform(turned);
            this.offsetX = turned.x;
            this.offsetY = turned.y;
            this.offsetZ = turned.z;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return addVertex(lastPose, x, y, z);
        }

        @Override
        public VertexConsumer addVertex(Matrix4f pose, float x, float y, float z) {
            // The previous quad is closed here, not on its own fourth vertex.
            //
            // A renderer that writes through a SuperByteBuffer chains
            // `addVertex(...).setColor(...).setUv(u, v)`: the uv for a vertex arrives one call
            // after its position. Flushing on the fourth `addVertex` therefore closed the face
            // BEFORE its last corner had been given a texture coordinate, and the corner kept the
            // `{0, 0}` this method pushed. Measured on a CBC cannon region export: 288 of 288
            // barrel faces and 7064 of 7064 track-belt faces carried exactly one corner at
            // (0, 0) and none of the other three rectangle corners, while the faces written
            // through putBulkData - where the uv rides on the vertex - had all four.
            //
            // Deferring the close to the next vertex costs one quad of latency and is closed by
            // resetQuad at the end of a pass, so no face is lost.
            if (quad.size() >= 4) {
                flush();
            }
            // Stage 0 either way. `setPoseWithOffset` folds the block offset into the frame
            // and zeroes the separate offset, so the same expression covers a block-entity
            // renderer's raw vertex and a baked quad's.
            org.joml.Vector3f world = place(x, y, z, 0);
            // The two probes are recorded here as well, because this is the route a
            // block-entity renderer takes when it writes through {@code SuperByteBuffer}: the
            // eleven-argument overload below is the model path and the one putBulkData reaches,
            // and a part drawn by code - a track link, a cannon carriage - never goes near it.
            // Without this the pose measurement would report "no vertex" for exactly the parts
            // whose placement is in question.
            noteRaw(x, y, z);
            notePlaced(world.x, world.y, world.z);
            quad.add(new float[] {world.x, world.y, world.z});
            uvQuad.add(new float[] {0f, 0f});
            nQuad.add(new float[] {0f, 1f, 0f});
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }

        /**
         * Convert an atlas-space UV into the sprite's own 0..1 space.
         *
         * <p>This is the step that makes a texture appear at all. Every face is baked from a
         * quad whose UVs point into the shared block atlas, and the atlas cannot be copied out
         * of the GPU - so each face's texture is instead written as the sprite's own PNG. A UV
         * still expressed against the atlas then samples a sliver of that small image: a quad
         * spanning 0.002 of the atlas lands inside a single pixel of a 16x16 sprite, and the
         * face comes out one flat colour. That is exactly what was reported.
         *
         * <p>The conversion has to happen wherever a UV enters, and there are two routes: the
         * BakedQuad path reaches {@code addVertex} directly, while a block-entity renderer that
         * sets UVs separately reaches {@code setUv}. An earlier version applied it only in
         * {@code setUv}, which the model path never calls - so every model face kept its atlas
         * coordinates and every texture was a flat colour.
         */
        // ------------------------------------------------------------ uv conversion probe
        //
        // What the renderer hands in, against what the conversion produces. A model face and a
        // renderer's part go through the same expression and come out with different spans - 0.5
        // against 0.02 - which means the inputs differ in units, not in scale. Only the two
        // ranges side by side show which.
        private float uvInputMin = Float.MAX_VALUE;
        private float uvInputMax = -Float.MAX_VALUE;
        private int uvInputCount = 0;
        private float uvOutputMin = Float.MAX_VALUE;
        private float uvOutputMax = -Float.MAX_VALUE;

        /**
         * Whether the UVs arriving now are atlas coordinates needing conversion.
         *
         * <p>True for a model's baked quad, false for a block-entity renderer's own vertices. See
         * {@link #toSpriteSpace}.
         */
        private boolean uvNeedsAtlasConversion = true;

        /**
         * The texture the renderer's own coordinates identified, once it has been drawn.
         *
         * <p>Set by {@link #toSpriteSpace} when the reverse lookup finds the tile a vertex lands
         * in. The block-entity pass reads it afterwards and writes the material for that image -
         * which is the only route that finds a texture the mod names solely in code.
         */
        private String resolvedTilePath = null;

        /** The texture the renderer's coordinates resolved to, or null. */
        String resolvedTile() {
            return this.resolvedTilePath;
        }

        /**
         * The texture the last face written was filed under.
         *
         * <p>Not read from {@code effectiveMaterial}: this is asked after the group has been
         * closed, so the answer has to come from the face that was actually written.
         */
        String materialTexture() {
            if (this.lastFaceTexture != null && !this.lastFaceTexture.isEmpty()) {
                return this.lastFaceTexture;
            }
            return this.currentAtlasPath == null || this.currentAtlasPath.isEmpty()
                ? "-" : this.currentAtlasPath;
        }

        /**
         * Which coordinate space the last group's UVs were in, as one word.
         *
         * <p>{@code atlas} means the renderer handed over atlas coordinates and the tile was found
         * from them; {@code local} means they were inside 0..1 with no tile to find, i.e. the
         * renderer's own texture; {@code unknown} means nothing was written.
         */
        String uvSpace() {
            if (this.resolvedTilePath != null) {
                return "atlas";
            }
            if (this.pinnedUMin <= this.pinnedUMax) {
                return this.pinnedUMin >= -0.001f && this.pinnedUMax <= 1.001f
                    && this.pinnedVMin >= -0.001f && this.pinnedVMax <= 1.001f
                    ? "local" : "unknown";
            }
            return "unknown";
        }

        /**
         * One texture a run of faces was filed under, with the space its UVs were in and how
         * many faces used it.
         *
         * <p>The region export's per-block and per-entity texture lines are built from these: a
         * block or a contraption can write several materials in one pass, and a single
         * {@code tex=} field has to be able to say so. {@code "-"} stands for geometry that ended
         * up with no texture at all, which is the fault this line exists to make visible.
         */
        record TextureLine(String texture, String uvSpace, int faces) {
        }

        /**
         * The faces written since {@code from}, grouped by the texture they were filed under.
         *
         * <p>Asked by the caller that knows which block or entity just finished, because the
         * grouping is only meaningful over one block's faces - the same texture appearing on two
         * parts must not merge them into one answer.
         */
        java.util.List<TextureLine> texturesSince(int from) {
            java.util.LinkedHashMap<String, int[]> counts = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, String> spaces = new java.util.LinkedHashMap<>();
            for (int i = Math.max(0, from); i < this.faces.size(); i++) {
                Face face = this.faces.get(i);
                String key = face.atlasPath == null || face.atlasPath.isEmpty()
                    ? "-" : face.atlasPath;
                counts.computeIfAbsent(key, k -> new int[1])[0]++;
                String space = face.uvSpace == null ? "unknown" : face.uvSpace;
                String existing = spaces.get(key);
                // atlas outranks local outranks unknown: one quad in atlas coordinates is enough
                // to say the renderer was drawing the block atlas.
                if (existing == null || uvSpaceRank(space) > uvSpaceRank(existing)) {
                    spaces.put(key, space);
                }
            }
            java.util.List<TextureLine> out = new java.util.ArrayList<>(counts.size());
            for (java.util.Map.Entry<String, int[]> entry : counts.entrySet()) {
                out.add(new TextureLine(entry.getKey(), spaces.get(entry.getKey()),
                                        entry.getValue()[0]));
            }
            return out;
        }

        /** How strongly a UV-space word claims the face was drawn from the block atlas. */
        private static int uvSpaceRank(String space) {
            if ("atlas".equals(space)) {
                return 2;
            }
            return "local".equals(space) ? 1 : 0;
        }

        /** Which coordinate space the quad about to be written used, as one word. */
        private String uvSpaceOfQuad() {
            if (resolvedTileForQuad() != null) {
                return "atlas";
            }
            if (!this.materialPinned) {
                // A model quad carries its own sprite, so its UVs are atlas coordinates that
                // were converted through that sprite.
                return "atlas";
            }
            return this.quadUvLocal ? "local" : "unknown";
        }

        /**
         * Where the geometry this block entity wrote ended up, in export coordinates.
         *
         * <p>The centre of its bounding box, against the centre of the block it belongs to: the
         * two agreeing is what a correctly placed part looks like, and a part drawn around the
         * wrong origin is displaced by exactly the difference. Nothing is cleared here - the
         * affinity measurement still wants the points - so this is a read, not a drain.
         *
         * @return {@code {x, y, z}}, or null when the renderer wrote no positioned vertex
         */
        float[] placedCentre() {
            if (this.placedPoints.isEmpty()) {
                return null;
            }
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            for (float[] point : this.placedPoints) {
                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                minZ = Math.min(minZ, point[2]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
                maxZ = Math.max(maxZ, point[2]);
            }
            return new float[] {(minX + maxX) * 0.5f, (minY + maxY) * 0.5f,
                                (minZ + maxZ) * 0.5f};
        }

        /** Set by the model path before a quad is emitted. */
        void setUvNeedsAtlasConversion(boolean needed) {
            this.uvNeedsAtlasConversion = needed;
        }

        /** A one-line summary of this material's UV conversion, or null if none ran. */
        String uvSummary() {
            if (this.uvInputCount == 0) {
                return null;
            }
            // A block-entity renderer's coordinates are resolved through the tile lookup and the
            // method returns before the outputs are recorded, so min/max are still the sentinels.
            // Printing those produced "out[3.4E38..-3.4E38] span=-Infinity", which reads like a
            // broken transform and sent two sessions after the wrong thing.
            boolean recorded = this.uvOutputMin <= this.uvOutputMax;
            return String.format(java.util.Locale.ROOT,
                "in[%.3f..%.3f] out[%s] span=%s sprite=%s span_uv=(%.5f, %.5f)",
                this.uvInputMin, this.uvInputMax,
                recorded ? String.format(java.util.Locale.ROOT, "%.3f..%.3f",
                    this.uvOutputMin, this.uvOutputMax) : "-",
                recorded ? String.format(java.util.Locale.ROOT, "%.4f",
                    this.uvOutputMax - this.uvOutputMin) : "-",
                this.currentSprite == null || this.currentSprite.contents() == null
                    ? "none" : this.currentSprite.contents().name().getPath(),
                this.currentSprite == null ? -1f
                    : this.currentSprite.getU1() - this.currentSprite.getU0(),
                this.currentSprite == null ? -1f
                    : this.currentSprite.getV1() - this.currentSprite.getV0());
        }

        private void noteUvOutput(float u, float v) {
            this.uvOutputMin = Math.min(this.uvOutputMin, Math.min(u, v));
            this.uvOutputMax = Math.max(this.uvOutputMax, Math.max(u, v));
        }

        /**
         * Print this material's UV conversion, once, when the material changes.
         *
         * <p>Called from the point the material is pinned, which is the only place that knows
         * both that a group has finished and what its summary is.
         */
        private void flushUvSummary(String material) {
            if (!DIAGNOSTICS || this.uvInputCount == 0) {
                return;
            }
            String summary = uvSummary();
            if (summary != null) {
                BlueprintExporter.LOGGER.info("DIAG uvm {}", summary);
            }
            this.uvInputMin = Float.MAX_VALUE;
            this.uvInputMax = -Float.MAX_VALUE;
            this.uvInputCount = 0;
            this.uvOutputMin = Float.MAX_VALUE;
            this.uvOutputMax = -Float.MAX_VALUE;
        }

        private float[] toSpriteSpace(float u, float v) {
            // A block-entity renderer already works in the sprite's own space.
            //
            // The conversion below turns an atlas coordinate into a sprite-local one. A renderer
            // does not need it: it draws against one texture and passes coordinates in that
            // texture's 0..1 range. Applying the conversion to those multiplies them by the
            // reciprocal of the sprite's span - measured over 774 probe lines, every input was
            // already inside 0..1 and the output was inflated by up to 673x, so a whole texture
            // collapsed into a few texels. That is the "the part is one flat colour" report, and
            // it is a different fault from the atlas problem this method exists to solve.
            //
            // The two paths are therefore kept apart. `emitQuad` clears this flag for a model's
            // quad, whose UVs really are atlas coordinates; the block-entity pass sets it.
            if (!this.uvNeedsAtlasConversion) {
                if (DIAGNOSTICS) {
                    uvInputCount++;
                    uvInputMin = Math.min(uvInputMin, Math.min(u, v));
                    uvInputMax = Math.max(uvInputMax, Math.max(u, v));
                }
                // A track renderer draws its links from an image only its own code names.
                //
                // See BeltSprite: neither the block's own model nor the render type says
                // `tracks:block/belt`, so the belt's faces used to be filed under the suspension
                // mount - and, worse, the tile search below reads a 0..1 coordinate as an atlas
                // coordinate and lands in whichever unrelated tile happens to contain it. For a
                // renderer whose own name says "track", the belt is the answer both times:
                // coordinates inside 0..1 are already in the belt's own space, and coordinates
                // inside the belt's atlas rect are the belt with its scroll shift applied.
                boolean spriteLocal = u >= -0.001f && u <= 1.001f
                    && v >= -0.001f && v <= 1.001f;
                if (spriteLocal) {
                    this.quadUvLocal = true;
                }
                if (this.trackHint) {
                    net.minecraft.client.renderer.texture.TextureAtlasSprite belt =
                        BeltSprite.sprite();
                    String beltName = this.trackTexture == null ? BeltSprite.texture()
                        : this.trackTexture;
                    if (beltName != null && (spriteLocal || inside(belt, u, v))) {
                        this.resolvedTilePath = beltName;
                        noteQuadTile(beltName);
                        if (spriteLocal) {
                            // Already the belt's own 0..1 space; converting it would divide by
                            // the tile's span and collapse the whole link into one texel.
                            return new float[] {u, v};
                        }
                        float spanU = belt.getU1() - belt.getU0();
                        float spanV = belt.getV1() - belt.getV0();
                        if (spanU > 0f && spanV > 0f) {
                            return new float[] {
                                (u - belt.getU0()) / spanU,
                                (v - belt.getV0()) / spanV,
                            };
                        }
                        return new float[] {u, v};
                    }
                }
                // Atlas coordinates, resolved by finding which tile contains them.
                //
                // The renderer's own texture names are not usable: measured on the [tracks] mod's
                // renderer, the render types it draws with resolve to `suspension_mount` and
                // `custom_break_particles`, while the belt it actually draws is named only inside
                // its `TracksSpriteShifts.BELT` constant and never appears in a render type at
                // all. So the sprite is identified from the coordinates instead - the probe read
                // `u[0.891..0.893] v[0.580..0.582]`, a 0.002 window, which is one tile of the
                // atlas and nothing else.
                //
                // The conversion is then the same one the model path uses, which is why passing
                // the values through unchanged left the belt sampling a 0.002-wide sliver of its
                // own 16x16 image and coming out a flat colour.
                // Atlas coordinates. The tile is identified by name and then resolved properly.
                //
                // Two things had to be got right and the first attempt got both wrong. The
                // candidate list must be deduplicated - the first version appended the same
                // sprites per block state and reached 3,834,703 entries, which would have hung the
                // export. And the resolution must go through `TextureAtlas.getSprite`, because
                // `Minecraft.getTextureAtlas` returns a lookup whose tiles are 1/512 placeholders
                // carrying the wrong name; every "found" tile in the first run was one of those.
                //
                // With a real tile the conversion is the same one the model path uses. The name
                // also becomes the material's image, which is the only route to a texture a mod
                // names solely in its own code - the [tracks] belt is drawn from
                // `TracksPartialModels.TRACKWORK_TRACK_LINK`, built in code, so it appears in no
                // blockstate and no registered model.
                net.minecraft.client.renderer.texture.TextureAtlasSprite tile =
                    spriteContaining(u, v);
                if (tile == null) {
                    return new float[] {u, v};
                }
                if (DIAGNOSTICS && SPRITE_TILE_LOGGED < 14) {
                    SPRITE_TILE_LOGGED++;
                    BlueprintExporter.LOGGER.info(
                        "DIAG tile {} at ({}, {}) span=({}, {})",
                        tile.contents() == null ? "?" : tile.contents().name(), u, v,
                        tile.getU1() - tile.getU0(), tile.getV1() - tile.getV0());
                }
                float tileSpanU = tile.getU1() - tile.getU0();
                float tileSpanV = tile.getV1() - tile.getV0();
                if (tileSpanU <= 0f || tileSpanV <= 0f) {
                    return new float[] {u, v};
                }
                if (tile.contents() != null) {
                    String tileName = tile.contents().name().getNamespace() + ":"
                        + tile.contents().name().getPath();
                    this.resolvedTilePath = tileName;
                    // Which tile THIS quad belongs to, decided by its first vertex. A quad's four
                    // corners come from one sprite, so the first one speaks for all four; two
                    // vertices disagreeing means the renderer stitched something together and no
                    // single image is right.
                    noteQuadTile(tileName);
                }
                return new float[] {
                    (u - tile.getU0()) / tileSpanU,
                    (v - tile.getV0()) / tileSpanV,
                };
            }
            // Record what came in and what goes out, per material.
            //
            // Collapsed UVs - a part rendering as one flat colour - have survived several
            // attempts at this conversion, and the arithmetic that would explain it is not
            // recoverable by reading the code: the same expression produces a correct 0.5 span
            // on a model face and a 0.02 span on a renderer's part, so the two must be handing
            // over different units. Logging both sides of the conversion is what settles which.
            if (DIAGNOSTICS) {
                uvInputMin = Math.min(uvInputMin, Math.min(u, v));
                uvInputMax = Math.max(uvInputMax, Math.max(u, v));
                uvInputCount++;
            }
            // The sprite carries everything needed, and nothing else is required.
            //
            // `TextureAtlasSprite.getU(t)` maps a coordinate in the sprite's own 0..1 space to
            // the shared atlas - so the inverse, `(u - getU0()) / (getU1() - getU0())`, turns an
            // atlas coordinate back into the sprite's own. That is the whole conversion, and it
            // needs no knowledge of how large the atlas is.
            //
            // Earlier versions tried to scale by the atlas dimensions, derived from a probe
            // sprite. That derivation was wrong - it produced a width near 1 instead of 512 -
            // and because the arithmetic multiplied by it, the error was invisible in the small
            // spans and gross in the large ones: Create's brass casing came out with UVs up to
            // 21 and 206. Removing the atlas size from the calculation removes the fault.
            if (DIAGNOSTICS && !UV_PROBE_LOGGED) {
                UV_PROBE_LOGGED = true;
                // Report the whole chain, because several of these links have been wrong and
                // each looked plausible on its own: the path the render type resolved to, the
                // sprite that came with the quad, and the sprite found by looking the path up.
                BlueprintExporter.LOGGER.info(
                    "DIAG uv probe: path={} sprite={} u0={} u1={} v0={} v1={} in=({}, {})",
                    currentAtlasPath,
                    currentSprite == null || currentSprite.contents() == null
                        ? "none" : currentSprite.contents().name(),
                    currentSprite == null ? -1f : currentSprite.getU0(),
                    currentSprite == null ? -1f : currentSprite.getU1(),
                    currentSprite == null ? -1f : currentSprite.getV0(),
                    currentSprite == null ? -1f : currentSprite.getV1(),
                    u, v);
            }

            if (currentSprite == null) {
                return new float[] {u, v};
            }
            float spanU = currentSprite.getU1() - currentSprite.getU0();
            float spanV = currentSprite.getV1() - currentSprite.getV0();
            // A span of one texel means the sprite is not a real one.
            //
            // The render type only ever reports the block ATLAS, so looking a sprite up by that
            // location returns a placeholder whose bounds are a single pixel - measured, the
            // span was exactly 1/512. Dividing by it maps every vertex of a face onto the same
            // texel, so the face is one flat colour however the rest of the pipeline behaves.
            // That is the whole of "the parts are white".
            //
            // The atlas is a lookup keyed by texture location, so the sprite can be had
            // properly: the block's own texture path is the key, and `currentAtlasPath` holds
            // it. Only the placeholder is rejected - a real sprite's span is larger than one
            // texel on any atlas, so a genuine lookup is never replaced.
            if (currentAtlasPath != null && currentAtlasPath.contains(":")) {
                int colon = currentAtlasPath.indexOf(':');
                var located = net.minecraft.resources.ResourceLocation.tryParse(
                    currentAtlasPath.substring(0, colon) + ":"
                    + currentAtlasPath.substring(colon + 1));
                if (located != null) {
                    var lookup = Minecraft.getInstance().getTextureAtlas(
                        net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
                    if (lookup != null) {
                        var real = lookup.apply(located);
                        if (real != null && real.contents() != null) {
                            float realSpanU = real.getU1() - real.getU0();
                            float realSpanV = real.getV1() - real.getV0();
                            // Only ever replace a placeholder. The old test demanded that the
                            // looked-up sprite be STRICTLY larger, which rejects an equally sized
                            // real tile - the case the user's log shows, where the lookup answered
                            // span=(0.00195,0.00195) and the current sprite was the same real
                            // 16x16 tile on an 8192 atlas. A one-texel placeholder is what has to
                            // be detected, so that is what is measured.
                            boolean currentIsPlaceholder = spanU < PLACEHOLDER_SPAN
                                || spanV < PLACEHOLDER_SPAN;
                            boolean lookupIsReal = realSpanU >= PLACEHOLDER_SPAN
                                && realSpanV >= PLACEHOLDER_SPAN;
                            if (lookupIsReal && (currentIsPlaceholder
                                    || realSpanU * realSpanV > spanU * spanV)) {
                                currentSprite = real;
                                spanU = realSpanU;
                                spanV = realSpanV;
                                if (DIAGNOSTICS && !SPRITE_LOOKUP_LOGGED) {
                                    SPRITE_LOOKUP_LOGGED = true;
                                    BlueprintExporter.LOGGER.info(
                                        "DIAG sprite lookup: {} -> {} span=({}, {})",
                                        currentAtlasPath, real.contents().name(),
                                        realSpanU, realSpanV);
                                }
                            } else if (DIAGNOSTICS && !SPRITE_LOOKUP_LOGGED) {
                                SPRITE_LOOKUP_LOGGED = true;
                                BlueprintExporter.LOGGER.info(
                                    "DIAG sprite lookup: {} returned span=({}, {}) - no larger "
                                    + "than the ({}), so it was not used",
                                    currentAtlasPath, realSpanU, realSpanV,
                                    currentSprite.contents() == null ? "?"
                                        : currentSprite.contents().name());
                            }
                        } else if (DIAGNOSTICS && !SPRITE_LOOKUP_LOGGED) {
                            SPRITE_LOOKUP_LOGGED = true;
                            BlueprintExporter.LOGGER.info(
                                "DIAG sprite lookup: {} found nothing", currentAtlasPath);
                        }
                    }
                }
            }
            if (spanU == 0f || spanV == 0f) {
                return new float[] {u, v};
            }
            // A block-entity renderer may supply the coordinate in atlas pixels instead of 0..1
            // - measured values like 370.375 - so anything outside the atlas range is reduced
            // first. Pixels divided by the sprite's own pixel width is exactly a sprite-local
            // coordinate, which is what the sprite's span is measured in.
            if (u < -1.001f || u > 1.001f) {
                float spritePixels = currentSprite.contents() == null
                    ? 0f : currentSprite.contents().width();
                if (spritePixels > 0f) {
                    u = u / spritePixels;
                }
            }
            if (v < -1.001f || v > 1.001f) {
                float spritePixels = currentSprite.contents() == null
                    ? 0f : currentSprite.contents().height();
                if (spritePixels > 0f) {
                    v = v / spritePixels;
                }
            }
            float[] out = {
                (u - currentSprite.getU0()) / spanU,
                (v - currentSprite.getV0()) / spanV,
            };
            if (DIAGNOSTICS) {
                noteUvOutput(out[0], out[1]);
            }
            return out;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (!uvQuad.isEmpty()) {
                float[] mapped = toSpriteSpace(u, v);
                noteUv(mapped[0], mapped[1]);
                uvQuad.set(uvQuad.size() - 1, mapped);
            }
            return this;
        }

        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            Vector3f n = new Vector3f(x, y, z);
            if (lastNormal != null) {
                lastNormal.transform(n);
            }
            if (!nQuad.isEmpty()) {
                nQuad.set(nQuad.size() - 1, new float[] {n.x, n.y, n.z});
            }
            return this;
        }

        @Override
        public VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) {
            Vector3f n = new Vector3f(x, y, z);
            if (pose != null) {
                n = pose.transformNormal(n, new Vector3f());
            }
            if (!nQuad.isEmpty()) {
                nQuad.set(nQuad.size() - 1, new float[] {n.x, n.y, n.z});
            }
            return this;
        }

        private void flush() {
            // Block-entity geometry samples the atlas in pixel space; a model's faces use 0..1.
            // Normalising here, once per quad, covers every route a block entity can take to
            // reach this consumer - the three-argument addVertex, setUv, and putBulkData.
            normalisePinnedUvs();
            // A pinned material that names the block atlas cannot become an image on its own -
            // the atlas has no file - so the quad's own coordinates are asked for a tile. This is
            // the route a CBC cannon barrel takes: its contraption renderer draws baked models
            // through the entity pass, which has no sprite to name the texture from.
            resolveAtlasPinTile();
            // Only well-formed faces are kept.
            //
            // A vertex list that does not hold `count` of each is malformed - something
            // called setUv or setNormal without a matching addVertex, so the three lists
            // drifted apart. Emitting anyway pairs one vertex's position with another's
            // texture coordinate, and produces the faces with repeated indices and the
            // edges that span whole blocks that showed up as spikes in Blender.
            if (quad.size() != uvQuad.size() || quad.size() != nQuad.size()) {
                quad.clear();
                uvQuad.clear();
                nQuad.clear();
                this.quadTile = null;
                this.quadTileMixed = false;
                return;
            }
            // A face whose corners are not distinct is a degenerate strip, not geometry.
            boolean distinct = true;
            for (int i = 0; i < quad.size() && distinct; i++) {
                for (int j = i + 1; j < quad.size(); j++) {
                    float[] a = quad.get(i);
                    float[] b = quad.get(j);
                    if (Math.abs(a[0] - b[0]) < 1e-4f
                        && Math.abs(a[1] - b[1]) < 1e-4f
                        && Math.abs(a[2] - b[2]) < 1e-4f) {
                        distinct = false;
                        break;
                    }
                }
            }
            // A face with no area is not geometry. These come out of models that emit
            // quads with collinear or coincident corners - Copycats+ panels do - and they
            // are what produced the sawtooth edge along an otherwise flat part: 39% of the
            // skirt object's faces measured zero area in Blender, and every one of them
            // drew as a thin spike.
            double area = faceArea(quad);
            boolean keep = distinct && area > 1e-4;
            if (keep && quad.size() == 4) {
                addFace(4);
            } else if (keep && quad.size() == 3) {
                addFace(3);
            }
            quad.clear();
            uvQuad.clear();
            nQuad.clear();
            // The next quad decides its own tile from its own first vertex.
            this.quadTile = null;
            this.quadTileMixed = false;
            this.quadUvLocal = false;
        }

        /** Total area of a polygon, from the cross products of its fan triangles. */
        private static double faceArea(List<float[]> points) {
            if (points.size() < 3) {
                return 0.0;
            }
            float[] origin = points.get(0);
            double total = 0.0;
            for (int i = 1; i + 1 < points.size(); i++) {
                float[] a = points.get(i);
                float[] b = points.get(i + 1);
                double ax = a[0] - origin[0];
                double ay = a[1] - origin[1];
                double az = a[2] - origin[2];
                double bx = b[0] - origin[0];
                double by = b[1] - origin[1];
                double bz = b[2] - origin[2];
                double cx = ay * bz - az * by;
                double cy = az * bx - ax * bz;
                double cz = ax * by - ay * bx;
                total += 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
            }
            return total;
        }

        private void addFace(int count) {
            // One note per quad, not per emitted face: a double-sided material writes two faces
            // for the same quad, and the owner is a property of the quad.
            CaptureScope.noteQuad();
            // Decided once for the quad, so the name, the image, the layer and the reverse copy
            // cannot disagree: `effectiveMaterial` may replace all three when the renderer's own
            // coordinates identified a different texture from the one the pin guessed.
            Pinned pinned = effectiveMaterial();
            // Named here, once per quad, because this is the one point where both halves of the
            // answer are known: the pin the render type guessed and whether the coordinates
            // identified anything better. A block-entity face that keeps the pin is the fallback
            // case, and it is logged rather than left to be discovered in the .mtl.
            if (this.materialPinned && resolvedTileForQuad() == null
                    && SPRITE_FALLBACK_LOGGED < 20) {
                SPRITE_FALLBACK_LOGGED++;
                BlueprintExporter.LOGGER.info(
                    "region-ber: sprite-fallback={} reason={}",
                    pinned.texture() == null ? "-" : pinned.texture(),
                    this.quadUvLocal ? "uv-local" : "no-sprite");
            }
            if (pinned.texture() != null && pinned.texture().equals(BeltSprite.texture())) {
                this.beltQuads++;
            }
            addFaceOne(count, false, pinned);
            if (pinned.layer() != Layer.SOLID && count >= 3) {
                // The reverse copy, so a pane, a fence or a sheet of glass is visible from both
                // sides. Done here rather than per draw path, because every face - a model's quad
                // and a block-entity renderer's own vertices alike - passes through this method.
                addFaceOne(count, true, pinned);
            }
        }

        private void addFaceOne(int count, boolean reversed, Pinned pinned) {
            // A pinned material means a block-entity renderer chose it, which is the signal
            // that these faces should be grouped by texture rather than by sub-level.
            Face face = new Face(count, pinned.material(), pinned.texture(), currentSubLevel,
                                 materialPinned ? currentSubLevel : -1, pinned.layer());
            face.uvSpace = uvSpaceOfQuad();
            this.lastFaceTexture = pinned.texture();
            for (int i = 0; i < count; i++) {
                int src = reversed ? count - 1 - i : i;
                float[] p = quad.get(src);
                face.pos[i * 3] = p[0];
                face.pos[i * 3 + 1] = p[1];
                face.pos[i * 3 + 2] = p[2];
                float[] u = uvQuad.get(src);
                face.uv[i * 2] = u[0];
                face.uv[i * 2 + 1] = u[1];
                float[] n = nQuad.get(src);
                float flip = reversed ? -1.0f : 1.0f;
                face.norm[i * 3] = n[0] * flip;
                face.norm[i * 3 + 1] = n[1] * flip;
                face.norm[i * 3 + 2] = n[2] * flip;
            }
            faces.add(face);
        }
    }
}
