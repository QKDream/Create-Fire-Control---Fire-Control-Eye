package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Cook-off adaptation for the Fire Control Eye's own missiles: the beam-rider,
 * infrared (ground and air-launched), loitering munition and heavy
 * air-defense missiles detonate when a CBC-family projectile hits them
 * mid-flight, when a shrapnel fragment hits them, or when they are caught in
 * an explosion blast.
 *
 * <p>The missiles move up to 34 blocks/tick and have no entity-vs-entity
 * collision with CBC shells, so hits are detected with per-tick movement
 * segment sweeps against each missile's hitbox, and blasts use a box larger
 * than the nominal explosion radius so fast movers cannot slip through.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class CookOffHandler {

    private static final Map<ServerLevel, Set<AbstractCannonProjectile>> PROJECTILES = new HashMap<>();
    private static final Map<AbstractCannonProjectile, Vec3> PROJECTILE_LAST_POS = new HashMap<>();
    private static final Map<ServerLevel, Set<Entity>> FRAGMENTS = new HashMap<>();
    private static final Map<Entity, Vec3> FRAGMENT_LAST_POS = new HashMap<>();

    /** Missiles currently exploding: their own blast re-enters the sweep synchronously. */
    private static final Set<Entity> DETONATING = new HashSet<>();

    private CookOffHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof AbstractCannonProjectile projectile) {
            PROJECTILES.computeIfAbsent(serverLevel, k -> new HashSet<>()).add(projectile);
            PROJECTILE_LAST_POS.put(projectile, projectile.position());
        } else if (isFragmentEntity(entity)) {
            FRAGMENTS.computeIfAbsent(serverLevel, k -> new HashSet<>()).add(entity);
            FRAGMENT_LAST_POS.put(entity, entity.position());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof AbstractCannonProjectile projectile) {
            PROJECTILE_LAST_POS.remove(projectile);
            Set<AbstractCannonProjectile> set = PROJECTILES.get(event.getLevel());
            if (set != null) {
                set.remove(projectile);
            }
        } else if (isFragmentEntity(entity)) {
            FRAGMENT_LAST_POS.remove(entity);
            Set<Entity> set = FRAGMENTS.get(event.getLevel());
            if (set != null) {
                set.remove(entity);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        sweepProjectiles(event);
        sweepFragments(event);
    }

    /** Direct hits: any CBC-family shell whose movement segment crosses a missile cooks it off. */
    private static void sweepProjectiles(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Set<AbstractCannonProjectile> set = PROJECTILES.get(level);
            if (set == null || set.isEmpty()) {
                continue;
            }
            Iterator<AbstractCannonProjectile> iterator = set.iterator();
            while (iterator.hasNext()) {
                AbstractCannonProjectile projectile = iterator.next();
                if (projectile.isRemoved()) {
                    iterator.remove();
                    PROJECTILE_LAST_POS.remove(projectile);
                    continue;
                }
                Vec3 current = projectile.position();
                Vec3 previous = PROJECTILE_LAST_POS.put(projectile, current);
                if (previous == null) {
                    continue;
                }
                double margin = projectile.getBbWidth() * 0.5 + 0.75;
                AABB area = new AABB(previous, current).inflate(margin);
                for (Entity entity : level.getEntities(null, area)) {
                    if (!(entity instanceof BeamRidingMissileEntity missile)
                            || missile.isRemoved() || !missile.isAlive() || DETONATING.contains(missile)) {
                        continue;
                    }
                    if (segmentIntersectsBox(previous, current,
                            missile.getBoundingBox().inflate(projectile.getBbWidth() * 0.5 + 0.25))) {
                        cookOff(missile, "projectile");
                    }
                }
            }
        }
    }

    /** Shrapnel fragments only cook off missiles in flight. */
    private static void sweepFragments(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Set<Entity> set = FRAGMENTS.get(level);
            if (set == null || set.isEmpty()) {
                continue;
            }
            Iterator<Entity> iterator = set.iterator();
            while (iterator.hasNext()) {
                Entity fragment = iterator.next();
                if (fragment.isRemoved()) {
                    iterator.remove();
                    FRAGMENT_LAST_POS.remove(fragment);
                    continue;
                }
                Vec3 current = fragment.position();
                Vec3 previous = FRAGMENT_LAST_POS.put(fragment, current);
                if (previous == null) {
                    continue;
                }
                AABB area = new AABB(previous, current).inflate(1.0);
                for (Entity entity : level.getEntities(null, area)) {
                    if (!(entity instanceof BeamRidingMissileEntity missile)
                            || missile.isRemoved() || !missile.isAlive() || DETONATING.contains(missile)) {
                        continue;
                    }
                    if (segmentIntersectsBox(previous, current, missile.getBoundingBox().inflate(0.5))) {
                        cookOff(missile, "fragment");
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (event.getLevel() instanceof ServerLevel level) {
            sweepBlast(level, event.getExplosion(), "blast");
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            sweepBlast(level, event.getExplosion(), "blast");
        }
    }

    private static void sweepBlast(ServerLevel level, Explosion explosion, String cause) {
        double radius = explosion.radius();
        if (!(radius > 0.0) || radius > 48.0) {
            return;
        }
        double half = Math.min(24.0, Math.max(radius * 2.0, 6.0));
        Vec3 center = explosion.center();
        AABB area = AABB.ofSize(center, half * 2.0, half * 2.0, half * 2.0);
        for (Entity entity : level.getEntities(null, area)) {
            if (!(entity instanceof BeamRidingMissileEntity missile)
                    || missile.isRemoved() || !missile.isAlive() || DETONATING.contains(missile)) {
                continue;
            }
            cookOff(missile, cause);
        }
    }

    private static void cookOff(BeamRidingMissileEntity missile, String cause) {
        if (missile.level().isClientSide() || missile.isRemoved() || !DETONATING.add(missile)) {
            return;
        }
        try {
            FireControlCompat.LOGGER.info("[firecontrolcompat] cook off: {} at {} cause={}",
                    missile.getClass().getSimpleName(), missile.position(), cause);
            missile.detonate(missile.position(), "cook-off-" + cause);
        } finally {
            DETONATING.remove(missile);
        }
    }

    /** CBC shrapnel burst entities (Ritchie's Projectile Library). */
    private static boolean isFragmentEntity(Entity entity) {
        if (entity == null) {
            return false;
        }
        for (Class<?> cls = entity.getClass(); cls != null; cls = cls.getSuperclass()) {
            if ("rbasamoyai.ritchiesprojectilelib.projectile_burst.ProjectileBurst".equals(cls.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentIntersectsBox(Vec3 a, Vec3 b, AABB box) {
        double tMin = 0.0;
        double tMax = 1.0;
        double[] result = slab(tMin, tMax, a.x, b.x - a.x, box.minX, box.maxX);
        if (result == null) {
            return false;
        }
        tMin = result[0];
        tMax = result[1];
        result = slab(tMin, tMax, a.y, b.y - a.y, box.minY, box.maxY);
        if (result == null) {
            return false;
        }
        tMin = result[0];
        tMax = result[1];
        result = slab(tMin, tMax, a.z, b.z - a.z, box.minZ, box.maxZ);
        if (result == null) {
            return false;
        }
        return tMax >= 0.0 && tMin <= 1.0;
    }

    private static double[] slab(double tMin, double tMax, double origin, double dir, double slabMin, double slabMax) {
        if (Math.abs(dir) < 1.0E-7) {
            return origin >= slabMin && origin <= slabMax ? new double[]{tMin, tMax} : null;
        }
        double t1 = (slabMin - origin) / dir;
        double t2 = (slabMax - origin) / dir;
        if (t1 > t2) {
            double tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        tMin = Math.max(tMin, t1);
        tMax = Math.min(tMax, t2);
        if (tMin > tMax) {
            return null;
        }
        return new double[]{tMin, tMax};
    }
}
