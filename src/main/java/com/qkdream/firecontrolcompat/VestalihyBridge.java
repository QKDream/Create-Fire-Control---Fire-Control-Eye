package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import dev.ryanhcode.sable.Sable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bridge to vestalihy's tube launchers (9M120, 9M14, TOW).
 *
 * <p>vestalihy is a soft dependency: nothing here names one of its classes.
 * The launcher mixin stamps the {@link Launcher} face onto the shared tube
 * entity, so a bound launcher can be found, asked whether it is loaded and
 * fired without reflection, and a missing mod simply means the face is never
 * stamped and every method below finds nothing.</p>
 *
 * <p>Only the two guided rounds are handed to the fire control computer.
 * The 9M14 keeps its own keyboard steering, so it is left out of the guided
 * set and its flight is never touched.</p>
 *
 * <p>A structure keeps its blocks and entities in the plot region its sub-level
 * owns, which is nowhere near where the structure is seen, and both vestalihy
 * and the fire control computer move positions between the two spaces with the
 * pose of that sub-level. Comparing a tube on a ship with a computer on the
 * ground therefore only works in the space the player sees, which is what the
 * methods here do, so a tube and a computer are free to sit on different
 * structures.</p>
 */
public final class VestalihyBridge {

    public static final String MOD_ID = "vestalihy";

    /** Missile entity ids the fire control computer flies itself. */
    // The ids vestalihy actually registers for its rounds: ptur is the 9M120,
// ptur_jet its rocket boosted round and tow the TOW-2A. "kd" only ever names
// the launcher containers (tubus_kd, tubus_tow_kd), never a round.
    private static final Set<String> GUIDED_MISSILE_IDS = Set.of("ptur", "ptur_jet", "tow");

    /** Search radius around a bound position when looking for the launcher entity. */
    private static final double LAUNCHER_RADIUS = 2.5;
    private static final double CONTROLLER_RADIUS = 32.0;

    /** How far below a tube the binding anchor looks for a block to hold on to. */
    private static final int ANCHOR_DEPTH = 6;

    /** How far around a bound block its tube is searched for. */
    private static final double TUBE_SCAN_RADIUS = ANCHOR_DEPTH + 4.0;

    /** Seconds between two reports about the same unmatched bound position. */
    private static final long UNMATCHED_REPORT_SECONDS = 60L;

    /** Positions whose unmatched tube was already reported. */
    private static final Map<Long, Long> UNMATCHED_REPORTED = new ConcurrentHashMap<>();

    /** How far around a muzzle the round that just left it is searched for. */
    private static final double ROUND_STAMP_RADIUS = 4.0;

    /** Entity id of the vestalihy round the fire control only launches. */
    private static final String MALYTKA_ROUND = "malytka";

    /** Where a tube remembers the block its binding points at. */
    private static final String ANCHOR_TAG = "CreateFireControlAnchor";

    /** Where a round remembers the launcher binding it left. */
    private static final String ROUND_REF_TAG = "CreateFireControlLaunchRef";

    private VestalihyBridge() {
    }

    /** Face stamped onto vestalihy's tube launcher entity. */
    public interface Launcher {

        boolean fccIsEmpty();

        boolean fccLaunch();

        /** Direction the tube points, used as the launcher direction. */
        Vec3 fccDirection();
    }

    public static boolean isLoaded() {
        return FireControlCompat.isModLoaded(MOD_ID);
    }

    /**
     * Moves a position out of the sub-level that stores it, if any. A position
     * that already is a world position is handed back unchanged.
     */
    public static Vec3 worldPosition(Level level, Vec3 position) {
        if (level == null || position == null) {
            return position;
        }
        try {
            Vec3 world = Sable.HELPER.projectOutOfSubLevel(level, position);
            return finite(world) ? world : position;
        } catch (Throwable ignored) {
            return position;
        }
    }

