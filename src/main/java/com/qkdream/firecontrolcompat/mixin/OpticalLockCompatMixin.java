package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.TargetClassifier;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends the stabilizer controller optical lock to hostile ordnance.
 *
 * <p>Vanilla {@code acquireOpticalLock} only accepts {@link LivingEntity}
 * targets found on the aim ray (or Sable sublevels). This mixin keeps that
 * behaviour and, when no living target is found, scans the same optical cone
 * for compat missiles (CBCMS, TAOV, vestalihy, mianbaos). A found missile is
 * stored in {@code lockedEntityId} exactly like a living target, without
 * identifying the target category to the gunner.
 *
 * <p>{@code opticalLockTarget} and {@code getOpticalLockBounds} only resolve
 * the locked entity when it is a {@link LivingEntity}, so the two methods are
 * also extended to resolve compat missiles, letting the turret track them
 * and the visibility check keep the lock alive.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class OpticalLockCompatMixin {

    @Accessor("lockedEntityId")
    public abstract UUID firecontrolcompat$getLockedEntityId();

    @Accessor("lockedEntityId")
    public abstract void firecontrolcompat$setLockedEntityId(UUID value);

    @Accessor("lockedSubLevelId")
    public abstract void firecontrolcompat$setLockedSubLevelId(UUID value);

    @Accessor("lastAimSubLevelId")
    public abstract void firecontrolcompat$setLastAimSubLevelId(UUID value);

    @Accessor("lockAimOffset")
    public abstract void firecontrolcompat$setLockAimOffset(Vec3 value);

    @Accessor("opticalLockAcquiredAt")
    public abstract void firecontrolcompat$setOpticalLockAcquiredAt(long value);

    @Accessor("opticalSearchUntil")
    public abstract void firecontrolcompat$setOpticalSearchUntil(long value);

    @Accessor("hasAimRay")
    public abstract boolean firecontrolcompat$getHasAimRay();

    @Accessor("desiredWorldTarget")
    public abstract Vec3 firecontrolcompat$getDesiredWorldTarget();

    @Accessor("lastAimRayOrigin")
    public abstract Vec3 firecontrolcompat$getLastAimRayOrigin();

    @Accessor("lastAimRayDirection")
    public abstract Vec3 firecontrolcompat$getLastAimRayDirection();

    @Accessor("opticalSearchHalfTanX")
    public abstract double firecontrolcompat$getOpticalSearchHalfTanX();

    @Accessor("opticalSearchHalfTanY")
    public abstract double firecontrolcompat$getOpticalSearchHalfTanY();

    @Invoker("findLivingTargetOnAimRay")
    public abstract LivingEntity firecontrolcompat$invokeFindLivingTargetOnAimRay(ServerPlayer player);

    @Invoker("getAimOrigin")
    public abstract Vec3 firecontrolcompat$invokeGetAimOrigin();

    @Invoker("ownControlledSubLevelIds")
    public abstract Set<UUID> firecontrolcompat$invokeOwnControlledSubLevelIds();

    @Invoker("isTargetAtLeastPartlyVisible")
    public abstract boolean firecontrolcompat$invokeIsTargetAtLeastPartlyVisible(
            Vec3 origin, Vec3 target, AABB bounds, UUID subLevelId, Set<UUID> ownIds);

    @Invoker("resetOpticalOcclusion")
    public abstract void firecontrolcompat$invokeResetOpticalOcclusion();

    @Redirect(
            method = "acquireOpticalLock",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity;findLivingTargetOnAimRay(Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/world/entity/LivingEntity;"
            )
    )
    private LivingEntity firecontrolcompat$acquireOpticalLockTarget(
            StabilizerControllerBlockEntity instance, ServerPlayer player) {
        LivingEntity living = firecontrolcompat$invokeFindLivingTargetOnAimRay(player);
        if (living != null) {
            return living;
        }
        Entity missile = firecontrolcompat$findMissileOnAimRay(player);
        if (missile == null) {
            return null;
        }
        firecontrolcompat$applyEntityLock(player, missile.getUUID());
        return null;
    }

    @Inject(method = "opticalLockTarget", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$opticalLockTargetHead(CallbackInfoReturnable<Vec3> cir) {
        Level level = ((StabilizerControllerBlockEntity) (Object) this).getLevel();
        if (level == null) {
            cir.setReturnValue(null);
            return;
        }
        UUID locked = firecontrolcompat$getLockedEntityId();
        if (locked == null) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            cir.setReturnValue(null);
            return;
        }
        Entity entity = serverLevel.getEntity(locked);
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            cir.setReturnValue(null);
            return;
        }
        if (!(entity instanceof LivingEntity) && !TargetClassifier.isCompatMissile(entity)) {
            cir.setReturnValue(null);
            return;
        }
        if (entity instanceof LivingEntity) {
            cir.setReturnValue(entity.getBoundingBox().getCenter());
        } else {
            Vec3 position = MianbaoMissileCompat.getWorldPosition(entity);
            cir.setReturnValue(position != null ? position : entity.getBoundingBox().getCenter());
        }
    }

    @Inject(method = "getOpticalLockBounds", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$opticalLockBoundsHead(CallbackInfoReturnable<AABB> cir) {
        Level level = ((StabilizerControllerBlockEntity) (Object) this).getLevel();
        if (level == null) {
            cir.setReturnValue(null);
            return;
        }
        UUID locked = firecontrolcompat$getLockedEntityId();
        if (locked == null) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            cir.setReturnValue(null);
            return;
        }
        Entity entity = serverLevel.getEntity(locked);
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            cir.setReturnValue(null);
            return;
        }
        if (!(entity instanceof LivingEntity) && !TargetClassifier.isCompatMissile(entity)) {
            cir.setReturnValue(null);
            return;
        }
        cir.setReturnValue(entity.getBoundingBox());
    }

    @Unique
    private void firecontrolcompat$applyEntityLock(ServerPlayer player, UUID entityId) {
        firecontrolcompat$setLockedEntityId(entityId);
        firecontrolcompat$setLockedSubLevelId(null);
        firecontrolcompat$setLastAimSubLevelId(null);
        firecontrolcompat$setLockAimOffset(Vec3.ZERO);
        Level level = ((StabilizerControllerBlockEntity) (Object) this).getLevel();
        if (level != null) {
            firecontrolcompat$setOpticalLockAcquiredAt(level.getGameTime());
        }
        firecontrolcompat$invokeResetOpticalOcclusion();
        firecontrolcompat$setOpticalSearchUntil(Long.MIN_VALUE);
        player.displayClientMessage(Component.literal("已锁定目标"), true);
    }

    @Unique
    private Entity firecontrolcompat$findMissileOnAimRay(ServerPlayer player) {
        ServerLevel serverLevel = player.serverLevel();
        Vec3 origin = firecontrolcompat$invokeGetAimOrigin();
        Vec3 direction = ((StabilizerControllerBlockEntity) (Object) this).getMeasuredAimDirection();
        if (direction == null || direction.lengthSqr() < 1.0E-8D) {
            if (firecontrolcompat$getHasAimRay() && firecontrolcompat$getLastAimRayDirection().lengthSqr() > 1.0E-8D) {
                origin = firecontrolcompat$getLastAimRayOrigin();
                direction = firecontrolcompat$getLastAimRayDirection().normalize();
            } else {
                origin = player.getEyePosition();
                direction = firecontrolcompat$getDesiredWorldTarget().subtract(origin).normalize();
            }
        }
        if (direction.lengthSqr() < 1.0E-8D) {
            return null;
        }
        direction = direction.normalize();
        Vec3 side = direction.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-8D) {
            side = new Vec3(1.0D, 0.0D, 0.0D);
        }
        side = side.normalize();
        Vec3 up = side.cross(direction).normalize();
        Set<UUID> own = firecontrolcompat$invokeOwnControlledSubLevelIds();
        double bestScore = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        Entity best = null;
        AABB query = new AABB(origin, origin).inflate(1024.0D);
        for (Entity entity : serverLevel.getEntitiesOfClass(
                Entity.class, query, entity -> entity.isAlive() && !entity.isRemoved())) {
            if (!TargetClassifier.isCompatMissile(entity)) {
                continue;
            }
            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 relative = center.subtract(origin);
            double along = relative.dot(direction);
            if (along <= 0.0D || along > 1024.0D) {
                continue;
            }
            AABB bounds = entity.getBoundingBox();
            double radius = 0.5D * Math.sqrt(
                    bounds.getXsize() * bounds.getXsize()
                            + bounds.getYsize() * bounds.getYsize()
                            + bounds.getZsize() * bounds.getZsize());
            double offsetX = Math.abs(relative.dot(side));
            double offsetY = Math.abs(relative.dot(up));
            double reachX = radius + firecontrolcompat$getOpticalSearchHalfTanX() * along;
            double reachY = radius + firecontrolcompat$getOpticalSearchHalfTanY() * along;
            if (offsetX > reachX || offsetY > reachY) {
                continue;
            }
            double score = Math.max(
                    offsetX / Math.max(1.0E-4D, reachX),
                    offsetY / Math.max(1.0E-4D, reachY));
            boolean better = score < bestScore - 1.0E-6D
                    || (Math.abs(score - bestScore) <= 1.0E-6D && along < bestDistance);
            if (better && firecontrolcompat$invokeIsTargetAtLeastPartlyVisible(
                    origin, center, bounds, null, own)) {
                bestScore = score;
                bestDistance = along;
                best = entity;
            }
        }
        return best;
    }
}
