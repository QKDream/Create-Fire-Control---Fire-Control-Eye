package com.qkdream.firecontrolcompat.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * The area a player drew with the Sable blueprint tool, read without linking against it.
 *
 * <p>"Export the region I selected" has to consume the <em>same</em> selection the blueprint tool
 * itself saves, or the two disagree about what a region is. That selection lives in
 * {@code dev.rew1nd.sableschematicapi.tool.client.session.BlueprintToolClientSession}:
 *
 * <pre>
 *   private static Vec3 start;                                        // corner 1
 *   private static Vec3 end;                                          // corner 2
 *   public static boolean hasRange()                                  // both corners set
 *   public static AABB selectionBox()                                 // block-aligned, max + 1
 * </pre>
 *
 * <p>Read from the shipped jar {@code sable-photomancy-1.0.1.jar} (modId
 * {@code sable_schematic_api}): {@code hasRange()} at line 112, {@code selectionBox()} at 116,
 * and {@code selectionBoxTo} at 128 building {@code new AABB(minX, minY, minZ, maxX + 1, maxY + 1,
 * maxZ + 1)} out of {@code BlockPos.containing(start/end)}. In the library mode the tool sets the
 * corners with two left clicks ({@code BlueprintLibraryToolMode#handleInput} :45) and clears them
 * with shift-left-click (:50), so this is exactly what the player sees as the selection box.
 *
 * <p>That tool's input mode cannot be entered in the pack this ships with - a left click there
 * digs the block instead of picking a corner - so the same two-left-click gesture is also offered
 * by this mod, {@code /sableexport select}. Its corners live in this class as well, and are read
 * only when the tool has nothing complete to offer: one gesture, one meaning, whichever of the two
 * recorded it.
 *
 * <p>Everything about the tool is reached by reflection with {@code initialize = false} and
 * wrapped in try/catch: this mod must keep loading on a pack that has no blueprint tool at all,
 * and a selection read that fails is a sentence in the log rather than a crash in a command.
 */
public final class RegionSelection {

    /** The session class that owns the selection. */
    public static final String SESSION_CLASS =
        "dev.rew1nd.sableschematicapi.tool.client.session.BlueprintToolClientSession";

    /** How the selection was obtained, for the {@code region: selection source=} line. */
    public record Box(AABB aabb, String source, boolean bothCorners, String detail) {

        /** Volume in blocks, counting the max edge the way the tool does. */
        public long volume() {
            if (aabb == null) {
                return 0L;
            }
            long dx = (long) Math.floor(aabb.maxX) - (long) Math.floor(aabb.minX);
            long dy = (long) Math.floor(aabb.maxY) - (long) Math.floor(aabb.minY);
            long dz = (long) Math.floor(aabb.maxZ) - (long) Math.floor(aabb.minZ);
            return Math.max(0L, dx) * Math.max(0L, dy) * Math.max(0L, dz);
        }

        /** {@code (minX, minY, minZ..maxX, maxY, maxZ)} with the max edge made inclusive. */
        public String describe() {
            if (aabb == null) {
                return "none";
            }
            return String.format(java.util.Locale.ROOT,
                "(%d, %d, %d)..(%d, %d, %d)",
                (int) Math.floor(aabb.minX), (int) Math.floor(aabb.minY), (int) Math.floor(aabb.minZ),
                Math.max((int) Math.floor(aabb.minX), (int) Math.ceil(aabb.maxX) - 1),
                Math.max((int) Math.floor(aabb.minY), (int) Math.ceil(aabb.maxY) - 1),
                Math.max((int) Math.floor(aabb.minZ), (int) Math.ceil(aabb.maxZ) - 1));
        }
    }

    private RegionSelection() {
    }

    /** Corner A of our own two-left-click selection; {@code null} until the first click. */
    private static volatile BlockPos commandA;

    /** Corner B of our own two-left-click selection; {@code null} until the second click. */
    private static volatile BlockPos commandB;

    /**
     * Records the corners clicked with our own selection mode ({@code /sableexport select}).
     *
     * <p>{@code b} is {@code null} while only the first corner has been clicked. That is not the
     * same as "no selection": {@link #read()} reports it the way it reports a half-drawn tool
     * selection, instead of exporting the single block the player happened to hit first.
     *
     * <p>Both corners are copied to immutable positions: the event carries a mutable cursor on
     * some paths, and a corner that moves after it was clicked would move the selection with it.
     */
    public static void setCommandCorners(BlockPos a, BlockPos b) {
        commandA = a == null ? null : a.immutable();
        commandB = b == null ? null : b.immutable();
    }

    /** Drops the corners clicked with our own selection mode. */
    public static void clearCommandCorners() {
        commandA = null;
        commandB = null;
    }

    /** Whether the first corner has been clicked; the box is drawn from then on. */
    public static boolean hasCommandCornerA() {
        return commandA != null;
    }

    /** Whether both corners have been clicked, i.e. there is a region to export. */
    public static boolean hasCommandCorners() {
        return commandA != null && commandB != null;
    }

    /** Corner A exactly as it was clicked, or {@code null}. */
    public static BlockPos commandCornerA() {
        return commandA;
    }

    /**
     * The box round the clicked corners, in the same shape the tool's own selection has: block
     * aligned, with the max edge pushed out by one block.
     *
     * <p>{@code null} before the first click. With only the first corner it is that one block, so
     * what is drawn while the player picks the second corner is the block they picked.
     */
    public static AABB commandBox() {
        BlockPos a = commandA;
        if (a == null) {
            return null;
        }
        BlockPos b = commandB == null ? a : commandB;
        return new AABB(
            Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1.0D, Math.max(a.getY(), b.getY()) + 1.0D,
            Math.max(a.getZ(), b.getZ()) + 1.0D);
    }

    /**
     * The selection to export: the blueprint tool's when it has both corners, otherwise our own
     * two left clicks, otherwise {@code null}.
     *
     * <p>The tool comes first because it is what the player was told to draw with, and its
     * selection is the one it shows. The clicked corners only stand in for it, and only when it
     * has nothing complete to offer - a half-drawn tool selection (one corner, which would save
     * as a single block) loses to a complete click selection rather than hiding it.
     */
    public static Box read() {
        Box tool = readTool();
        if (tool != null && tool.bothCorners()) {
            return tool;
        }
        Box clicks = readClicks();
        if (clicks != null) {
            return clicks;
        }
        return tool;
    }

    /** Whether the blueprint tool is on this classpath at all. */
    public static boolean available() {
        return load() != null;
    }

    /**
     * The blueprint tool's own selection, or {@code null} when it has none or is not installed.
     *
     * <p>The public readers are tried first; the two private corners are the fallback, kept
     * because the public readers are a courtesy of the tool's author and could be renamed while
     * the fields stay - they are what its own save path reads.
     */
    private static Box readTool() {
        Class<?> session = load();
        if (session == null) {
            return null;
        }
        Box viaApi = readViaApi(session);
        if (viaApi != null) {
            return viaApi;
        }
        return readViaFields(session);
    }

    /**
     * Our own two-left-click selection, or {@code null} when nothing was clicked.
     *
     * <p>The source is the word {@code clicks} and nothing else: it is what the
     * {@code region: selection source=} line answers the player's "did that come from the tool or
     * from my own clicks" with.
     */
    private static Box readClicks() {
        AABB box = commandBox();
        if (box == null) {
            return null;
        }
        boolean both = hasCommandCorners();
        return new Box(box, "clicks", both, both ? "two-corners" : "start-only");
    }

    private static Class<?> load() {
        try {
            ClassLoader loader = RegionSelection.class.getClassLoader();
            return Class.forName(SESSION_CLASS, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Box readViaApi(Class<?> session) {
        try {
            java.lang.reflect.Method hasRange = session.getMethod("hasRange");
            java.lang.reflect.Method box = session.getMethod("selectionBox");
            Object raw = box.invoke(null);
            if (!(raw instanceof AABB aabb)) {
                return null;
            }
            Object range = hasRange.invoke(null);
            boolean both = range instanceof Boolean b && b;
            // The source is the word the log line reports; which reader answered is in the detail,
            // because "which of the two recorded this region" is the question that line answers.
            return new Box(aabb, "sable", both,
                both ? "two-corners via selectionBox()" : "start-only via selectionBox()");
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.info(
                "region: selection api unavailable ({}) - falling back to the corner fields",
                t.toString());
            return null;
        }
    }

    private static Box readViaFields(Class<?> session) {
        try {
            net.minecraft.world.phys.Vec3 start = staticVec3(session, "start");
            net.minecraft.world.phys.Vec3 end = staticVec3(session, "end");
            if (start == null) {
                return null;
            }
            net.minecraft.core.BlockPos a = net.minecraft.core.BlockPos.containing(start);
            net.minecraft.core.BlockPos b = net.minecraft.core.BlockPos.containing(end == null ? start : end);
            double minX = Math.min(a.getX(), b.getX());
            double minY = Math.min(a.getY(), b.getY());
            double minZ = Math.min(a.getZ(), b.getZ());
            double maxX = Math.max(a.getX(), b.getX()) + 1.0;
            double maxY = Math.max(a.getY(), b.getY()) + 1.0;
            double maxZ = Math.max(a.getZ(), b.getZ()) + 1.0;
            return new Box(new AABB(minX, minY, minZ, maxX, maxY, maxZ),
                "sable", end != null,
                end == null ? "start-only via the corner fields" : "two-corners via the corner fields");
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.info("region: selection unreadable ({})", t.toString());
            return null;
        }
    }

    private static net.minecraft.world.phys.Vec3 staticVec3(Class<?> owner, String name)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        return value instanceof net.minecraft.world.phys.Vec3 vec ? vec : null;
    }
}
