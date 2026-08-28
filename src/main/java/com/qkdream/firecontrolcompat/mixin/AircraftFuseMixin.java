package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftGuidanceHandler;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.SableMissileFuze;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import java.util.Collection;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Extends the air-to-air proximity fuse to radar contacts that are not Sable
 * sublevels: entity ammunition (vestalihy / CBCMS / mianbaos) and Shaolib
 * Tau/Hellfire instances. The original check only measures Sable centers of
 * mass, so an entity/Shaolib lock could never satisfy the fuse and the
 * air-to-air missile flew through its target instead of detonating.
 */
@Mixin(MianbaoAircraftGuidanceHandler.class)
public abstract class AircraftFuseMixin {

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/controller/SableMissileFuze;anyCenterOfMassWithinRange(Lnet/minecraft/server/level/ServerLevel;Ljava/util/Collection;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;D)Z"
            )
    )
    private static boolean firecontrolcompat$aircraftFuse(
            ServerLevel level,
            Collection<UUID> targetIds,
            Vec3 from,
            Vec3 to,
            double range
    ) {
        if (SableMissileFuze.anyCenterOfMassWithinRange(level, targetIds, from, to, range)) {
            return true;
        }
        if (level == null || targetIds == null || range <= 0.0) {
            return false;
        }
        double rangeSqr = range * range;
        for (UUID id : targetIds) {
            Vec3 center = null;
            if (ShaolibBridge.isShaolibId(id)) {
                center = ShaolibBridge.position(id);
            } else {
                Entity entity = level.getEntity(id);
                if (entity != null && entity.isAlive()) {
                    center = MianbaoMissileCompat.getWorldPosition(entity);
                }
            }
            if (center != null && distanceToSegmentSqr(center, from, to) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }

    private static double distanceToSegmentSqr(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.subtract(from);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 1.0E-12) {
            return point.distanceToSqr(from);
        } else {
            double t = Math.max(0.0, Math.min(1.0, point.subtract(from).dot(segment) / lengthSqr));
            return point.distanceToSqr(from.add(segment.scale(t)));
        }
    }
}

