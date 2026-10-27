package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity;

import java.util.UUID;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Position;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.heap.HEAPMediumcannonProjectile;

/**
 * Sensor fuzed medium cannon shell: a high explosive shell that, the moment it
 * goes off, throws two submunitions out of its base in a column, three blocks
 * apart along the path it came in on. The
 * submunitions sink under a slow spin, look down through a 120 degree cone for
 * a Sable structure and put an APDS dart into the first one they find.
 *
 * <p>The shell carries no charge and no fuze of its own, so it never explodes
 * and never opens on its own: opening is left entirely to the fuze fitted to it
 * in the assembly bench, exactly like the high explosive rounds next to it. An
 * unfuzed round simply flies on and buries itself.</p>
 */
public class SensorFuzedMediumcannonProjectile extends HEAPMediumcannonProjectile {

    /** Submunitions thrown out of the shell. */
    public static final int SUBMUNITION_COUNT = 2;

    /** Distance between the two submunitions once they are clear, in blocks. */
    public static final double SUBMUNITION_SPACING = 3.0;

    /** Throw that separates the pair along the flight path, in blocks per tick. */
    private static final double EJECTION_SPREAD_SPEED = 0.24;

    /** Vertical throw given to each submunition, in blocks per tick. */
    private static final double EJECTION_RISE_SPEED = 0.16;

    private static final String LAUNCH_SUBLEVEL_KEY = "FireControlEyeSfmLaunchSubLevel";

    private UUID launchSubLevelId;
    private boolean firecontrolcompat$opened;

    public SensorFuzedMediumcannonProjectile(EntityType<? extends SensorFuzedMediumcannonProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide()) {
            this.firecontrolcompat$rememberLaunchSubLevel();
        }
        super.tick();
    }

    /**
     * The structure under the muzzle at the moment the shell was fired, kept so
     * the submunitions never pick their own ship as a target.
     */
    private void firecontrolcompat$rememberLaunchSubLevel() {
        if (this.launchSubLevelId != null) {
            return;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(this);
            if (subLevel != null) {
                this.launchSubLevelId = subLevel.getUniqueId();
                this.getPersistentData().putUUID(LAUNCH_SUBLEVEL_KEY, this.launchSubLevelId);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The fuze going off only opens the shell: there is no charge behind it, so
     * the high explosive detonation of the parent shell is deliberately never
     * run. The shell itself is spent either way.
     */
    @Override
    protected void detonate(Position position) {
        if (this.level().isClientSide()) {
            return;
        }
        this.firecontrolcompat$openSubmunitions(position);
        this.discard();
    }

    /** Throws the pair of submunitions out of the shell in a column, three blocks apart. */
    private void firecontrolcompat$openSubmunitions(Position position) {
        if (this.firecontrolcompat$opened || position == null) {
            return;
        }
        this.firecontrolcompat$opened = true;
        Vec3 centre = new Vec3(position.x(), position.y(), position.z());
        if (!isFinite(centre)) {
            return;
        }
        Vec3 axis = firecontrolcompat$ejectionAxis();
        for (int index = 0; index < SUBMUNITION_COUNT; index++) {
            double offset = SUBMUNITION_COUNT == 1 ? 0.0
                    : (index - (SUBMUNITION_COUNT - 1) / 2.0) * (SUBMUNITION_SPACING / (SUBMUNITION_COUNT - 1));
            SensorFuzedSubmunition submunition =
                    CbcModernWarfareCompat.SFM_SUBMUNITION_ENTITY.get().create(this.level());
            if (submunition == null) {
                continue;
            }
            Vec3 spawn = centre.add(axis.scale(offset));
            Vec3 throwVelocity = axis.scale(Math.signum(offset) * EJECTION_SPREAD_SPEED)
                    .add(0.0, EJECTION_RISE_SPEED, 0.0);
            submunition.setPos(spawn.x, spawn.y, spawn.z);
            submunition.setDeltaMovement(throwVelocity);
            submunition.hasImpulse = true;
            submunition.markEjection(throwVelocity, this.launchSubLevelId);
            this.level().addFreshEntity(submunition);
        }
    }

    /**
     * Axis the pair is thrown along: the shell's own flight path, so the two
     * darts leave nose to tail in a column - the leading one further down the
     * track, the trailing one still up behind it - the way a dispenser lays its
     * submunitions out.
     */
    private Vec3 firecontrolcompat$ejectionAxis() {
        Vec3 direction = this.getDeltaMovement();
        if (!isFinite(direction) || direction.lengthSqr() < 1.0E-6) {
            return new Vec3(0.0, -1.0, 0.0);
        }
        return direction.normalize();
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(CbcModernWarfareCompat.SFM_MEDIUMCANNON_ROUND.get());
    }


    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.launchSubLevelId != null) {
            tag.putUUID(LAUNCH_SUBLEVEL_KEY, this.launchSubLevelId);
        }
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(LAUNCH_SUBLEVEL_KEY)) {
            this.launchSubLevelId = tag.getUUID(LAUNCH_SUBLEVEL_KEY);
        }
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}