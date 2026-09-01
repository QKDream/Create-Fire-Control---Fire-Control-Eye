package com.qkdream.firecontrolcompat.entity;

import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import com.qkdream.firecontrolcompat.TargetClassifier;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Infrared-homing proximity missile. No lock is required to launch: after
 * firing the seeker scans a 60 degree cone straight ahead for Sable
 * structures, mianbao / TAOV / vestalihy missiles, CBCMS bombs, rockets and
 * torpedoes, and mianbao countermeasures, then keeps riding the first
 * contact it acquires. Flight data: Mach 1 top speed (340 blocks/s) with
 * 25G of overload.
 */
public class InfraredMissileEntity extends BeamRidingMissileEntity {

    protected static final double SEEKER_HALF_COS = Math.cos(Math.toRadians(30.0));
    protected static final double SEEKER_RANGE = 200.0;
    protected static final int SEEKER_START_TICK = 2;
    /** Mach 1 = 340 blocks/s = 17 blocks/tick. */
    private static final double IR_GUIDANCE_SPEED = 17.0;
    /** Infrared missile lateral overload rating. */
    private static final double IR_OVERLOAD_G = 25.0;

    private UUID trackedEntityId;
    private UUID trackedSubLevelId;
    private UUID trackedShaolibId;

    public InfraredMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item missileItem() {
        return BeamMissileRegistry.INFRARED_MISSILE.get();
    }

    @Override
    protected double guidanceSpeed() {
        return IR_GUIDANCE_SPEED;
    }

    @Override
    protected double guidanceOverloadG() {
        return IR_OVERLOAD_G;
    }

    @Override
    protected Vec3 trackedTargetPosition() {
        return currentTrack();
    }

    @Override
    protected Vec3 guidancePoint() {
        Vec3 current = currentTrack();
        if (current != null) {
            return current;
        }
        clearTrack();
        if (this.getPersistentData().getDouble("fadongji1") < SEEKER_START_TICK) {
            return null;
        }
        Vec3 direction = forwardDirection();
        return direction == null ? null : acquireTarget(direction);
    }

    private Vec3 currentTrack() {
        if (this.trackedEntityId != null) {
            Entity entity = this.level() instanceof ServerLevel serverLevel
                    ? serverLevel.getEntity(this.trackedEntityId) : null;
            if (entity == null || !entity.isAlive() || entity.isRemoved()) {
                this.trackedEntityId = null;
                return null;
            }
            return entity.getBoundingBox().getCenter();
        }
        if (this.trackedSubLevelId != null) {
            SubLevelContainer container = SubLevelContainer.getContainer(this.level());
            SubLevel subLevel = container == null ? null : container.getSubLevel(this.trackedSubLevelId);
            if (subLevel == null || subLevel.isRemoved()) {
                this.trackedSubLevelId = null;
                return null;
            }
            return subLevel.boundingBox().toMojang().getCenter();
        }
        if (this.trackedShaolibId != null) {
            Vec3 position = ShaolibBridge.position(this.trackedShaolibId);
            if (position == null || !ShaolibBridge.isAlive(this.trackedShaolibId)) {
                this.trackedShaolibId = null;
                return null;
            }
            return position;
        }
        return null;
    }

    private void clearTrack() {
        this.trackedEntityId = null;
        this.trackedSubLevelId = null;
        this.trackedShaolibId = null;
    }

