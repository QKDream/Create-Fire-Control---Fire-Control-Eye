package com.qkdream.firecontrolcompat.blueprint;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
     * The index given to the blueprint's own body, shifted clear of {@code sub_levels[0]}.
     *
     * <p>Most blueprints keep nothing in the main template - a zero-sized body is normal,
     * because the blueprint tool puts everything in sub-levels - but the index has to be
     * reserved so the two can never be confused for one another in the manifest.
     */
    private static final int MAIN_BODY = -1;

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
    public static int bake(CompoundTag root, String name) throws IOException {
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

    /** How many partial-block lines the log may carry per export. */
    private static int PARTIAL_LOGGED = 0;

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
        for (Direction dir : dirs) {
            for (RenderType layer : layers) {
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
                continue;
            }
            var renderer = dispatcher.getRenderer(be);
            if (renderer == null) {
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
            PoseStack local = new PoseStack();
            collector.setPoseWithOffset(frame, translationOf(pos));
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
            try {
                // `local`, not the shared `pose`: the renderer follows the pose it is handed,
                // and the collector adds the frame. Handing it the shared pose - which already
                // contains the frame - put block-entity geometry through the frame twice, which
                // is why it rendered in the wrong place while the model faces were right.
                local.pushPose();
                renderer.render(be, 0.0f, local, capture, 0, 0);
                local.popPose();
                // Back to the model convention, so a quad emitted afterwards is not left in
                // atlas coordinates because a renderer happened to run before it.
                collector.setUvNeedsAtlasConversion(true);
                drawn++;
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
                // that ticks, a player, a rendered entity - must not take the export down.
                skipped++;
                if (DIAGNOSTICS) {
                    BlueprintExporter.LOGGER.warn(
                        "block entity {} at {} did not render: {}",
                        be.getType(), pos, t.toString());
                }
            } finally {
                collector.unpinMaterial();
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
            // compared against another without anyone judging a render by eye.
            String affinity = blockAffinity(w, placedVertices);
            if (affinity != null) {
                BlueprintExporter.LOGGER.info("DIAG affinity sub{}: {}", subLevel, affinity);
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
    private static String materialNameFor(String texture) {
        int colon = texture.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : texture.substring(0, colon);
        String path = colon < 0 ? texture : texture.substring(colon + 1);
        return namespace + "_" + path.replace('/', '_');
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

        BlockEntityCapture(Collector collector) {
            this.collector = collector;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) {
            if (type != null) {
                // The block's own sprite first: the render type can only offer the atlas, whose
                // pixels cannot be copied. See spritePathForBlock.
                String texture = textures.computeIfAbsent(type,
                    t -> spritePathForBlock(currentState, t));
                // Named after the block AND this buffer's texture, so a renderer drawing several
                // partial models - the belt and the suspension mount of one track, say - keeps one
                // material per texture instead of the last one overwriting the rest. See
                // blockEntityMaterialName.
                String name = currentBlockLabel == null ? null
                    : currentBlockLabel + "__" + materialNameFor(texture);
                collector.pinMaterial(name, texture);
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
    private static net.minecraft.client.renderer.texture.TextureAtlasSprite spriteContaining(
            float u, float v) {
        net.minecraft.client.renderer.texture.TextureAtlasSprite best = null;
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
            collector.addVertex(point.x, point.y, point.z, -1, u, v, 0, 0,
                                snapAxis(direction.x), snapAxis(direction.y),
                                snapAxis(direction.z));
        }
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
        for (Face f : collector.faces) {
            byMaterial.computeIfAbsent(f.material, k -> new ArrayList<>()).add(f);
            materialAtlas.putIfAbsent(f.material, f.atlasPath);
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
            String textureFile = writeSpritePng(outDir, entry.getValue());
            materialFile.put(entry.getKey(), textureFile);
        }

        try (BufferedWriter w = Files.newBufferedWriter(mtl)) {
            w.write("# Sable blueprint export materials\n");
            for (String mat : byMaterial.keySet()) {
                w.write("\nnewmtl " + mat + "\n");
                w.write("Kd 1.000 1.000 1.000\nKa 0.000 0.000 0.000\nKs 0.000 0.000 0.000\n");
                w.write("d 1.0\nillum 1\n");
                String file = materialFile.get(mat);
                if (file != null) {
                    // Relative to the output folder, with forward slashes, which is what
                    // Blender's OBJ importer resolves against the .mtl's own directory.
                    w.write("map_Kd " + file + "\n");
                }
            }
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
    private static String writeSpritePng(Path outDir, String texturePath) {
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
                try (var stream = resource.get().open()) {
                    Files.copy(stream, target,
                               java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

        Face(int count, String material, String atlasPath, int subLevel) {
            this(count, material, atlasPath, subLevel, -1);
        }

        Face(int count, String material, String atlasPath, int subLevel,
             int blockEntitySubLevel) {
            this.count = count;
            this.pos = new float[count * 3];
            this.uv = new float[count * 2];
            this.norm = new float[count * 3];
            this.material = material;
            this.atlasPath = atlasPath;
            this.subLevel = subLevel;
            this.blockEntitySubLevel = blockEntitySubLevel;
        }
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
         * Name the material for the next quad explicitly.
         *
         * <p>For geometry that arrives without a sprite: a block-entity renderer writes
         * straight into a VertexConsumer and never says which texture it used, but the
         * RenderType it asked for carries the location. Using that is what gives a barrel or
         * a belt a material the .mtl can resolve, rather than a stale name from the previous
         * block.
         */
        void pinMaterial(String material, String texturePath) {
            // The material is changing, so the group that was running has finished and its UV
            // conversion can be reported.
            flushUvSummary(this.currentMaterial);
            if (material != null && !material.isEmpty()) {
                this.currentMaterial = material;
            }
            this.currentAtlasPath = texturePath;
            this.materialPinned = true;
            this.pinnedUMin = Float.MAX_VALUE;
            this.pinnedUMax = -Float.MAX_VALUE;
            this.pinnedVMin = Float.MAX_VALUE;
            this.pinnedVMax = -Float.MAX_VALUE;
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

        /** Let the next sprite decide the material again. */
        void unpinMaterial() {
            this.materialPinned = false;
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
                this.currentMaterial = id.getNamespace() + "_" + id.getPath().replace('/', '_');
                this.currentAtlasPath = id.getNamespace() + ":" + id.getPath();
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

        /** Discard a partially accumulated face, so nothing leaks into the next block. */
        void resetQuad() {
            quad.clear();
            uvQuad.clear();
            nQuad.clear();
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
            // Stage 0 either way. `setPoseWithOffset` folds the block offset into the frame
            // and zeroes the separate offset, so the same expression covers a block-entity
            // renderer's raw vertex and a baked quad's.
            org.joml.Vector3f world = place(x, y, z, 0);
            quad.add(new float[] {world.x, world.y, world.z});
            uvQuad.add(new float[] {0f, 0f});
            nQuad.add(new float[] {0f, 1f, 0f});
            // Only a model that feeds positions without uv or normal relies on this
            // path; it cannot build a face until something supplies them, so it is
            // capped rather than emitting garbage.
            if (quad.size() >= 4) {
                flush();
            }
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
                    this.resolvedTilePath = tile.contents().name().getNamespace() + ":"
                        + tile.contents().name().getPath();
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
            // A pinned material means a block-entity renderer chose it, which is the signal
            // that these faces should be grouped by texture rather than by sub-level.
            Face face = new Face(count, currentMaterial, currentAtlasPath, currentSubLevel,
                                 materialPinned ? currentSubLevel : -1);
            for (int i = 0; i < count; i++) {
                float[] p = quad.get(i);
                face.pos[i * 3] = p[0];
                face.pos[i * 3 + 1] = p[1];
                face.pos[i * 3 + 2] = p[2];
                float[] u = uvQuad.get(i);
                face.uv[i * 2] = u[0];
                face.uv[i * 2 + 1] = u[1];
                float[] n = nQuad.get(i);
                face.norm[i * 3] = n[0];
                face.norm[i * 3 + 1] = n[1];
                face.norm[i * 3 + 2] = n[2];
            }
            faces.add(face);
        }
    }
}
