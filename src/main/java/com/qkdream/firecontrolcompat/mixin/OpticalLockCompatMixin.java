package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.TargetClassifier;
import java.util.ArrayList;
import java.util.List;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    @Accessor("lastAimSubLevelId")
    public abstract UUID firecontrolcompat$getLastAimSubLevelId();

    @Accessor("lockAimOffset")
    public abstract void firecontrolcompat$setLockAimOffset(Vec3 value);

    @Accessor("opticalLockAcquiredAt")
    public abstract void firecontrolcompat$setOpticalLockAcquiredAt(long value);

    /**
     * How far off the aim ray a missile may sit and still be locked, as a
     * tangent: about one degree.
     *
     * <p>The pack's own optical search sweeps a wide cone, and using that for
     * missiles let a gunner lock something his sight was not on: the mount then
     * showed the lock, and the model turned to it, while the sight stayed where
     * the gunner was looking - which from outside reads as the pod's animation
     * pointing somewhere the view does not. A living target is only ever locked
     * on the aim ray; a missile is held to the same rule here.</p>
     */
    @Unique
    private static final double MISSILE_RAY_HALF_TAN = 0.0175D;

    @Accessor("opticalSearchUntil")
    public abstract void firecontrolcompat$setOpticalSearchUntil(long value);

    @Accessor("hasAimTarget")
    public abstract boolean firecontrolcompat$getHasAimTarget();

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

    @Invoker("hasInputConfigured")
    public abstract boolean firecontrolcompat$invokeHasInputConfigured(int action);

    @Invoker("findSeatedGunner")
    public abstract ServerPlayer firecontrolcompat$invokeFindSeatedGunner();

    @Inject(method = "acquireOpticalLock", at = @At("HEAD"))
    private void firecontrolcompat$acquireOpticalLockHead(ServerPlayer gunner, CallbackInfo ci) {
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] acquire optical lock head: hasAimTarget={} hasAimRay={} lastAimSubLevelId={} podView={}",
                firecontrolcompat$getHasAimTarget(),
                firecontrolcompat$getHasAimRay(),
                firecontrolcompat$getLastAimSubLevelId(),
                ((StabilizerControllerBlockEntity) (Object) this).isPodViewEnabled());
    }

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
        Entity missile = living == null ? firecontrolcompat$findMissileOnAimRay(player) : null;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] optical lock acquire attempt: hasAimTarget={} hasAimRay={} living={} missile={} lastAimSubLevelId={}",
                firecontrolcompat$getHasAimTarget(),
                firecontrolcompat$getHasAimRay(),
                living != null,
                missile != null,
                firecontrolcompat$getLastAimSubLevelId());
        if (living != null) {
            return living;
        }
        if (missile != null) {
            firecontrolcompat$applyEntityLock(player, missile.getUUID());
        }
        return null;
    }

    /**
     * Keeps the lock alive by answering <b>where</b> the locked target is.
     *
     * <h2>An ordnance lock used to be dropped on the very next tick</h2>
     *
     * <p>This hook returns {@code null} for anything that is not a {@link LivingEntity} unless the
     * mathematical fallback below finds it, and {@code updateOpticalLock} treats a {@code null}
     * target as "the target is gone" and calls {@code clearOpticalLock()}. So the lock was written
     * into {@code lockedEntityId}, the reticle and the name showed it for one tick, and the next
     * server tick wiped it - for every missile lock this mod has ever offered, not only for the
     * mathematical ones. Nothing downstream (the pod's aim point, this mod's own
     * {@code LockSource.OPTICAL} launch gate) could ever see a held lock.</p>
     *
     * <p>It now answers the target's live position for ordnance as well, which is what makes a lock
     * a lock: the sight follows the round, exactly as it follows a vehicle. That pins the pod's
     * sight to a moving round, which is unavoidable if the lock is to mean anything - a lock that
     * cannot say where its target is cannot guide a weapon at it.</p>
     *
     * <h2>Mathematical rounds</h2>
     *
     * <p>A round fire control flies mathematically is not in the level, so
     * {@code serverLevel.getEntity(id)} used to answer {@code null} and the lock died immediately.
     * {@link MathLocks#resolve} reads the pack's own {@code ACTIVE} table instead, and every hit is
     * reported as {@code math lock: type=.. uuid=.. source=optical selectable=true}.</p>
     */
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
            // Belt and braces: the level lookup already falls through to the mathematical table
            // (MathEntityLookupMixin), but a lock made before that hook was reached - or a level
            // whose container cannot be resolved - still gets one more chance here.
            entity = com.qkdream.firecontrolcompat.missile.MathLocks.resolve(level, locked);
        }
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            cir.setReturnValue(null);
            return;
        }
        boolean mathematical = com.qkdream.firecontrolcompat.missile.MathLocks.isMathMissile(entity);
        if (!(entity instanceof LivingEntity) && !TargetClassifier.isCompatMissile(entity) && !mathematical) {
            cir.setReturnValue(null);
            return;
        }
        if (entity instanceof LivingEntity) {
            cir.setReturnValue(entity.getBoundingBox().getCenter());
            return;
        }
        Vec3 position = com.qkdream.firecontrolcompat.missile.MathLocks.position(entity);
        if (position == null) {
            position = MianbaoMissileCompat.getWorldPosition(entity);
        }
        if (position == null) {
            position = entity.getBoundingBox().getCenter();
        }
        if (mathematical) {
            com.qkdream.firecontrolcompat.missile.MathLocks.reportSelectable(entity, "optical");
        }
        cir.setReturnValue(position);
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
            entity = com.qkdream.firecontrolcompat.missile.MathLocks.resolve(level, locked);
        }
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            cir.setReturnValue(null);
            return;
        }
        boolean mathematical = com.qkdream.firecontrolcompat.missile.MathLocks.isMathMissile(entity);
        if (!(entity instanceof LivingEntity) && !TargetClassifier.isCompatMissile(entity) && !mathematical) {
            cir.setReturnValue(null);
            return;
        }
        if (entity instanceof LivingEntity) {
            cir.setReturnValue(entity.getBoundingBox());
            return;
        }
        // Without a box the visibility test in updateOpticalLock has nothing to test and would
        // report the round as permanently occluded, dropping the lock after forty ticks.
        AABB bounds = com.qkdream.firecontrolcompat.missile.MathLocks.bounds(entity);
        cir.setReturnValue(bounds != null ? bounds : entity.getBoundingBox());
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
        Entity locked = com.qkdream.firecontrolcompat.missile.MathLocks.resolve(level, entityId);
        if (locked != null && com.qkdream.firecontrolcompat.missile.MathLocks.isMathMissile(locked)) {
            com.qkdream.firecontrolcompat.missile.MathLocks.reportSelectable(locked, "optical");
        }
        player.displayClientMessage(Component.literal("已锁定目标"), true);
    }

    @Unique
    private Entity firecontrolcompat$findMissileOnAimRay(ServerPlayer player) {
        if (!com.qkdream.firecontrolcompat.PodLinkSwitch.enabled()) {
            // The whole lock extension is off with the rest of the pod chain.
            return null;
        }
        if (((StabilizerControllerBlockEntity) (Object) this).isPodViewEnabled()) {
            // The pod is a designation sight for air-to-ground work: what it locks
            // is the place a bomb, a cruise missile or a glide bomb is meant to go,
            // never the ordnance flying about. Locking those belongs to the gun
            // sight, and that path is left exactly as it was.
            return null;
        }
        ServerLevel serverLevel = player.serverLevel();
        Vec3 origin;
        Vec3 direction = null;
        if (firecontrolcompat$getHasAimRay() && firecontrolcompat$getLastAimRayDirection().lengthSqr() > 1.0E-8D) {
            origin = firecontrolcompat$getLastAimRayOrigin();
            direction = firecontrolcompat$getLastAimRayDirection().normalize();
        } else {
            direction = ((StabilizerControllerBlockEntity) (Object) this).getMeasuredAimDirection();
            if (direction != null && direction.lengthSqr() > 1.0E-8D) {
                origin = firecontrolcompat$invokeGetAimOrigin();
            } else {
                origin = player.getEyePosition();
                direction = firecontrolcompat$getDesiredWorldTarget().subtract(origin);
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
        // The sweep the pack's own acquisition uses walks the level, which cannot see a round that
        // never joined it. The mathematical rounds are therefore carried in from the pack's table
        // and scored by exactly the same rule - same cone, same visibility test, same tie-break -
        // so a round of ours is one more target on the sight, not a special case.
        List<Entity> pool = new ArrayList<>();
        AABB query = new AABB(origin, origin).inflate(1024.0D);
        for (Entity entity : serverLevel.getEntitiesOfClass(
                Entity.class, query, entity -> entity.isAlive() && !entity.isRemoved())) {
            if (TargetClassifier.isCompatMissile(entity)) {
                pool.add(entity);
            }
        }
        for (Entity round : com.qkdream.firecontrolcompat.missile.MathLocks.rounds(serverLevel)) {
            if (round != null && round.isAlive() && !round.isRemoved() && !pool.contains(round)) {
                pool.add(round);
            }
        }
        double bestScore = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        Entity best = null;
        for (Entity entity : pool) {
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
            double reachX = radius + MISSILE_RAY_HALF_TAN * along;
            double reachY = radius + MISSILE_RAY_HALF_TAN * along;
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
