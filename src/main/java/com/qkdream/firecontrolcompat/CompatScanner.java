package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoCruiseMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Adds hostile ordnance entities (vestalihy / CBCMS / mianbaos) and Shaolib
 * Tau/Hellfire instances to the existing fire-control contact pipelines.
 */
public final class CompatScanner {

    private CompatScanner() {
    }

    /** Appends hostile-entity and Shaolib contacts to the connected display scan. */
    public static void appendDisplayContacts(
            ConnectedDisplayBlockEntity display,
            List<ConnectedDisplayBlockEntity.Contact> found,
            Vec3 origin,
            double radarRange,
            Vec3 radarVelocity
    ) {
        Level level = display.getLevel();
        if (level == null) {
            return;
        }
        Set<UUID> existing = new HashSet<>();
        for (ConnectedDisplayBlockEntity.Contact contact : found) {
            existing.add(contact.key().id());
        }
        Set<UUID> visited = new HashSet<>();

        scanEntities(level, origin, radarRange, entity -> {
            if (!visited.add(entity.getUUID()) || existing.contains(entity.getUUID())) {
                return;
            }
            if (isOwnFireControlOrdnance(entity)) {
                return;
            }
            boolean ownMissile = TargetClassifier.isCompatMissile(entity);
            if (!ownMissile && (MianbaoAirDefenseCompat.isMissile(entity)
                    || MianbaoMissileCompat.isGuidedMissile(entity)
                    || MianbaoCruiseMissileCompat.isRadarMissile(entity)
                    || MianbaoAircraftCompat.isAirToGroundMissile(entity))) {
                return;
            }
            if (!ownMissile) {
                return;
            }
            Vec3 center = MianbaoMissileCompat.getWorldPosition(entity);
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(entity);
            double distance = center.distanceTo(origin);
            if (distance > radarRange || distance < 1.0) {
                return;
            }
            Vec3 los = center.subtract(origin).normalize();
            double closing = -velocity.subtract(radarVelocity).dot(los) * 20.0;
            found.add(new ConnectedDisplayBlockEntity.Contact(
                    new ConnectedDisplayBlockEntity.TargetKey(entity.getUUID(), false),
                    Set.of(),
                    center,
                    velocity,
                    entity.getBoundingBox(),
                    distance,
                    closing,
                    true
            ));
        });

        appendShaolibDisplayContacts(level, found, origin, radarRange, radarVelocity, existing);
    }

    /** Appends hostile-entity and Shaolib contacts to the stabilizer controller radar scan. */
    public static void appendRadarContacts(StabilizerControllerBlockEntity controller, List<StabilizerControllerBlockEntity.RadarContact> contacts) {
        Level level = controller.getLevel();
        if (level == null) {
            return;
        }
        Vec3 origin = controller.getRadarOrigin();
        boolean aircraft = controller.isAircraftRadarMode();
        double range = aircraft ? controller.getAircraftRadarRange() : controller.getRadarRange();
        Set<UUID> existing = new HashSet<>();
        for (StabilizerControllerBlockEntity.RadarContact contact : contacts) {
            existing.add(contact.subLevelId());
        }
        Set<UUID> visited = new HashSet<>();

        scanEntities(level, origin, range, entity -> {
            if (!visited.add(entity.getUUID()) || existing.contains(entity.getUUID())) {
                return;
            }
            if (isOwnFireControlOrdnance(entity)) {
                return;
            }
            boolean ownMissile = TargetClassifier.isCompatMissile(entity);
            if (!ownMissile && MianbaoAirDefenseCompat.isMissile(entity)) {
                return;
            }
            if (!ownMissile) {
                return;
            }
            Vec3 center = MianbaoMissileCompat.getWorldPosition(entity);
            AABB bounds = entity.getBoundingBox();
            if (!inRadarVolume(controller, aircraft, origin, center, bounds, range)) {
                return;
            }
            contacts.add(new StabilizerControllerBlockEntity.RadarContact(
                    entity.getUUID(),
                    center,
                    MianbaoMissileCompat.getWorldVelocity(entity),
                    bounds,
                    Set.of()
            ));
        });

        appendShaolibRadarContacts(level, contacts, existing, controller, aircraft, origin, range);
    }

    private static boolean isOwnFireControlOrdnance(Entity entity) {
        boolean flagged = entity.getPersistentData().getBoolean("CreateFireControlDisplayGuided")
                || entity.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || entity.getPersistentData().getBoolean("CreateFireControlIntercepted");
        if (!flagged) {
            return false;
        }
        // The heavy air-defense missile must remain a visible missile threat
        // so the automatic missile defense can engage it.
        return !TargetClassifier.isHeavyAaMissile(entity);
    }

    private interface EntityConsumer {
        void accept(Entity entity);
    }

    private static void scanEntities(Level level, Vec3 origin, double range, EntityConsumer consumer) {
        AABB query = new AABB(origin, origin).inflate(range);
        Level root = rootLevel(level);
        if (root != null && root != level) {
            for (Entity entity : root.getEntitiesOfClass(Entity.class, query, entity -> entity.isAlive() && !entity.isRemoved())) {
                consumer.accept(entity);
            }
        }
        for (Entity entity : level.getEntitiesOfClass(Entity.class, query, entity -> entity.isAlive() && !entity.isRemoved())) {
            consumer.accept(entity);
        }
    }

    private static Level rootLevel(Level level) {
        try {
            return SubLevelContainer.getContainer(level).getLevel();
        } catch (Throwable t) {
            return level;
        }
    }