    /** World position of a block that may live inside a sub-level. */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        return pos == null ? null : worldPosition(level, Vec3.atCenterOf(pos));
    }

    /** World position where a round leaves the bound tube. */
    public static Vec3 muzzle(Launcher launcher) {
        Entity tube = entityOf(launcher);
        return tube == null ? null : worldPosition(tube.level(), localMuzzle(tube));
    }

    /**
     * World direction the bound tube points in, keeping the heading a tube on a
     * turned or banked structure has in the world.
     */
    public static Vec3 direction(Launcher launcher) {
        Entity tube = entityOf(launcher);
        if (tube == null) {
            return null;
        }
        Vec3 axis = launcher.fccDirection();
        if (axis == null || axis.lengthSqr() < 1.0E-8) {
            return null;
        }
        Vec3 origin = tube.position();
        Vec3 worldOrigin = worldPosition(tube.level(), origin);
        Vec3 worldTip = worldPosition(tube.level(), origin.add(axis.normalize()));
        Vec3 worldAxis = worldTip.subtract(worldOrigin);
        return worldAxis.lengthSqr() < 1.0E-8 ? null : worldAxis.normalize();
    }

    /**
     * Distance between a round and the tube it just left, measured in the space
     * where the two are closest: a round fired by a ship's tube keeps the plot
     * space of that ship while the computer measures in world space, so both
     * are tried, as the fire control computer does for its own launchers.
     */
    public static double spawnDistanceSqr(Launcher launcher, Entity missile) {
        Entity tube = entityOf(launcher);
        if (tube == null || missile == null) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 localMuzzle = localMuzzle(tube);
        Vec3 worldMuzzle = worldPosition(tube.level(), localMuzzle);
        Vec3 rawMissile = missile.position();
        Vec3 worldMissile = worldPosition(missile.level(), rawMissile);
        double closest = worldMuzzle.distanceToSqr(worldMissile);
        closest = Math.min(closest, worldMuzzle.distanceToSqr(rawMissile));
        return Math.min(closest, localMuzzle.distanceToSqr(rawMissile));
    }

    /** The tube launcher standing at or next to a bound position, or null. */
    public static Launcher launcherAt(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        Launcher bound = boundLauncher(level, pos);
        if (bound != null) {
            return bound;
        }
        Launcher found = searchLauncher(level, Vec3.atCenterOf(pos));
        if (found == null) {
            Vec3 world = worldCenter(level, pos);
            if (world != null && world.distanceToSqr(Vec3.atCenterOf(pos)) > 1.0E-6) {
                found = searchLauncher(level, world);
            }
        }
        if (found == null) {
            reportUnmatchedTube(level, pos);
        }
        return found;
    }

    /**
     * The tube that was bound at a position. Every tube remembers the block its
     * reference points at, so a launcher with several tubes is told apart by its
     * own reference instead of by whichever tube happens to stand closer.
     */
    private static Launcher boundLauncher(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        AABB box = new AABB(center, center).inflate(TUBE_SCAN_RADIUS);
        Launcher inColumn = null;
        int inColumnCount = 0;
        Launcher nearby = null;
        int nearbyCount = 0;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, candidate -> candidate instanceof Launcher)) {
            if (pos.equals(rememberedAnchor(entity))) {
                return (Launcher) entity;
            }
            if (ownsColumn(entity, pos)) {
                inColumn = (Launcher) entity;
                inColumnCount++;
            } else if (entity.position().distanceToSqr(center) <= LAUNCHER_RADIUS * LAUNCHER_RADIUS) {
                nearby = (Launcher) entity;
                nearbyCount++;
            }
        }
        if (inColumnCount == 0 && carriesLauncherOfItsOwn(level, pos)) {
            return null;
        }
        // A tube that no longer carries the anchor it was bound at - it was placed
        // again, or the structure it stood on was put back together - is taken
        // back while only one tube reaches down to that very block, because the
        // block a tube picks for itself always sits in its own column. A rig that
        // shares one mount hands its tubes blocks below each other, so a tube can
        // stand several blocks above the block it was bound to and still be found.
        // Several tubes holding on to the same column keep their own references
        // instead, and a tube that merely stands beside the position is only taken
        // back while it is the single tube in reach, so nothing is guessed.
        Launcher chosen;
        if (inColumnCount == 1) {
            chosen = inColumn;
        } else if (inColumnCount == 0 && nearbyCount == 1) {
            chosen = nearby;
        } else {
            chosen = null;
        }
        if (chosen != null) {
            rememberAnchor(entityOf(chosen), pos);
        }
        return chosen;
    }

    /** True while a position sits in the column a tube reaches down for a block. */
    private static boolean ownsColumn(Entity tube, BlockPos pos) {
        BlockPos base = tube.blockPosition();
        for (int depth = 0; depth <= ANCHOR_DEPTH; depth++) {
            if (base.below(depth).equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a launcher of another mod stands on a position. The tubes of this
     * bridge hang in the air and are bound to the block they hold on to, so a
     * position that carries a launcher of its own answers for that launcher and
     * never for a tube passing by.
     */
    private static boolean carriesLauncherOfItsOwn(Level level, BlockPos pos) {
        try {
            BlockState state = SubLevelPositions.blockState(level, pos);
            return state != null && MianbaoMissileCompat.isLauncher(state);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Reports a bound position that has a tube in reach it cannot be matched
     * with, so a launcher the computer does not recognise names the tube beside
     * it instead of leaving the cause to guesswork. One line a minute for a
     * position, because the question is asked again and again.
     */
    private static void reportUnmatchedTube(Level level, BlockPos pos) {
        Entity nearest = nearestTube(level, Vec3.atCenterOf(pos));
        if (nearest == null) {
            return;
        }
        long now = level.getGameTime();
        Long reported = UNMATCHED_REPORTED.get(pos.asLong());
        if (reported != null && now - reported < UNMATCHED_REPORT_SECONDS * 20L) {
            return;
        }
        if (UNMATCHED_REPORTED.size() > 128) {
            UNMATCHED_REPORTED.clear();
        }
        UNMATCHED_REPORTED.put(pos.asLong(), now);
        FireControlCompat.LOGGER.info(
            "vestalihy bound launcher at {} carries no tube: {} stands {} blocks away and holds on to {}",
            pos,
            describe(nearest),
            Math.round(Math.sqrt(nearest.position().distanceToSqr(Vec3.atCenterOf(pos)))),
            rememberedAnchor(nearest));
    }

    /** The tube closest to a point, or null when none stands in reach. */
    private static Entity nearestTube(Level level, Vec3 center) {
        AABB box = new AABB(center, center).inflate(TUBE_SCAN_RADIUS);
        Entity nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, candidate -> candidate instanceof Launcher)) {
            double distance = entity.position().distanceToSqr(center);
            if (distance <= best) {
                best = distance;
                nearest = entity;
            }
        }
        return nearest;
    }

    /** The registry name of an entity, for the reports above. */
    private static String describe(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? String.valueOf(entity.getType()) : id.toString();
    }

    /**
     * Looks for the tube around a point. The world query also reaches tubes that
     * live in the plot space of a structure, because Sable widens entity lookups
     * into the sub-levels the searched area covers.
     */
    private static Launcher searchLauncher(Level level, Vec3 center) {
        Launcher best = null;
        double bestDistance = LAUNCHER_RADIUS * LAUNCHER_RADIUS;
        AABB box = new AABB(center, center).inflate(LAUNCHER_RADIUS);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, candidate -> candidate instanceof Launcher)) {
            double distance = entity.position().distanceToSqr(center);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = (Launcher) entity;
            }
        }
        return best;
    }

    /** The entity behind a launcher face, for position maths. */
    public static Entity entityOf(Launcher launcher) {
        return launcher instanceof Entity entity ? entity : null;
    }

    /** True for the rounds the fire control computer is allowed to fly. */
    public static boolean isGuidedMissile(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && MOD_ID.equals(id.getNamespace()) && GUIDED_MISSILE_IDS.contains(id.getPath());
    }

    public static boolean isGuidedMissileType(net.minecraft.world.entity.EntityType<?> type) {
        if (type == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id != null && MOD_ID.equals(id.getNamespace()) && GUIDED_MISSILE_IDS.contains(id.getPath());
    }

    /**
     * Where the binding points. A tube hangs in the air, so the reference holds
     * on to a block it is mounted on: the tube's own block when that block is
     * there, otherwise the first block found below it. Several tubes bolted to
     * the same block would otherwise produce the same reference, and the fire
     * control computer drops a reference it already holds, so every tube keeps
     * the first free block around it and remembers its choice on itself.
     */
    public static BlockPos anchor(Level level, Entity tube) {
        BlockPos remembered = rememberedAnchor(tube);
        if (remembered != null && holdsBlock(level, remembered)) {
            return remembered;
        }
        for (BlockPos candidate : candidateAnchors(level, tube.blockPosition())) {
            if (!claimedByOtherTube(level, tube, candidate)) {
                return candidate;
            }
        }
        return tube.blockPosition();
    }

    /**
     * The blocks a tube may hold on to, nearest first: its own block, the blocks
     * straight below it, and the blocks beside each of those, so a tube sharing
     * a block with another one still finds a block of its own.
     */
    private static List<BlockPos> candidateAnchors(Level level, BlockPos start) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int depth = 0; depth <= ANCHOR_DEPTH; depth++) {
            BlockPos column = start.below(depth);
            if (holdsBlock(level, column)) {
                candidates.add(column);
            }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos beside = column.relative(side);
                if (holdsBlock(level, beside)) {
                    candidates.add(beside);
                }
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(start);
        }
        return candidates;
    }

    /** True when another tube of the same group already holds on to this block. */
    private static boolean claimedByOtherTube(Level level, Entity tube, BlockPos candidate) {
        if (level == null) {
            return false;
        }
        Vec3 center = Vec3.atCenterOf(candidate);
        AABB box = new AABB(center, center).inflate(TUBE_SCAN_RADIUS);
        for (Entity other : level.getEntitiesOfClass(Entity.class, box, candidatex -> candidatex instanceof Launcher)) {
            if (other != tube && candidate.equals(rememberedAnchor(other))) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos rememberedAnchor(Entity tube) {
        if (tube == null) {
            return null;
        }
        CompoundTag data = tube.getPersistentData();
        return data.contains(ANCHOR_TAG) ? BlockPos.of(data.getLong(ANCHOR_TAG)) : null;
    }

    private static void rememberAnchor(Entity tube, BlockPos anchor) {
        if (tube != null && anchor != null) {
            tube.getPersistentData().putLong(ANCHOR_TAG, anchor.asLong());
        }
    }

    private static Vec3 localMuzzle(Entity tube) {
        return tube.position().add(0.0, tube.getBbHeight() * 0.5, 0.0);
    }

    private static boolean holdsBlock(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        try {
            if (!level.getBlockState(pos).isAir()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            BlockState state = SubLevelPositions.blockState(level, pos);
            return state != null && !state.isAir();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean finite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static final String OPTICS_CONNECTOR = "vestalihy:optics_connector";

    /** Keys the connector keeps the remembered computer under, beside vestalihy's own. */
    private static final String CONTROLLER_X = "firecontrol_computer_x";
    private static final String CONTROLLER_Y = "firecontrol_computer_y";
    private static final String CONTROLLER_Z = "firecontrol_computer_z";
    private static final String CONTROLLER_SUB = "firecontrol_computer_sublevel";

    /** True for vestalihy's optics connector, without naming one of its classes. */
    public static boolean isOpticsConnector(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && OPTICS_CONNECTOR.equals(id.toString());
    }

    /**
     * Files a fire control computer on the connector that was used on it, the way
     * the connector files an optics module away: the first click names the
     * computer, the second click - the one on a launcher - hands that launcher to
     * it. Clicking the computer while sneaking forgets it again.
     *
     * <p>Naming the computer instead of taking the nearest one is what lets a
     * player run several computers side by side and still decide which of them
     * owns a given launcher.</p>
     */
    public static void rememberController(Player player, Level level, ItemStack stack, BlockPos pos) {
        if (player == null || level == null || pos == null || stack == null || stack.isEmpty()) {
            return;
        }
        if (player.isShiftKeyDown()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.remove(CONTROLLER_X);
                tag.remove(CONTROLLER_Y);
                tag.remove(CONTROLLER_Z);
                tag.remove(CONTROLLER_SUB);
            });
            player.displayClientMessage(Component.literal("已清除记录的火控计算机"), true);
            return;
        }
        StabilizerControllerBlockEntity controller = controllerAt(level, pos, null, false);
        if (controller == null) {
            return;
        }
        UUID subLevel = ControllerTracker.controllerSubLevelId(controller);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt(CONTROLLER_X, pos.getX());
            tag.putInt(CONTROLLER_Y, pos.getY());
            tag.putInt(CONTROLLER_Z, pos.getZ());
            if (subLevel == null) {
                tag.remove(CONTROLLER_SUB);
            } else {
                tag.putString(CONTROLLER_SUB, subLevel.toString());
            }
        });
        player.displayClientMessage(Component.literal("已记录火控计算机 " + callSign(level, controller)
                + "，再对导弹右键就能把发射器交给它"), true);
    }

    /**
     * The click that hands a tube to a fire control computer: the tube is given
     * to the computer the connector remembers, which the player named by clicking
     * that computer. A connector that remembers nothing says so rather than
     * guessing, so two computers in reach can never be mixed up.
     */
    public static void tryBindWithConnector(Player player, Entity launcher) {
        if (player == null || launcher == null || !(launcher.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack stack = player.getMainHandItem().isEmpty() ? player.getOffhandItem() : player.getMainHandItem();
        if (!isOpticsConnector(stack)) {
            return;
        }
        StabilizerControllerBlockEntity controller = rememberedController(stack, level);
        if (controller == null) {
            player.displayClientMessage(Component.literal(
                    "还没有记录火控计算机：先用光学连接器右键一台火控计算机，再右键导弹"), true);
            return;
        }
        BlockPos anchor = anchor(level, launcher);
        rememberAnchor(launcher, anchor);
        FireControlCompat.LOGGER.info("vestalihy launcher {} bound to {} at anchor {} ({} blocks below the tube)",
                describe(launcher), callSign(level, controller), anchor,
                Math.round(Math.sqrt(launcher.position().distanceToSqr(Vec3.atCenterOf(anchor)))));
        releaseFromOtherControllers(level, anchor, controller);
        controller.bind(BindingSlot.MISSILE_LAUNCHER, BindingRef.capture(level, anchor));
        int bound = controller.getMissileLaunchers().size();
        player.displayClientMessage(Component.literal("发射器已交给火控计算机 " + callSign(level, controller)
                + "，发射和制导由它接手（已绑定 " + bound + " 具）"), true);
    }

    /** The computer a connector remembers, or null when it remembers nothing usable. */
    private static StabilizerControllerBlockEntity rememberedController(ItemStack stack, Level level) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (!tag.contains(CONTROLLER_X)) {
            return null;
        }
        BlockPos pos = new BlockPos(
                tag.getInt(CONTROLLER_X), tag.getInt(CONTROLLER_Y), tag.getInt(CONTROLLER_Z));
        return controllerAt(level, pos, readSubLevel(tag), true);
    }

    private static UUID readSubLevel(CompoundTag tag) {
        if (!tag.contains(CONTROLLER_SUB)) {
            return null;
        }
        try {
            return UUID.fromString(tag.getString(CONTROLLER_SUB));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * The loaded computer standing at a position in this dimension. A computer on
     * a structure keeps its blocks in that structure's plot region, which is
     * nowhere near where the structure is seen, so the level the block reports is
     * tried first and the whole dimension after it. A remembered computer also
     * has to sit on the structure it was filed under, because two structures may
     * share a coordinate.
     */
    private static StabilizerControllerBlockEntity controllerAt(
            Level level, BlockPos pos, UUID subLevelId, boolean exactSubLevel) {
        if (level == null || pos == null) {
            return null;
        }
        StabilizerControllerBlockEntity fallback = null;
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            if (controller == null || controller.isRemoved()) {
                continue;
            }
            Level controllerLevel = controller.getLevel();
            if (controllerLevel == null || controllerLevel.isClientSide) {
                continue;
            }
            if (level.getServer() == null || controllerLevel.getServer() != level.getServer()
                    || !controllerLevel.dimension().equals(level.dimension())) {
                continue;
            }
            if (!controller.getBlockPos().equals(pos)) {
                continue;
            }
            if (exactSubLevel && !Objects.equals(subLevelId, ControllerTracker.controllerSubLevelId(controller))) {
                continue;
            }
            if (controllerLevel == level) {
                return controller;
            }
            if (fallback == null) {
                fallback = controller;
            }
        }
        return fallback;
    }

    /**
     * Drops the tube from every other computer. A tube hands itself to the
     * computer the player named, so a computer that held it before has to let go,
     * or two screens would fight over one tube.
     */
    private static void releaseFromOtherControllers(Level level, BlockPos anchor, StabilizerControllerBlockEntity keep) {
        for (StabilizerControllerBlockEntity other : ControllerTracker.loadedControllers()) {
            if (other == null || other == keep || other.isRemoved()) {
                continue;
            }
            List<BindingRef> held = other.getMissileLaunchers();
            for (int index = held.size() - 1; index >= 0; index--) {
                BindingRef reference = held.get(index);
                if (anchor.equals(reference.pos()) && reference.matches(level, anchor)) {
                    other.unbindDevice(BindingSlot.MISSILE_LAUNCHER, index, anchor);
                }
            }
        }
    }

    /** Call sign of a computer, the bare default when it was never named. */
    private static String callSign(Level level, StabilizerControllerBlockEntity controller) {
        String name = "";
        try {
            name = RadarNameStore.nameOf(level, controller);
        } catch (Throwable ignored) {
        }
        return name == null || name.isEmpty() ? "[AAA]" : "[" + name + "]";
    }

    /**
     * Remembers, on the round a tube has just fired, which launcher it came
     * from. The fire control computer flies the big rounds itself, but the 9M14
     * is steered by hand, and the player doing the steering is the one sitting
     * at the computer rather than the one behind a vestalihy scope, so the round
     * has to carry the launcher it belongs to.
     */
    public static void markSteerableRounds(Entity tube) {
        if (tube == null || !(tube.level() instanceof ServerLevel level)) {
            return;
        }
        CompoundTag reference = BindingRef.capture(level, anchor(level, tube)).save();
        Vec3 localMuzzle = localMuzzle(tube);
        stampRounds(level, localMuzzle, reference);
        Vec3 worldMuzzle = worldPosition(level, localMuzzle);
        if (worldMuzzle != null && worldMuzzle.distanceToSqr(localMuzzle) > 1.0E-6) {
            stampRounds(level, worldMuzzle, reference);
        }
    }

    private static void stampRounds(Level level, Vec3 muzzle, CompoundTag reference) {
        AABB box = new AABB(muzzle, muzzle).inflate(ROUND_STAMP_RADIUS);
        for (Entity round : level.getEntitiesOfClass(Entity.class, box, VestalihyBridge::isSteerableRound)) {
            CompoundTag data = round.getPersistentData();
            if (!data.contains(ROUND_REF_TAG)) {
                data.put(ROUND_REF_TAG, reference.copy());
            }
        }
    }

    private static boolean isSteerableRound(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && MOD_ID.equals(id.getNamespace()) && MALYTKA_ROUND.equals(id.getPath());
    }

    /**
     * True when a hand-steered round answers to this player, because the
     * computer that launched it is the one this player is sitting at.
     */
    public static boolean isFireControlSteerable(Entity round, Player player) {
        if (round == null || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        BindingRef launchedFrom = BindingRef.load(round.getPersistentData(), ROUND_REF_TAG);
        if (launchedFrom == null) {
            return false;
        }
        Level roundLevel = round.level();
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            if (controller == null || controller.isRemoved()) {
                continue;
            }
            Level controllerLevel = controller.getLevel();
            if (controllerLevel == null || controllerLevel.isClientSide) {
                continue;
            }
            if (roundLevel.getServer() == null || controllerLevel.getServer() != roundLevel.getServer()
                    || !controllerLevel.dimension().equals(roundLevel.dimension())) {
                continue;
            }
            if (!operates(controller, serverPlayer)) {
                continue;
            }
            for (BindingRef bound : controller.getMissileLaunchers()) {
                if (bound.equals(launchedFrom)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the player is the one operating this computer. */
    private static boolean operates(StabilizerControllerBlockEntity controller, ServerPlayer player) {
        if (controller.isSeatedMissileOperator(player)) {
            return true;
        }
        try {
            return controller.getAircraftMissileOperator() == player;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
