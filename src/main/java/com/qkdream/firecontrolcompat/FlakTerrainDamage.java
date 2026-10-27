package com.qkdream.firecontrolcompat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.CreateBigCannons;
import rbasamoyai.createbigcannons.base.PartialBlockDamageManager;
import rbasamoyai.createbigcannons.munitions.ProjectileDamageHooks;

/**
 * Terrain damage of one fragment that hit a block.
 *
 * <p>Fragments used to only hurt entities, so an anti air burst fired into a
 * wall left no trace at all. They now wear the block down through Create Big
 * Cannons' own partial block damage: every fragment adds its share, the pack's
 * damage restriction and the terrain damage hooks stay in charge of what may
 * be broken, and a block that has taken ten times its own toughness gives
 * way.</p>
 *
 * <p>This is the flak path only. The fragments a penetrating round throws are
 * the terminal ballistics ones, which the pack applies on its own.</p>
 */
public final class FlakTerrainDamage {

    private static final float MIN_DAMAGE = 0.25F;
    private static final float MAX_DAMAGE = 80.0F;

    /** Cached answer of the pack's "may projectiles break blocks" setting. */
    private static volatile Boolean griefingAllowed;

    private FlakTerrainDamage() {
    }

    /**
     * Applies one fragment's worth of block damage.
     *
     * @param level  server level the block lives in
     * @param pos    block that was hit
     * @param state  state of that block
     * @param damage damage of this fragment, in the pack's own units
     */
    public static void apply(Level level, BlockPos pos, BlockState state, float damage) {
        if (level == null || pos == null || state == null || state.isAir()) {
            return;
        }
        if (!(level instanceof ServerLevel)) {
            return;
        }
        try {
            if (state.getDestroySpeed(level, pos) < 0.0F) {
                return;
            }
            if (!griefingAllowed()) {
                return;
            }
            if (!ProjectileDamageHooks.canDamageTerrain(level, pos)) {
                return;
            }
            float wear = Mth.clamp(damage, MIN_DAMAGE, MAX_DAMAGE);
            CreateBigCannons.BLOCK_DAMAGE.damageBlock(pos, wear, state, level, PartialBlockDamageManager::destroyBlockDefault);
        } catch (Throwable ignored) {
            // A block that cannot be resolved simply keeps its integrity.
        }
    }

    /**
     * Fragment damage of a fragmentation burst, scaled by the speed the
     * fragment still had when it reached the block: a burst that goes off
     * close carries more than one that spent itself on the way there.
     */
    public static float burstFragmentDamage(double fragmentMass, double speed, float baseDamage) {
        double speedScale = Mth.clamp(speed / 12.0, 0.45, 2.0);
        return (float) (baseDamage * speedScale + fragmentMass * 0.5);
    }

    /**
     * Whether the pack allows blocks to be broken at all.
     *
     * <p>The setting sits on the pack's own config, which is built on a
     * library that is not part of this build's classpath, so it is read once
     * through reflection and remembered. Anything that cannot be resolved
     * counts as "allowed", which is what a pack with default settings
     * answers.</p>
     */
    private static boolean griefingAllowed() {
        Boolean cached = griefingAllowed;
        if (cached != null) {
            return cached;
        }
        boolean allowed = true;
        try {
            Class<?> configs = Class.forName("rbasamoyai.createbigcannons.config.CBCConfigs");
            Object server = configs.getMethod("server").invoke(null);
            Object munitions = server.getClass().getField("munitions").get(server);
            Object restriction = munitions.getClass().getField("damageRestriction").get(munitions);
            Object value = restriction.getClass().getMethod("get").invoke(restriction);
            allowed = !(value instanceof Enum<?> state && "NO_DAMAGE".equals(state.name()));
        } catch (Throwable ignored) {
            allowed = true;
        }
        griefingAllowed = allowed;
        return allowed;
    }
}