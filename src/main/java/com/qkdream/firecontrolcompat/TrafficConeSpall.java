package com.qkdream.firecontrolcompat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Terminal ballistics values for the traffic cone round.
 *
 * <p>The cone is built on the discarding sabot shell, so it penetrates and
 * spalls like one - except that neither its entity id nor its class name
 * carries an "ap", "shot" or "inert" marker, which is all the terminal
 * ballistics classifier looks at. The round was therefore treated as a plain
 * high explosive shell and produced no fragments at all: the cone punched a
 * clean hole and left the wall standing.</p>
 *
 * <p>The values below give the round its own fragment profile: a large count
 * (still capped by the pack's own setting, with a higher floor so the cone
 * keeps its absurd character), a slightly stronger fragment and the narrow
 * forward cone of the sabot round whose ballistics it borrows.</p>
 */
public final class TrafficConeSpall {

    /** Entity id of the round, as registered by the CBC Modern Warfare bridge. */
    private static final String ENTITY_ID = "firecontrolcompat:traffic_cone_mediumshell";

    /** Fragment count multiplier, applied before the pack's cap. */
    public static final double SPALL_COUNT_MODIFIER = 8.0;

    /** Fragment damage multiplier; the sabot round it is built on uses 1.5. */
    public static final double SPALL_DAMAGE_MODIFIER = 1.15;

    /** Cone shape of the fragment spray, matching the sabot round (0.4). */
    public static final double SPALL_CONE_MODIFIER = 0.45;

    /** Fragment cap floor for this one round; a higher configured cap still wins. */
    public static final int SPALL_FRAGMENT_FLOOR = 96;

    private TrafficConeSpall() {
    }

    /** Whether the projectile is the traffic cone round. */
    public static boolean isTrafficCone(Entity entity) {
        if (entity == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && ENTITY_ID.equals(id.toString());
    }
}