package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bridges Shaolib (TAOV Tau/Hellfire) targets through the display interceptor
 * guidance. Tau/Hellfire are not entities, so the entity lookup, alive check,
 * target resolution and intercept handling are adapted here while entity
 * targets keep the original behavior.
 *
 * <p>The per-iteration proxy state is cleared at the top of every
 * {@code resolveTarget} call (the first call of each guided-missile
 * iteration) and right after an intercept, so a stale proxy from a previous
 * tick can never leak into the next iteration.</p>
 */
@Mixin(AutoDefenseMissileGuidance.class)
public abstract class AutoDefenseGuidanceMixin {

    @Unique
    private static Entity firecontrolcompat$proxyEntity;

    @Unique
    private static UUID firecontrolcompat$proxyShaolibId;

    @Invoker("findEntity")
    private static Entity invokeFindEntityDisplay(ConnectedDisplayBlockEntity display, UUID id) {
        throw new AbstractMethodError();
    }

    @Inject(method = "isThreatAlive", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$isThreatAlive(
            ConnectedDisplayBlockEntity display,
            ConnectedDisplayBlockEntity.Contact contact,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (contact != null && contact.missile() && ShaolibBridge.isShaolibId(contact.key().id())) {
            cir.setReturnValue(ShaolibBridge.isAlive(contact.key().id()));
        }
    }

    @Inject(method = "resolveTarget", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$resolveTarget(
            Entity missile,
            ConnectedDisplayBlockEntity.Contact contact,
            CallbackInfoReturnable<Vec3> cir
    ) {
        firecontrolcompat$proxyEntity = null;
        firecontrolcompat$proxyShaolibId = null;
        if (contact != null && contact.missile() && ShaolibBridge.isShaolibId(contact.key().id())) {
            cir.setReturnValue(ShaolibBridge.position(contact.key().id()));
        }
    }

    @Redirect(
            method = "onTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/display/AutoDefenseMissileGuidance;findEntity(Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity;Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;"
            )
    )
    private static Entity firecontrolcompat$redirectFindEntityInTick(ConnectedDisplayBlockEntity display, UUID id) {
        Entity original = invokeFindEntityDisplay(display, id);
        if (original != null || !ShaolibBridge.isShaolibId(id) || !ShaolibBridge.isAlive(id)) {
            firecontrolcompat$proxyEntity = null;
            firecontrolcompat$proxyShaolibId = null;
            return original;
        }
        Entity proxy = firecontrolcompat$findGuidedInterceptor(display, id);
        if (proxy == null) {
            firecontrolcompat$proxyEntity = null;
            firecontrolcompat$proxyShaolibId = null;
            return null;
        }
        firecontrolcompat$proxyEntity = proxy;
        firecontrolcompat$proxyShaolibId = id;
        return proxy;
    }

    @Redirect(
            method = "onTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/MianbaoMissileCompat;getWorldPosition(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private static Vec3 firecontrolcompat$redirectGetWorldPosition(Entity entity) {
        if (entity == firecontrolcompat$proxyEntity && firecontrolcompat$proxyShaolibId != null) {
            Vec3 position = ShaolibBridge.position(firecontrolcompat$proxyShaolibId);
            return position == null ? MianbaoMissileCompat.getWorldPosition(entity) : position;
        }
        return MianbaoMissileCompat.getWorldPosition(entity);
    }

    @Inject(
            method = "onTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/display/AutoDefenseMissileGuidance;intercept(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lcom/hooya/stabilizedturret/content/display/AutoDefenseMissileGuidance$Guided;)V"
            )
    )
    private static void firecontrolcompat$beforeIntercept(CallbackInfo ci) {
        if (firecontrolcompat$proxyShaolibId != null) {
            ShaolibBridge.detonate(firecontrolcompat$proxyShaolibId);
            firecontrolcompat$proxyEntity = null;
            firecontrolcompat$proxyShaolibId = null;
        }
    }

    @Unique
    private static Entity firecontrolcompat$findGuidedInterceptor(ConnectedDisplayBlockEntity display, UUID id) {
        Level level = display == null ? null : display.getLevel();
        if (level == null) {
            return null;
        }
        Level root = SubLevelContainer.getContainer(level).getLevel();
        AABB query = new AABB(display.getBlockPos()).inflate(512.0);
        if (root != null && root != level) {
            Entity found = firecontrolcompat$findGuidedInterceptorIn(root, query, id);
            if (found != null) {
                return found;
            }
        }
        return firecontrolcompat$findGuidedInterceptorIn(level, query, id);
    }

    @Unique
    private static Entity firecontrolcompat$findGuidedInterceptorIn(Level level, AABB query, UUID id) {
        for (Entity entity : level.getEntitiesOfClass(Entity.class, query, candidate -> candidate.isAlive() && !candidate.isRemoved())) {
            CompoundTag data = entity.getPersistentData();
            if (data.getBoolean("CreateFireControlDisplayGuided") && data.contains("CreateFireControlDisplayContact")) {
                CompoundTag contactTag = data.getCompound("CreateFireControlDisplayContact");
                if (contactTag.hasUUID("Id") && contactTag.getUUID("Id").equals(id)) {
                    return entity;
                }
            }
        }
        return null;
    }
}