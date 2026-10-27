package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.sonar.ManportableSonarBlockEntity;
import com.qkdream.firecontrolcompat.sonar.SonarContact;
import com.qkdream.firecontrolcompat.sonar.SonarKind;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * The marks a man-portable set is holding on to, shown through whatever is in
 * the way.
 *
 * <p>A mark points at the source of the noise itself. Where that source is a
 * thing standing in the world - a creature, a torpedo, a warhead - it is lit
 * with the game's own glow as well, so the player sees its shape where the two
 * of them can see one another. Where the source is a structure the mark is not
 * a box round it but her own outline - the rim of every surface she shows, and
 * not a line round every block in her - because the box a hull answers with is
 * the plot she was welded on rather than the ship the player is looking for. A source with no shape at all - a gun going off, a block going down -
 * keeps the small box round the place it was heard.</p>
 *
 * <p>Every mark is drawn with the depth test off, so it is seen through hills,
 * through hulls and through the water, which is the whole point of a listening
 * set: a mark that the shoreline can hide answers nothing. The colour is the
 * weight of the source, the same ramp the dish sets paint their pictures with,
 * so a gun going off reads as loud at a glance.</p>
 *
 * <p>The pass runs off the level's own model view matrix with the camera taken
 * off every vertex, which is how fire control draws its own world overlays, and
 * it is drawn by hand rather than through a render type so the depth test can
 * be left switched off for it alone.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ManportableSonarHighlight {

    /** How far from the player a mark is still drawn. */
    private static final double VISIBLE_RANGE = 128.0D;
    /** Side of the box drawn round a plain noise, in blocks. */
    private static final double MARK_SIDE = 1.4D;
    /** How close a mark has to be to a hull's centre to be that hull's. */
    private static final double BODY_MATCH = 8.0D;
    /** How close a source has to be to a mark to be the thing that made it. */
    private static final double SOURCE_MATCH = 6.0D;
    /** How long a source stays lit after the set last heard it, in ticks. */
    private static final long GLOW_TICKS = 100L;
    /** Ticks between two reports of what the pass had in hand. */
    private static final long REPORT_TICKS = 100L;
    /** Ticks between two passes over the sources the sets are hearing. */
    private static final long SOURCE_PERIOD = 5L;
    /** Ticks the list of hulls in the world is reused for. */
    private static final long HULL_PERIOD = 10L;
    /** Ticks a structure's block outline is reused for. */
    private static final long OUTLINE_PERIOD = 20L;
    /** Block reads spent on one structure's outline, as a guard on a huge plot. */
    private static final int OUTLINE_WALK_LIMIT = 262144;
    /** Lines kept for one structure; a hull with more of them is drawn as far as she got. */
    private static final int OUTLINE_SEGMENT_LIMIT = 12000;
    /** Structures whose outline is walked in one tick; the rest wait their turn. */
    private static final int OUTLINE_REBUILDS = 1;
    /** How solid a structure's block outline is drawn. */
    private static final float HULL_ALPHA = 0.45F;
    /** How solid a box round a plain noise is drawn. */
    private static final float MARK_ALPHA = 0.6F;

    /** The twelve edges of a cube, as corner indices. */
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /** The six ways a block can face, in the order the game lists them. */
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Every face of a block, with the block beside each of her lines. */
    private static final Face[] FACES = faces();

    /** Works out where the four corners of a face sit and what answers each of her lines. */
    private static Face[] faces() {
        Face[] found = new Face[DIRECTIONS.length];
        for (int index = 0; index < DIRECTIONS.length; index++) {
            Direction side = DIRECTIONS[index];
            int axis = side.getAxis().ordinal();
            int across = (axis + 1) % 3;
            int along = (axis + 2) % 3;
            int at = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            int[] corners = new int[12];
            for (int corner = 0; corner < 4; corner++) {
                int[] offsets = new int[3];
                if (corner == 1 || corner == 2) {
                    offsets[across] = 1;
                }
                if (corner == 2 || corner == 3) {
                    offsets[along] = 1;
                }
                offsets[axis] = at;
                corners[corner * 3] = offsets[0];
                corners[corner * 3 + 1] = offsets[1];
                corners[corner * 3 + 2] = offsets[2];
            }
            Direction[] beside = {
                    way(along, -1), way(across, 1), way(along, 1), way(across, -1)
            };
            found[index] = new Face(side, corners, beside);
        }
        return found;
    }

    /** The direction along one axis, either way. */
    private static Direction way(int axis, int sign) {
        return Direction.fromAxisAndDirection(
                Direction.Axis.values()[axis],
                sign > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
    }

    /** Scratch for turning a structure's own coordinates into the world's. */
    private static final Vector3d LOCAL = new Vector3d();
    private static final Vector3d WORLD = new Vector3d();

    /** The one mask the walk reads her blocks into; kept, so a walk leaves no litter. */
    private static final Mask MASK = new Mask();

    private static long lastReport = Long.MIN_VALUE;
    /** Sources this pass lit, against the tick their light goes out. */
    private static final Map<Integer, Long> LIT = new HashMap<>();
    /** The world the lit sources belong to: ids mean nothing once it changes. */
    private static Level world;
    private static long lastSourcePass = Long.MIN_VALUE;
    private static List<Hull> hulls = List.of();
    private static long hullsAt = Long.MIN_VALUE;
    private static final Map<UUID, Outline> OUTLINES = new HashMap<>();
    private static long outlineTick = Long.MIN_VALUE;
    private static int outlinesRebuilt;

    private ManportableSonarHighlight() {
    }

    /** A structure in the world, with the box she answers with. */
    private record Hull(SubLevel sub, AABB box) {
    }

    /** One face of a block: the way she faces, her four corners and the block beside each line. */
    private record Face(Direction side, int[] corners, Direction[] beside) {
    }

    /** The outline of one structure, in her own space: line ends, and when read. */
    private record Outline(float[] segments, int count, long readAt) {
    }

    /**
     * A bit per block of one structure's plot, so a face can ask what stands
     * beside it without the world being read again for every line.
     */
    private static final class Mask {

        private long[] words = new long[0];
        private int minX;
        private int minY;
        private int minZ;
        private int sizeX;
        private int sizeY;
        private int sizeZ;
        private int filled;

        void reset(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.filled = 0;
            int needed = (sizeX * sizeY * sizeZ + 63) >>> 6;
            if (words.length < needed) {
                words = new long[needed];
            } else {
                Arrays.fill(words, 0, needed, 0L);
            }
        }

        /** How many blocks of her were found, so the lines can be sized to her. */
        int filled() {
            return filled;
        }

        void set(int x, int y, int z) {
            if (!inside(x, y, z)) {
                return;
            }
            int index = index(x, y, z);
            long bit = 1L << (index & 63);
            int word = index >>> 6;
            if ((words[word] & bit) == 0L) {
                words[word] |= bit;
                filled++;
            }
        }

        boolean has(int x, int y, int z) {
            if (!inside(x, y, z)) {
                return false;
            }
            int index = index(x, y, z);
            return (words[index >>> 6] & (1L << (index & 63))) != 0L;
        }

        private boolean inside(int x, int y, int z) {
            int dx = x - minX;
            int dy = y - minY;
            int dz = z - minZ;
            return dx >= 0 && dy >= 0 && dz >= 0 && dx < sizeX && dy < sizeY && dz < sizeZ;
        }

        private int index(int x, int y, int z) {
            return (x - minX) + sizeX * ((y - minY) + sizeY * (z - minZ));
        }
    }

    /**
     * Lights whatever is making the noise a set is hearing.
     *
     * <p>The light is the pass's own: a source already glowing for a reason of
     * its own is left to it, and every light this pass put on is taken back off
     * once the mark behind it has gone quiet, so a set never leaves anything in
     * the world lit up behind it. The look is thinned to one pass every few
     * ticks, because a light that lasts a hundred ticks does not need to be
     * renewed twenty times a second.</p>
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            forget();
            return;
        }
        if (world != level) {
            LIT.clear();
            OUTLINES.clear();
            hulls = List.of();
            hullsAt = Long.MIN_VALUE;
            lastSourcePass = Long.MIN_VALUE;
            world = level;
        }
        long now = level.getGameTime();
        if (now - lastSourcePass >= SOURCE_PERIOD) {
            lastSourcePass = now;
            for (ManportableSonarBlockEntity set : ManportableSonarBlockEntity.clientSets()) {
                List<Entity> sources = sourcesIn(level, set);
                for (SonarContact contact : set.contacts()) {
                    Entity source = sourceOf(sources, contact, minecraft.player);
                    if (source == null) {
                        continue;
                    }
                    if (source.isCurrentlyGlowing() && !LIT.containsKey(source.getId())) {
                        continue;
                    }
                    source.setGlowingTag(true);
                    LIT.put(source.getId(), now + GLOW_TICKS);
                }
            }
            if (OUTLINES.size() > 64) {
                OUTLINES.entrySet().removeIf(entry -> now - entry.getValue().readAt() > 200L);
            }
        }
        if (LIT.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Long>> entries = LIT.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, Long> entry = entries.next();
            if (entry.getValue() >= now) {
                continue;
            }
            Entity lit = level.getEntity(entry.getKey());
            if (lit != null) {
                lit.setGlowingTag(false);
            }
            entries.remove();
        }
    }

    private static void forget() {
        LIT.clear();
        OUTLINES.clear();
        hulls = List.of();
        hullsAt = Long.MIN_VALUE;
        lastSourcePass = Long.MIN_VALUE;
        world = null;
    }

    /** Everything standing round a set that could have made one of its marks. */
    private static List<Entity> sourcesIn(Level level, ManportableSonarBlockEntity set) {
        try {
            return level.getEntitiesOfClass(Entity.class,
                    new AABB(set.getBlockPos()).inflate(ManportableSonarBlockEntity.RANGE));
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /**
     * The thing that made a mark, or null when the mark has nothing to point
     * at: a hull, a gun going off, a block going down somewhere in the world.
     */
    private static Entity sourceOf(List<Entity> sources, SonarContact contact, Player self) {
        SonarKind kind = contact.kind();
        if (kind != SonarKind.MOB && kind != SonarKind.AMMO && kind != SonarKind.TORPEDO
                && kind != SonarKind.MISSILE) {
            return null;
        }
        Vec3 at = contact.position();
        Entity found = null;
        double best = SOURCE_MATCH * SOURCE_MATCH;
        for (Entity source : sources) {
            if (source == null || source.isRemoved() || source == self || !makesNoise(kind, source)) {
                continue;
            }
            double distance = source.position().distanceToSqr(at);
            if (distance <= best) {
                best = distance;
                found = source;
            }
        }
        return found;
    }

    /**
     * Whether a source is of the sort a mark of this kind was made by. A player
     * is left out of the lit ones: a set marking one is telling the player where
     * a body is, not drawing them through the hillside.
     */
    private static boolean makesNoise(SonarKind kind, Entity source) {
        if (kind == SonarKind.MOB) {
            return source instanceof LivingEntity && !(source instanceof Player);
        }
        return !(source instanceof LivingEntity);
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        List<ManportableSonarBlockEntity> sets = ManportableSonarBlockEntity.clientSets();
        if (sets.isEmpty()) {
            return;
        }
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            draw(event, minecraft, sets);
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void draw(RenderLevelStageEvent event, Minecraft minecraft, List<ManportableSonarBlockEntity> sets) {
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = new PoseStack();
        pose.mulPose(event.getModelViewMatrix());
        Matrix4f matrix = pose.last().pose();
        BufferBuilder lines = Tesselator.getInstance()
                .begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        if (lines == null) {
            return;
        }
        long now = minecraft.level.getGameTime();
        List<Hull> hulls = hulls(minecraft, now);
        int held = 0;
        int drawn = 0;
        int outlined = 0;
        for (ManportableSonarBlockEntity set : sets) {
            for (SonarContact contact : set.contacts()) {
                held++;
                Vec3 at = contact.position();
                if (at == null || at.distanceToSqr(camera) > VISIBLE_RANGE * VISIBLE_RANGE) {
                    continue;
                }
                int colour = contact.kind().weight().color();
                if (contact.kind() == SonarKind.BODY) {
                    Hull hull = nearestHull(hulls, at);
                    if (hull != null) {
                        int blocks = drawHull(hull, now, lines, matrix, camera, colour);
                        if (blocks > 0) {
                            outlined += blocks;
                            drawn++;
                            continue;
                        }
                        drawBox(lines, matrix, hull.box(), colour, camera);
                        drawn++;
                        continue;
                    }
                }
                drawBox(lines, matrix, boxAround(at), colour, camera);
                drawn++;
            }
        }
        flush(lines);
        report(minecraft, sets.size(), held, drawn, outlined);
    }

    /**
     * Says once every few seconds what the pass had to draw, so a set whose
     * marks never arrive on the client can be told from one whose marks arrive
     * and are drawn somewhere the player is not looking.
     */
    private static void report(Minecraft minecraft, int sets, int held, int drawn, int outlined) {
        if (minecraft.level == null) {
            return;
        }
        long now = minecraft.level.getGameTime();
        if (lastReport != Long.MIN_VALUE && now - lastReport < REPORT_TICKS) {
            return;
        }
        lastReport = now;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] manportable marks sets={} held={} drawn={} lines={} lit={} camera={}",
                sets, held, drawn, outlined, LIT.size(), minecraft.gameRenderer.getMainCamera().getPosition());
    }

    /** The marks are written by hand so the depth test can be left off for them alone. */
    private static void flush(BufferBuilder lines) {
        MeshData mesh = lines.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        try {
            BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    /** Every hull the client knows about, reread now and then rather than per frame. */
    private static List<Hull> hulls(Minecraft minecraft, long now) {
        if (hullsAt != Long.MIN_VALUE && now - hullsAt < HULL_PERIOD) {
            return hulls;
        }
        List<Hull> found = new ArrayList<>();
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
            for (SubLevel sub : container == null ? List.<SubLevel>of() : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                found.add(new Hull(sub, sub.boundingBox().toMojang()));
            }
        } catch (Throwable ignored) {
        }
        hulls = List.copyOf(found);
        hullsAt = now;
        return hulls;
    }

    /** The hull a mark belongs to, or null when it is not a hull's. */
    private static Hull nearestHull(List<Hull> hulls, Vec3 at) {
        Hull best = null;
        double bestDistance = BODY_MATCH * BODY_MATCH;
        for (Hull hull : hulls) {
            double distance = hull.box().getCenter().distanceToSqr(at);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = hull;
            }
        }
        return best;
    }

    /** The small box round a noise that has no shape of its own. */
    private static AABB boxAround(Vec3 at) {
        return new AABB(
                at.x - MARK_SIDE / 2.0D, at.y - MARK_SIDE / 2.0D, at.z - MARK_SIDE / 2.0D,
                at.x + MARK_SIDE / 2.0D, at.y + MARK_SIDE / 2.0D, at.z + MARK_SIDE / 2.0D);
    }

    /**
     * Draws a structure as the outline of the whole shape, so the mark is the
     * shape the player is looking for rather than the plot she was welded on.
     *
     * <p>The lines are worked out in her own space and kept for a second or so,
     * one structure per tick at the most, so a hall full of hulls does not put
     * the walk on the frame. A frame only turns them into the world and writes
     * them out, and asks nothing of the world at all, so a hull that is moving,
     * or a camera that is, costs exactly what a still one does.</p>
     */
    private static int drawHull(Hull hull, long now, BufferBuilder lines, Matrix4f matrix, Vec3 camera, int colour) {
        Outline outline = outline(hull, now);
        if (outline == null || outline.count() <= 0) {
            return 0;
        }
        Pose3dc pose;
        try {
            pose = hull.sub().logicalPose();
        } catch (Throwable ignored) {
            return 0;
        }
        if (pose == null) {
            return 0;
        }
        float red = ((colour >> 16) & 0xFF) / 255.0F;
        float green = ((colour >> 8) & 0xFF) / 255.0F;
        float blue = (colour & 0xFF) / 255.0F;
        float[] segments = outline.segments();
        for (int index = 0; index < outline.count(); index++) {
            int base = index * 6;
            line(lines, matrix, red, green, blue, pose, camera,
                    segments[base], segments[base + 1], segments[base + 2],
                    segments[base + 3], segments[base + 4], segments[base + 5]);
        }
        return outline.count();
    }

    /** One line of a structure's outline, still in her own space. */
    private static void line(BufferBuilder lines, Matrix4f matrix, float red, float green, float blue,
                             Pose3dc pose, Vec3 camera,
                             float x0, float y0, float z0, float x1, float y1, float z1) {
        try {
            LOCAL.set(x0, y0, z0);
            Vector3d from = pose.transformPosition(LOCAL, WORLD);
            lines.addVertex(matrix,
                            (float) (from.x - camera.x), (float) (from.y - camera.y), (float) (from.z - camera.z))
                    .setColor(red, green, blue, HULL_ALPHA);
            LOCAL.set(x1, y1, z1);
            Vector3d to = pose.transformPosition(LOCAL, WORLD);
            lines.addVertex(matrix,
                            (float) (to.x - camera.x), (float) (to.y - camera.y), (float) (to.z - camera.z))
                    .setColor(red, green, blue, HULL_ALPHA);
        } catch (Throwable ignored) {
        }
    }

    /** The outline of a structure, read again when the copy in hand has gone stale. */
    private static Outline outline(Hull hull, long now) {
        UUID id;
        try {
            id = hull.sub().getUniqueId();
        } catch (Throwable ignored) {
            return null;
        }
        if (id == null) {
            return null;
        }
        Outline cached = OUTLINES.get(id);
        if (cached != null && now - cached.readAt() < OUTLINE_PERIOD) {
            return cached;
        }
        if (now != outlineTick) {
            outlineTick = now;
            outlinesRebuilt = 0;
        }
        if (outlinesRebuilt >= OUTLINE_REBUILDS) {
            return cached;
        }
        outlinesRebuilt++;
        Outline built = walk(hull.sub(), now);
        OUTLINES.put(id, built);
        return built;
    }

    /**
     * Walks a structure's plot and returns the lines of her outside.
     *
     * <p>Her blocks are read into a mask first, because whether a face belongs
     * to the outside depends on what stands beside it. The lines are then taken
     * off the mask: every face with nothing in front of it is outside, and of
     * the four lines round it only those she does not carry on across are kept.
     * A flat wall therefore answers with one long rectangle instead of a grid of
     * squares and a corner with the two rims that meet there, so what is drawn
     * is the outline of the whole shape rather than a line round every block of
     * her.</p>
     *
     * <p>A plot too big to read in one pass is left to the box she answers with
     * rather than wearing the frame down over a shape read in part.</p>
     */
    private static Outline walk(SubLevel sub, long now) {
        try {
            BoundingBox3ic plot = sub.getPlot() == null ? null : sub.getPlot().getBoundingBox();
            BlockGetter blocks = sub.getLevel();
            if (plot == null || blocks == null) {
                return new Outline(new float[0], 0, now);
            }
            int sizeX = plot.maxX() - plot.minX() + 1;
            int sizeY = plot.maxY() - plot.minY() + 1;
            int sizeZ = plot.maxZ() - plot.minZ() + 1;
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
                    || (long) sizeX * sizeY * sizeZ > OUTLINE_WALK_LIMIT) {
                return new Outline(new float[0], 0, now);
            }
            Mask mask = MASK;
            mask.reset(plot.minX(), plot.minY(), plot.minZ(), sizeX, sizeY, sizeZ);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int y = plot.minY(); y <= plot.maxY(); y++) {
                for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                    for (int x = plot.minX(); x <= plot.maxX(); x++) {
                        try {
                            if (!blocks.getBlockState(cursor.set(x, y, z)).isAir()) {
                                mask.set(x, y, z);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            int room = Math.min(OUTLINE_SEGMENT_LIMIT, Math.max(64, mask.filled() * 3));
            float[] segments = new float[room * 6];
            int count = 0;
            done:
            for (int y = plot.minY(); y <= plot.maxY(); y++) {
                for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                    for (int x = plot.minX(); x <= plot.maxX(); x++) {
                        if (!mask.has(x, y, z)) {
                            continue;
                        }
                        for (Face face : FACES) {
                            Direction side = face.side();
                            if (mask.has(x + side.getStepX(), y + side.getStepY(), z + side.getStepZ())) {
                                continue;
                            }
                            int[] corners = face.corners();
                            Direction[] beside = face.beside();
                            for (int edge = 0; edge < 4; edge++) {
                                if (count >= room) {
                                    break done;
                                }
                                Direction along = beside[edge];
                                int nx = x + along.getStepX();
                                int ny = y + along.getStepY();
                                int nz = z + along.getStepZ();
                                if (mask.has(nx, ny, nz)
                                        && !mask.has(nx + side.getStepX(), ny + side.getStepY(),
                                                nz + side.getStepZ())) {
                                    continue;
                                }
                                int base = count * 6;
                                segments[base] = x + corners[edge * 3];
                                segments[base + 1] = y + corners[edge * 3 + 1];
                                segments[base + 2] = z + corners[edge * 3 + 2];
                                int next = ((edge + 1) & 3) * 3;
                                segments[base + 3] = x + corners[next];
                                segments[base + 4] = y + corners[next + 1];
                                segments[base + 5] = z + corners[next + 2];
                                count++;
                            }
                        }
                    }
                }
            }
            return new Outline(segments, count, now);
        } catch (Throwable ignored) {
            return new Outline(new float[0], 0, now);
        }
    }

    /** One marker box, in the colour of the weight it stands for. */
    private static void drawBox(BufferBuilder lines, Matrix4f matrix, AABB box, int colour, Vec3 camera) {
        float red = ((colour >> 16) & 0xFF) / 255.0F;
        float green = ((colour >> 8) & 0xFF) / 255.0F;
        float blue = (colour & 0xFF) / 255.0F;
        double x0 = box.minX - 0.05D;
        double y0 = box.minY - 0.05D;
        double z0 = box.minZ - 0.05D;
        double x1 = box.maxX + 0.05D;
        double y1 = box.maxY + 0.05D;
        double z1 = box.maxZ + 0.05D;
        double[][] corners = {
                {x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0},
                {x0, y0, z1}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1}
        };
        for (int[] edge : EDGES) {
            vertex(lines, matrix, red, green, blue, MARK_ALPHA,
                    new Vec3(corners[edge[0]][0], corners[edge[0]][1], corners[edge[0]][2]), camera);
            vertex(lines, matrix, red, green, blue, MARK_ALPHA,
                    new Vec3(corners[edge[1]][0], corners[edge[1]][1], corners[edge[1]][2]), camera);
        }
    }

    private static void vertex(BufferBuilder lines, Matrix4f matrix, float red, float green, float blue, float alpha,
                               Vec3 at, Vec3 camera) {
        lines.addVertex(matrix, (float) (at.x - camera.x), (float) (at.y - camera.y), (float) (at.z - camera.z))
                .setColor(red, green, blue, alpha);
    }
}
