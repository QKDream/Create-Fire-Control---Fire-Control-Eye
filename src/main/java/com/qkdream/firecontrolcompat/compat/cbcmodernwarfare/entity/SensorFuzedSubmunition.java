package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity;

import java.util.UUID;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.munitions.ShellExplosion;


/**
 * One of the two darts a sensor fuzed shell throws out when it goes off. It
 * sinks slowly under a slow spin and looks straight down through a 150 degree
 * cone for a Sable structure; the first one it finds under it takes an APDS
 * dart at 340 m/s aimed at the middle of that structure, and the dart itself is
 * gone either way once that shot is away or once it reaches the ground. The
 * structure the shell was fired from is never taken for a target, so a salvo
 * cannot shoot the ship that launched it.
 */
public class SensorFuzedSubmunition extends AbstractArrow implements ItemSupplier {

    /** How far below itself the seeker looks, in blocks. */
    private static final double SCAN_DEPTH = 8.0;

    /** Cosine of the seeker half angle: a 150 degree field of view is 75 either side. */
    private static final double SCAN_COSINE = Math.cos(Math.toRadians(75.0));

    /** Sink rate of the dart, in blocks per tick, once the ejection has bled off. */
    private static final double DESCENT_SPEED = 0.1;

    /** Speed the dart leaves at, in blocks per tick. 340 m/s is 17 blocks a tick. */
    private static final double DART_SPEED = 17.0;

    /** Ticks the ejection throw takes to bleed into the slow sink. */
    private static final int EJECT_TICKS = 10;

    /** Longest the dart is allowed to drift before it gives up. */
    private static final int MAX_LIFETIME_TICKS = 300;

    /**
     * Charge of the self forging fragment warhead. It is measured in the same
     * unit a Create Big Cannons shell explosion calls its block power, and it is
     * what sizes the blast wave and the smoke cloud, while the burst itself is
     * left unable to break blocks or hurt anything. The charge sits at a bit
     * over a third of CBC Modern Warfare's high explosive autocannon round,
     * because each dart of a salvo throws up a cloud that every client in
     * sight then has to tick for eight seconds, and a screen of them cost more
     * frames than it was worth.
     */
    private static final float BURST_POWER = 1.5F;

    /** Ticks between two sweeps of the seeker. */
    private static final int SCAN_INTERVAL = 4;

    /** Blocks within which a player has to stand for a burst to be sent out. */
    private static final double BURST_VISIBLE_RANGE = 128.0;

    /** Blocks above a structure the sky is looked for through. */
    private static final double SKY_DEPTH = 32.0;

    /** How far inside the edges of a structure its roof is looked at. */
    private static final double PROBE_INSET = 0.25;

    /** Share of the roof of a structure that has to be covered to hide it. */
    private static final double HIDDEN_SHARE = 0.66;

    private Vec3 ejectVelocity = Vec3.ZERO;
    private UUID launchSubLevelId;

    public SensorFuzedSubmunition(EntityType<? extends SensorFuzedSubmunition> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.pickup = AbstractArrow.Pickup.DISALLOWED;
        this.setPickupItemStack(new ItemStack(CbcModernWarfareCompat.SFM_SUBMUNITION.get()));
    }

