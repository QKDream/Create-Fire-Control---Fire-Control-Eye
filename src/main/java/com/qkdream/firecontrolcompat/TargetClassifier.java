package com.qkdream.firecontrolcompat;

import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Classifies hostile ordnance entities from CBC Military Supplement
 * (cbcmoreshells), vestalihy and mianbaos_modernwarfare so both the
 * connected display radar and the stabilizer controller radar can track them.
 */
public final class TargetClassifier {

    public static boolean isCompatMissile(Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null) {
            return false;
        }
        String namespace = id.getNamespace();
        String path = id.getPath().toLowerCase(Locale.ROOT);

        if ("vestalihy".equals(namespace)) {
            if (path.equals("ptur") || path.equals("tow") || path.equals("ptur_jet") || path.equals("malytka") || path.contains("tubus")) {
                return true;
            }
            return hasClassFragment(entity, "PturEntity", "MalytkaEntity", "TowEntity");
        }
        if ("cbcmoreshells".equals(namespace)) {
            // Air bombs, torpedoes, rockets, depth charges and missiles.
            return path.contains("torpedo")
                    || path.contains("rocket")
                    || path.contains("depth_charge")
                    || path.contains("depthcharge")
                    || path.contains("bomb")
                    || path.contains("missile");
        }
        if ("mianbaos_modernwarfare".equals(namespace)) {
            return path.contains("missile")
                    || path.contains("rocket")
                    || path.contains("tanshe")
                    || path.startsWith("agm_")
                    || path.startsWith("jdam");
        }
        // Fire Control Eye's own projectiles: beam-rider, infrared (ground/air),
        // small loitering munition and heavy air-defense missile.
        if ("firecontrolcompat".equals(namespace)) {
            return path.contains("tanshe");
        }
        return false;
    }

    /** The heavy air-defense missile stays interceptable even while fire control guides it. */
    public static boolean isHeavyAaMissile(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null
                && "firecontrolcompat".equals(id.getNamespace())
                && "heavy_aa_tanshe".equals(id.getPath());
    }

    private static boolean hasClassFragment(Entity entity, String... fragments) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            for (String fragment : fragments) {
                if (name.contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private TargetClassifier() {
    }
}
