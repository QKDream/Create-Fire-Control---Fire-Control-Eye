package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.PhysicsGroups;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * A sonar set: a dish that listens, and - switched over - pings as well.
 *
 * <p>The dish answers to the fire control computer the player wired it to with
 * a wrench, and sweeps twice a second. A hearing sweep paints every noise the
 * water is carrying inside the set's window plus the living things and hulls
 * moving in it; a pinging sweep adds range finding, so ordnance, hulls and
 * living things inside the beam come back as echoes. A pinging set is heard by
 * everybody, which is the price of asking.</p>
 *
 * <p>Every set keeps its own window, its own mode and its own place on the
 * screen, so a computer can run several sets at once and the player can lay
 * their pictures out side by side. The set never marks the ship it stands on:
 * the hull it is bolted to is its own noise, so those bodies are filtered out
 * of its sweep.</p>
 */
public class SonarBlockEntity extends BlockEntity {

    /** Reach of a set out of the box. */
    public static final double DEFAULT_RANGE = 1500.0D;
    public static final double MIN_RANGE = 100.0D;
    public static final double MAX_RANGE = 3000.0D;

    /** How far off the bow the set listens, as a half angle: 180 is every way. */
    public static final double DEFAULT_ANGLE = 90.0D;
    public static final double MIN_ANGLE = 5.0D;
    public static final double MAX_ANGLE = 180.0D;

    /** How far up and how far down the dish looks, as half angles. */
    public static final double DEFAULT_ELEVATION = 90.0D;
    public static final double MIN_ELEVATION = 0.0D;
    public static final double MAX_ELEVATION = 90.0D;

    /** Where the picture sits on the screen, as a percentage of the free space. */
    public static final double DEFAULT_HUD_X = 1.0D;
    public static final double DEFAULT_HUD_Y = 1.0D;
    public static final double MIN_HUD = 0.0D;
    public static final double MAX_HUD = 100.0D;

    /** How big the picture is, as a percentage of its normal size. */
    public static final double DEFAULT_HUD_SCALE = 100.0D;
    public static final double MIN_HUD_SCALE = 40.0D;
    public static final double MAX_HUD_SCALE = 180.0D;

    /** How long the name written on a set may be. */
    public static final int MAX_NAME = 16;

    /** Half a second between two sweeps. */
    private static final int SWEEP_PERIOD = 10;

    private BlockPos controllerPos;
    private UUID controllerSubLevel;
    private double range = DEFAULT_RANGE;
    private double halfAngle = DEFAULT_ANGLE;
    private double elevationUp = DEFAULT_ELEVATION;
    private double elevationDown = DEFAULT_ELEVATION;
    private boolean activeMode;
    private double hudX = DEFAULT_HUD_X;
    private double hudY = DEFAULT_HUD_Y;
    private double hudScale = DEFAULT_HUD_SCALE;
    private String name = "";
    private List<SonarContact> contacts = List.of();
    private Vec3 origin = Vec3.ZERO;
    private Vec3 forward = new Vec3(0.0D, 0.0D, -1.0D);

    public SonarBlockEntity(BlockPos pos, BlockState state) {
        super(SonarBlocks.SONAR_BE.get(), pos, state);
    }

    /** Ticker handed to the block, so a set sweeps without a global tick handler. */
    public static <T extends BlockEntity> void serverTick(Level level, BlockPos pos, BlockState state, T blockEntity) {
        if (blockEntity instanceof SonarBlockEntity sonar) {
            sonar.tick();
        }
    }