    /** Throws one dart clear of the shell it came out of. */
    public void markEjection(Vec3 velocity, UUID launchSubLevelId) {
        this.ejectVelocity = velocity == null || !isFinite(velocity) ? Vec3.ZERO : velocity;
        this.launchSubLevelId = launchSubLevelId;
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide()) {
            this.firecontrolcompat$sink();
        }
        super.tick();
        if (this.level().isClientSide()) {
            return;
        }
        if (this.inGround || this.onGround() || this.tickCount > MAX_LIFETIME_TICKS) {
            this.discard();
            return;
        }
        if (this.tickCount >= EJECT_TICKS && (this.tickCount - EJECT_TICKS) % SCAN_INTERVAL == 0) {
            Vec3 target = this.firecontrolcompat$findStructureBelow();
            if (target != null) {
                this.firecontrolcompat$fireDart(target);
                this.discard();
            }
        }
    }

    /**
     * Bleeds the throw off into a straight, slow sink. The dart keeps a little
     * of its throw for the first few ticks so a pair thrown three blocks apart
     * actually separates into its column before they both settle.
     */
    private void firecontrolcompat$sink() {
        double keep = Math.max(0.0, 1.0 - this.tickCount / (double) EJECT_TICKS);
        double vertical = this.ejectVelocity.y * keep - DESCENT_SPEED * (1.0 - keep);
        this.setDeltaMovement(this.ejectVelocity.x * keep, vertical, this.ejectVelocity.z * keep);
    }

    /**
     * Middle of the nearest Sable structure inside the seeker cone, or null when
     * there is nothing under the dart. The cone and the range are measured to
     * the closest corner of the structure - what the seeker can actually see -
     * while the shot itself is laid on the middle of it, so the dart goes into
     * the hull rather than skimming its roof.
     */
    private Vec3 firecontrolcompat$findStructureBelow() {
        SubLevelContainer container = SubLevelContainer.getContainer(this.level());
        if (container == null) {
            return null;
        }
        Vec3 origin = this.position();
        Vec3 down = new Vec3(0.0, -1.0, 0.0);
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()) {
                continue;
            }
            if (this.launchSubLevelId != null && this.launchSubLevelId.equals(subLevel.getUniqueId())) {
                continue;
            }
            AABB bounds = firecontrolcompat$worldBounds(subLevel);
            if (bounds == null || !isFinite(bounds.getCenter())) {
                continue;
            }
            Vec3 closest = firecontrolcompat$closestPoint(bounds, origin);
            Vec3 offset = closest.subtract(origin);
            double distance = offset.length();
            if (!(distance <= SCAN_DEPTH)) {
                continue;
            }
            if (distance > 1.0E-4 && offset.normalize().dot(down) < SCAN_COSINE) {
                continue;
            }
            if (distance < bestDistance && this.firecontrolcompat$visible(bounds)) {
                bestDistance = distance;
                best = bounds.getCenter();
            }
        }
        return best;
    }

    /**
     * True when the seeker can make the structure out at all. A ship that is
     * roofed over - parked under a roof, a bridge or the overhang of a hill - is
     * left alone, because a seeker that looks straight down at a covered ship
     * has nothing to lock onto.
     */
    private boolean firecontrolcompat$visible(AABB bounds) {
        Vec3[] probes = firecontrolcompat$probes(bounds);
        int covered = 0;
        for (Vec3 probe : probes) {
            Vec3 sky = probe.add(0.0, SKY_DEPTH, 0.0);
            BlockHitResult above = this.level().clip(new ClipContext(probe, sky,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, (Entity) null));
            if (above.getType() == HitResult.Type.BLOCK) {
                covered++;
            }
        }
        return covered < probes.length * HIDDEN_SHARE;
    }

    /**
     * The spots of a structure the sky is looked at through: a grid over its
     * roof, held a little inside the edges so a line cannot slip through the
     * crack between two blocks, and its middle.
     */
    private static Vec3[] firecontrolcompat$probes(AABB bounds) {
        double minX = bounds.minX + PROBE_INSET;
        double maxX = bounds.maxX - PROBE_INSET;
        double minZ = bounds.minZ + PROBE_INSET;
        double maxZ = bounds.maxZ - PROBE_INSET;
        double roof = bounds.maxY - PROBE_INSET;
        double midX = (minX + maxX) * 0.5;
        double midZ = (minZ + maxZ) * 0.5;
        Vec3[] probes = new Vec3[10];
        int i = 0;
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                probes[i++] = new Vec3(x == 0 ? minX : (x == 1 ? midX : maxX), roof,
                        z == 0 ? minZ : (z == 1 ? midZ : maxZ));
            }
        }
        probes[i] = bounds.getCenter();
        return probes;
    }

    /** True while a player stands close enough for the burst to be worth sending. */
    private static boolean firecontrolcompat$burstIsWatched(ServerLevel level, Vec3 origin) {
        double rangeSqr = BURST_VISIBLE_RANGE * BURST_VISIBLE_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(origin.x, origin.y, origin.z) < rangeSqr) {
                return true;
            }
        }
        return false;
    }

    /** Sends one incendiary APDS dart down into the structure the seeker picked up. */
    private void firecontrolcompat$fireDart(Vec3 target) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        IncendiaryApdsMediumcannonProjectile dart =
                CbcModernWarfareCompat.INCENDIARY_APDS_MEDIUMSHELL.get().create(server);
        if (dart == null) {
            return;
        }
        Vec3 origin = this.position();
        // The submunition goes off on top of the target like a self forging
        // fragment charge. The burst is a Create Big Cannons shell explosion,
        // the same blast wave and black smoke cloud CBC Modern Warfare's high
        // explosive autocannon round makes, so the charge looks and sounds like
        // the shell it was forged from. Nothing behind it: the blocks are left
        // alone and the flash hurts nobody, the dart underneath is what bites.
        if (firecontrolcompat$burstIsWatched(server, origin)) {
            ShellExplosion burst = new ShellExplosion(server, null, null, origin.x, origin.y, origin.z,
                    BURST_POWER, 0.0F, false, Explosion.BlockInteraction.KEEP);
            CreateBigCannons.handleCustomExplosion(server, burst);
        }
        Vec3 direction = target.subtract(origin);
        if (direction.lengthSqr() < 1.0E-8) {
            direction = new Vec3(0.0, -1.0, 0.0);
        }
        direction = direction.normalize();
        dart.setPos(origin.x, origin.y, origin.z);
        dart.setDeltaMovement(direction.scale(DART_SPEED));
        dart.hasImpulse = true;
        dart.setOrientation(direction);
        server.addFreshEntity(dart);
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(CbcModernWarfareCompat.SFM_SUBMUNITION.get());
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(CbcModernWarfareCompat.SFM_SUBMUNITION.get());
    }

    private static AABB firecontrolcompat$worldBounds(SubLevel subLevel) {
        try {
            AABB bounds = subLevel.boundingBox().toMojang();
            return bounds == null || !isFinite(bounds.getCenter()) ? null : bounds;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Vec3 firecontrolcompat$closestPoint(AABB box, Vec3 point) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ));
    }

    private static boolean isFinite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}