    private Vec3 forwardDirection() {
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-4) {
            return velocity.normalize();
        }
        Vec3 look = this.getViewVector(1.0F);
        return look.lengthSqr() > 1.0E-8 ? look.normalize() : null;
    }

    private Vec3 acquireTarget(Vec3 direction) {
        Vec3 origin = this.position();
        double bestDistanceSqr = Double.MAX_VALUE;
        Vec3 best = null;
        UUID bestEntity = null;
        UUID bestSubLevel = null;
        UUID bestShaolib = null;

        AABB query = AABB.ofSize(origin, SEEKER_RANGE * 2.0, SEEKER_RANGE * 2.0, SEEKER_RANGE * 2.0);
        for (Entity entity : this.level().getEntities(this, query, this::isIrSeekerTarget)) {
            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 relative = center.subtract(origin);
            double distanceSqr = relative.lengthSqr();
            if (distanceSqr > SEEKER_RANGE * SEEKER_RANGE || distanceSqr < 1.0E-8) {
                continue;
            }
            if (relative.dot(direction) / Math.sqrt(distanceSqr) < SEEKER_HALF_COS) {
                continue;
            }
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                best = center;
                bestEntity = entity.getUUID();
                bestSubLevel = null;
                bestShaolib = null;
            }
        }

        try {
            if (this.level() instanceof ServerLevel serverLevel) {
                SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
                if (container != null) {
                    for (SubLevel subLevel : container.getAllSubLevels()) {
                        if (subLevel == null || subLevel.isRemoved()) {
                            continue;
                        }
                        if (this.launchSubLevelId != null && this.launchSubLevelId.equals(subLevel.getUniqueId())) {
                            continue;
                        }
                        AABB box = subLevel.boundingBox().toMojang();
                        AABB launchZone = box.inflate(2.0);
                        if (this.launchSourcePos != null && launchZone.contains(Vec3.atCenterOf(this.launchSourcePos))
                                || this.launchPosition != null && launchZone.contains(this.launchPosition)) {
                            continue;
                        }
                        Vec3 center = box.getCenter();
                        Vec3 relative = center.subtract(origin);
                        double distanceSqr = relative.lengthSqr();
                        if (distanceSqr > SEEKER_RANGE * SEEKER_RANGE || distanceSqr < 1.0E-8) {
                            continue;
                        }
                        if (relative.dot(direction) / Math.sqrt(distanceSqr) < SEEKER_HALF_COS) {
                            continue;
                        }
                        if (distanceSqr < bestDistanceSqr) {
                            bestDistanceSqr = distanceSqr;
                            best = center;
                            bestSubLevel = subLevel.getUniqueId();
                            bestEntity = null;
                            bestShaolib = null;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (ShaolibBridge.available()) {
            String dimensionId = this.level().dimension().location().toString();
            for (Object instance : ShaolibBridge.activeInstances()) {
                if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                    continue;
                }
                if (!dimensionId.equals(ShaolibBridge.dimensionId(instance))) {
                    continue;
                }
                Vec3 center = ShaolibBridge.position(instance);
                if (center == null) {
                    continue;
                }
                Vec3 relative = center.subtract(origin);
                double distanceSqr = relative.lengthSqr();
                if (distanceSqr > SEEKER_RANGE * SEEKER_RANGE || distanceSqr < 1.0E-8) {
                    continue;
                }
                if (relative.dot(direction) / Math.sqrt(distanceSqr) < SEEKER_HALF_COS) {
                    continue;
                }
                if (distanceSqr < bestDistanceSqr) {
                    bestDistanceSqr = distanceSqr;
                    best = center;
                    bestShaolib = ShaolibBridge.uuidFor(instance);
                    bestEntity = null;
                    bestSubLevel = null;
                }
            }
        }

        this.trackedEntityId = bestEntity;
        this.trackedSubLevelId = bestSubLevel;
        this.trackedShaolibId = bestShaolib;
        if (best != null) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ir missile acquired target entity={} sublevel={} shaolib={} dist={} pos={}",
                    bestEntity, bestSubLevel, bestShaolib, Math.sqrt(bestDistanceSqr), best);
        }
        return best;
    }

    private boolean isIrSeekerTarget(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        EntityType<?> type = entity.getType();
        if (type == BeamMissileRegistry.BEAMRIDER_TANSHE.get()
                || type == BeamMissileRegistry.INFRARED_TANSHE.get()
                || type == BeamMissileRegistry.AIRCRAFT_INFRARED_TANSHE.get()) {
            return false;
        }
        return TargetClassifier.isCompatMissile(entity) || isMianbaoCountermeasure(entity);
    }

    static boolean isMianbaoCountermeasure(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null || !"mianbaos_modernwarfare".equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("countermeasure")) {
            return true;
        }
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().contains("Countermeasure")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean countsAsFuseEntity(Entity entity) {
        return super.countsAsFuseEntity(entity) || isMianbaoCountermeasure(entity);
    }
}
