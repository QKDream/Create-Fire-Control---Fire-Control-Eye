package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Which rounds fire control is flying, wherever they come from.
 *
 * <p>Fire control keeps its own missiles in sight for the whole of the flight:
 * it lifts the tracking range, draws them past their own render distance and
 * leaves a trail the length of the path. All of that keys off its own missile
 * list, and a round flown by this mod - or by one of the launchers this mod
 * teaches fire control to fly - is not on that list, so it drops out of the
 * frame a few dozen blocks after the launch and is only heard from again when
 * it goes off. This is the list that closes that gap: our own guided rounds by
 * name, plus anything else carrying one of fire control's guidance marks.</p>
 */
public final class FireControlMunitions {

    /** The marks fire control writes onto a round it has taken over. */
    private static final String[] GUIDED_KEYS = {
            "CreateFireControlGuided",
            "CreateFireControlAaGuided",
            "CreateFireControlAircraftGuided",
            "CreateFireControlCruiseGuided",
            "CreateFireControlBallisticGuided",
            "CreateFireControlDisplayGuided",
            "CreateFireControlOpticalGuided"
    };

    /** The rounds that carry a mark of their own kind rather than of ours. */
    private static final String MIANBAO = "mianbaos_modernwarfare";

    /**
     * The rounds this mod knows are flown by fire control: our own missiles, the
     * medium cannon's gun-launched one, and the cruise missile a bound rail
     * throws - she is 面包学's own round, flown 面包学's own way, and the only
     * thing of fire control's about her is the target the computer writes on the
     * rail before she leaves.
     */
    private static final Set<ResourceLocation> GUIDED = Set.of(
            id("infrared_tanshe"),
            // RETIRED(ir-arm-rounds): {@code aircraft_infrared_tanshe} is not registered any more;
            // the airborne infrared round is 面包学's own from its next version on.
            id("loitering_tanshe"),
            id("heavy_aa_tanshe"),
            id("atgm_mediumshell"),
            from(MIANBAO, "cruisemissiletanshe"),
            from(MIANBAO, "cruisemissilelowtanshe"));

    private FireControlMunitions() {
    }

    /** Whether fire control is flying this round, so it has to stay in sight. */
    public static boolean isTakenOver(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            if (GUIDED.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))) {
                return true;
            }
            CompoundTag data = entity.getPersistentData();
            for (String key : GUIDED_KEYS) {
                if (data.getBoolean(key)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Where a round stands in the world, taken the way fire control takes it:
     * a round on a welded structure is answered in the world's own coordinates
     * rather than the plot's.
     */
    public static Vec3 worldPosition(Entity entity) {
        try {
            return MianbaoMissileCompat.getWorldPosition(entity);
        } catch (Throwable ignored) {
            return entity.position();
        }
    }

    private static ResourceLocation id(String path) {
        return from(FireControlCompat.MOD_ID, path);
    }

    private static ResourceLocation from(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