    private void tick() {
        Level level = this.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % SWEEP_PERIOD != 0L) {
            return;
        }
        this.sweep();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        Level level = this.getLevel();
        if (level != null && !level.isClientSide) {
            SonarTracker.track(this);
        }
    }

    @Override
    public void setRemoved() {
        SonarTracker.forget(this);
        super.setRemoved();
    }

    /** One sweep of the dish: hear what is out there, and mark it. */
    public void sweep() {
        Level level = this.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        Level root = SonarLog.rootLevel(level);
        if (root == null) {
            return;
        }
        Vec3 dish = SubLevelPositions.worldCenter(level, this.worldPosition);
        Vec3 heading = this.forwardWorld();
        Set<UUID> ownBodies = this.ownBodies(root, dish);
        List<AABB> ownBoxes = new ArrayList<>();
        for (UUID id : ownBodies) {
            SubLevel body = PhysicsGroups.resolve(root, id);
            if (body == null) {
                continue;
            }
            try {
                ownBoxes.add(body.boundingBox().toMojang());
            } catch (Throwable ignored) {
            }
        }
        this.origin = dish;
        this.forward = heading;
        if (this.activeMode) {
            // A pinging set is loud, and every other set in the water hears it.
            SonarLog.record(root, dish, SonarKind.PING, this);
            // The ping is also a real splash, so the world's own ears - sculk
            // sensors, and the warden that listens for them - hear the set.
            try {
                SonarPulse.ping(root, dish, this.getBlockState());
            } catch (Throwable ignored) {
            }
        }
        try {
            this.contacts = SonarScanner.scan(
                    root, this, dish, heading, this.range, this.halfAngle,
                    this.elevationUp, this.elevationDown, this.activeMode, ownBodies, ownBoxes);
        } catch (Throwable ignored) {
        }
    }

    /** Sweeps at once, so a setting changed on the screen shows up immediately. */
    public void sweepNow() {
        try {
            this.sweep();
        } catch (Throwable ignored) {
        }
    }

    /**
     * The bodies the set stands on: the hull she is bolted to is her own noise,
     * and never marks itself.
     *
     * <p>Only the platform the set belongs to is filtered - the body she sits on
     * and everything welded or jointed to it. Asking which bodies the dish merely
     * stands <em>inside</em> would take the whole harbour with it, because the box
     * of a dock, or of another berth alongside, can hold the dish as easily as her
     * own hull does; a set that has quietly filed the ship she is looking for as
     * part of her own platform then paints nothing at all.</p>
     */
    private Set<UUID> ownBodies(Level root, Vec3 dish) {
        Set<UUID> own = new LinkedHashSet<>();
        UUID ownSubLevel = SonarTracker.subLevelOf(this);
        try {
            if (ownSubLevel != null) {
                own.addAll(PhysicsGroups.group(root, ownSubLevel));
            }
            if (own.isEmpty()) {
                own.addAll(PhysicsGroups.bodiesContaining(root, dish, 1.0D));
            }
        } catch (Throwable ignored) {
        }
        return own;
    }

    /** Where the dish looks, in the world, with the hull's own pose applied. */
    private Vec3 forwardWorld() {
        Direction facing = Direction.NORTH;
        try {
            BlockState state = this.getBlockState();
            if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
                facing = state.getValue(HorizontalDirectionalBlock.FACING);
            }
        } catch (Throwable ignored) {
        }
        Vector3d vector = new Vector3d(facing.getStepX(), 0.0D, facing.getStepZ());
        try {
            SubLevel containing = Sable.HELPER.getContaining(this);
            if (containing != null && !containing.isRemoved()) {
                containing.logicalPose().transformNormal(vector);
            }
        } catch (Throwable ignored) {
        }
        Vec3 result = new Vec3(vector.x, vector.y, vector.z);
        return result.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 0.0D, -1.0D) : result.normalize();
    }

    /** Points the set at a fire control computer. */
    public void bind(BlockPos pos, UUID subLevel) {
        this.controllerPos = pos == null ? null : pos.immutable();
        this.controllerSubLevel = subLevel;
        this.setChanged();
    }

    /** Lets the set go back to listening for nobody. */
    public void clearBinding() {
        this.controllerPos = null;
        this.controllerSubLevel = null;
        this.setChanged();
    }

    public boolean isBound() {
        return this.controllerPos != null;
    }

    /** Whether this set answers to the computer with that identity. */
    public boolean isBoundTo(BlockPos pos, UUID subLevel) {
        if (this.controllerPos == null || pos == null || !this.controllerPos.equals(pos)) {
            return false;
        }
        return this.controllerSubLevel == null || subLevel == null || this.controllerSubLevel.equals(subLevel);
    }

    public BlockPos controllerPos() {
        return this.controllerPos;
    }

    public UUID controllerSubLevel() {
        return this.controllerSubLevel;
    }

    /** Range, window, mode and picture placement written on the fire control screen. */
    public void applySettings(
            double range,
            double halfAngle,
            double elevationUp,
            double elevationDown,
            boolean active,
            double hudX,
            double hudY,
            double hudScale,
            String name) {
        this.range = clampRange(range);
        this.halfAngle = clampAngle(halfAngle);
        this.elevationUp = clampElevation(elevationUp);
        this.elevationDown = clampElevation(elevationDown);
        this.activeMode = active;
        this.hudX = clampHud(hudX);
        this.hudY = clampHud(hudY);
        this.hudScale = clampHudScale(hudScale);
        this.name = clampName(name);
        this.setChanged();
    }

    public static double clampRange(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_RANGE;
        }
        return Math.max(MIN_RANGE, Math.min(MAX_RANGE, value));
    }

    public static double clampAngle(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_ANGLE;
        }
        return Math.max(MIN_ANGLE, Math.min(MAX_ANGLE, value));
    }

    /** One side of the elevation window: 0 looks flat, 90 covers straight up or down. */
    public static double clampElevation(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_ELEVATION;
        }
        return Math.max(MIN_ELEVATION, Math.min(MAX_ELEVATION, value));
    }

    public static double clampHud(double value) {
        if (!Double.isFinite(value)) {
            return MIN_HUD;
        }
        return Math.max(MIN_HUD, Math.min(MAX_HUD, value));
    }

    public static double clampHudScale(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_HUD_SCALE;
        }
        return Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, value));
    }

    /** The name written on a set: trimmed, and short enough to read on the picture. */
    public static String clampName(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_NAME ? trimmed : trimmed.substring(0, MAX_NAME);
    }

    public double range() {
        return this.range;
    }

    public double halfAngle() {
        return this.halfAngle;
    }

    public double elevationUp() {
        return this.elevationUp;
    }

    public double elevationDown() {
        return this.elevationDown;
    }

    public boolean activeMode() {
        return this.activeMode;
    }

    public double hudX() {
        return this.hudX;
    }

    public double hudY() {
        return this.hudY;
    }

    public double hudScale() {
        return this.hudScale;
    }

    /** The name the player gave this set, or an empty string when it has none. */
    public String name() {
        return this.name;
    }

    /** Names this set. */
    public void setName(String value) {
        this.name = clampName(value);
        this.setChanged();
    }

    /** Puts a fresh set on a free line of the screen, so two dishes do not stack. */
    public void placeOnHud(int index) {
        int slot = Math.max(0, index);
        this.hudX = clampHud(1.0D + 24.0D * (slot % 4));
        this.hudY = clampHud(1.0D + 34.0D * (slot / 4));
        this.setChanged();
    }

    public List<SonarContact> contacts() {
        return this.contacts;
    }

    public Vec3 dishPosition() {
        return this.origin;
    }

    public Vec3 dishForward() {
        return this.forward;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.controllerPos != null) {
            tag.putLong("ControllerPos", this.controllerPos.asLong());
            if (this.controllerSubLevel != null) {
                tag.putUUID("ControllerSubLevel", this.controllerSubLevel);
            }
        }
        tag.putDouble("SonarRange", this.range);
        tag.putDouble("SonarHalfAngle", this.halfAngle);
        tag.putDouble("SonarElevationUp", this.elevationUp);
        tag.putDouble("SonarElevationDown", this.elevationDown);
        tag.putBoolean("SonarActive", this.activeMode);
        tag.putDouble("SonarHudX", this.hudX);
        tag.putDouble("SonarHudY", this.hudY);
        tag.putDouble("SonarHudScale", this.hudScale);
        tag.putString("SonarName", this.name);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("ControllerPos")) {
            this.controllerPos = BlockPos.of(tag.getLong("ControllerPos"));
            this.controllerSubLevel = tag.hasUUID("ControllerSubLevel") ? tag.getUUID("ControllerSubLevel") : null;
        }
        if (tag.contains("SonarRange")) {
            this.range = clampRange(tag.getDouble("SonarRange"));
        }
        if (tag.contains("SonarHalfAngle")) {
            this.halfAngle = clampAngle(tag.getDouble("SonarHalfAngle"));
        }
        // Sets saved before the elevation window was split keep the one angle they have.
        double legacy = tag.contains("SonarVerticalAngle") ? tag.getDouble("SonarVerticalAngle") : DEFAULT_ELEVATION;
        this.elevationUp = clampElevation(tag.contains("SonarElevationUp") ? tag.getDouble("SonarElevationUp") : legacy);
        this.elevationDown = clampElevation(
                tag.contains("SonarElevationDown") ? tag.getDouble("SonarElevationDown") : legacy);
        this.activeMode = tag.getBoolean("SonarActive");
        this.hudX = clampHud(tag.contains("SonarHudX") ? tag.getDouble("SonarHudX") : DEFAULT_HUD_X);
        this.hudY = clampHud(tag.contains("SonarHudY") ? tag.getDouble("SonarHudY") : DEFAULT_HUD_Y);
        this.hudScale = clampHudScale(
                tag.contains("SonarHudScale") ? tag.getDouble("SonarHudScale") : DEFAULT_HUD_SCALE);
        this.name = clampName(tag.getString("SonarName"));
    }
}