    private static boolean inRadarVolume(
            StabilizerControllerBlockEntity controller,
            boolean aircraft,
            Vec3 origin,
            Vec3 center,
            AABB bounds,
            double range
    ) {
        if (center.distanceToSqr(origin) > range * range) {
            return false;
        }
        if (aircraft) {
            Vec3 forward = controller.getAircraftRadarForward();
            Vec3 up = controller.getAircraftRadarUp();
            Vec3 right = forward.cross(up).normalize();
            Vec3 planarForward = forward.subtract(up.scale(forward.dot(up))).normalize();
            Vec3 delta = center.subtract(origin);
            double longitudinal = delta.dot(planarForward);
            if (longitudinal <= 0.0) {
                return false;
            }
            double azimuth = Math.toDegrees(Math.atan2(delta.dot(right), longitudinal));
            double elevation = Math.toDegrees(Math.atan2(delta.dot(up), Math.sqrt(longitudinal * longitudinal + Math.pow(delta.dot(right), 2.0))));
            return Math.abs(azimuth) <= controller.getAircraftRadarHorizontalHalfAngle()
                    && Math.abs(elevation) <= controller.getAircraftRadarVerticalAngle() * 0.5;
        }

        double closestX = Math.max(bounds.minX, Math.min(bounds.maxX, origin.x));
        double closestZ = Math.max(bounds.minZ, Math.min(bounds.maxZ, origin.z));
        double horizontalMin = Math.sqrt((closestX - origin.x) * (closestX - origin.x) + (closestZ - origin.z) * (closestZ - origin.z));
        double horizontalMax = 0.0;
        for (double x : new double[]{bounds.minX, bounds.maxX}) {
            for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                horizontalMax = Math.max(horizontalMax, Math.sqrt((x - origin.x) * (x - origin.x) + (z - origin.z) * (z - origin.z)));
            }
        }
        double lowY = bounds.minY - origin.y;
        double highY = bounds.maxY - origin.y;
        double minElevation = Math.toDegrees(Math.atan2(lowY, Math.max(1.0E-6, lowY <= 0.0 ? horizontalMin : horizontalMax)));
        double maxElevation = Math.toDegrees(Math.atan2(highY, Math.max(1.0E-6, highY >= 0.0 ? horizontalMin : horizontalMax)));
        return maxElevation >= -controller.getRadarDepression() && minElevation <= controller.getRadarElevation();
    }

    private static void appendShaolibDisplayContacts(
            Level level,
            List<ConnectedDisplayBlockEntity.Contact> found,
            Vec3 origin,
            double radarRange,
            Vec3 radarVelocity,
            Set<UUID> existing
    ) {
        if (!ShaolibBridge.available()) {
            return;
        }
        Level root = rootLevel(level);
        if (!(root instanceof ServerLevel serverLevel)) {
            return;
        }
        String dimension = serverLevel.dimension().location().toString();
        for (Object instance : ShaolibBridge.activeInstances()) {
            if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                continue;
            }
            if (!dimension.equals(ShaolibBridge.dimensionId(instance))) {
                continue;
            }
            UUID id = ShaolibBridge.uuidFor(instance);
            if (existing.contains(id)) {
                continue;
            }
            Vec3 center = ShaolibBridge.position(instance);
            if (center == null) {
                continue;
            }
            double distance = center.distanceTo(origin);
            if (distance > radarRange || distance < 1.0) {
                continue;
            }
            Vec3 velocity = ShaolibBridge.velocity(instance);
            Vec3 los = center.subtract(origin).normalize();
            double closing = -velocity.subtract(radarVelocity).dot(los) * 20.0;
            found.add(new ConnectedDisplayBlockEntity.Contact(
                    new ConnectedDisplayBlockEntity.TargetKey(id, false),
                    Set.of(),
                    center,
                    velocity,
                    new AABB(center, center).inflate(0.75),
                    distance,
                    closing,
                    true
            ));
        }
    }

    private static void appendShaolibRadarContacts(
            Level level,
            List<StabilizerControllerBlockEntity.RadarContact> contacts,
            Set<UUID> existing,
            StabilizerControllerBlockEntity controller,
            boolean aircraft,
            Vec3 origin,
            double range
    ) {
        if (!ShaolibBridge.available()) {
            return;
        }
        Level root = rootLevel(level);
        if (!(root instanceof ServerLevel serverLevel)) {
            return;
        }
        String dimension = serverLevel.dimension().location().toString();
        for (Object instance : ShaolibBridge.activeInstances()) {
            if (instance == null || !ShaolibBridge.isTauOrHellfire(instance) || !ShaolibBridge.isAlive(instance)) {
                continue;
            }
            if (!dimension.equals(ShaolibBridge.dimensionId(instance))) {
                continue;
            }
            UUID id = ShaolibBridge.uuidFor(instance);
            if (existing.contains(id)) {
                continue;
            }
            Vec3 center = ShaolibBridge.position(instance);
            if (center == null) {
                continue;
            }
            AABB bounds = new AABB(center, center).inflate(0.75);
            if (!inRadarVolume(controller, aircraft, origin, center, bounds, range)) {
                continue;
            }
            contacts.add(new StabilizerControllerBlockEntity.RadarContact(
                    id,
                    center,
                    ShaolibBridge.velocity(instance),
                    bounds,
                    Set.of()
            ));
        }
    }
}
