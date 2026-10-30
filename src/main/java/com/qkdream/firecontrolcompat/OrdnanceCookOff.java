package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.entity.RemoteVisualState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Cook-off for the mod's flying ordnance.
 *
 * <p>A bomb or a missile sitting in the air is ammunition, and ammunition near a blast goes
 * off with it. The pack's rounds already know how to detonate when they are struck - their
 * own hit procedures run the burst - so a nearby explosion is turned into a hit on every
 * flying round of 面包学's, which sets them off and lets their own blasts carry the chain on.
 *
 * <p>Only rounds still in the air are struck: one already lying on the ground has had its
 * burst, and striking it again would double it. The pack's own aircraft rounds, the gun
 * launched missiles, the cruise rounds and this mod's bombs all count, because they are all
 * 面包学's flying ammunition.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class OrdnanceCookOff {

    /** How far a blast reaches into the ammunition around it. */
    private static final double COOK_OFF_RANGE = 12.0D;

    /** Enough to set a round off through its own hit handling. */
    private static final float COOK_OFF_DAMAGE = 2000.0F;

    private OrdnanceCookOff() {
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        var centre = event.getExplosion().center();
        if (centre == null) {
            return;
        }
        double range = COOK_OFF_RANGE * COOK_OFF_RANGE;
        for (Entity entity : level.getEntities((Entity) null,
                new net.minecraft.world.phys.AABB(
                        centre.x - COOK_OFF_RANGE, centre.y - COOK_OFF_RANGE, centre.z - COOK_OFF_RANGE,
                        centre.x + COOK_OFF_RANGE, centre.y + COOK_OFF_RANGE, centre.z + COOK_OFF_RANGE),
                candidate -> isFlyingPackOrdnance(candidate, centre, range))) {
            try {
                // A hit is the round's own fuse: 面包学's rounds burst on being struck, and
                // that burst is what passes the cook-off along to the next one.
                entity.hurt(level.damageSources().explosion(null, null), COOK_OFF_DAMAGE);
                FireControlCompat.LOGGER.info("[firecontrolcompat] cook-off type={} pos={}",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), entity.position());
            } catch (RuntimeException | LinkageError failure) {
                FireControlCompat.LOGGER.info("[firecontrolcompat] cook-off failed type={} error={}",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), failure.toString());
            }
        }
    }

    /** Whether the entity is 面包学's flying ammunition close enough to that blast. */
    private static boolean isFlyingPackOrdnance(Entity entity, net.minecraft.world.phys.Vec3 centre, double range) {
        if (entity == null || entity.isRemoved() || entity.isSpectator()) {
            return false;
        }
        if (entity.position().distanceToSqr(centre) > range) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null || !"mianbaos_modernwarfare".equals(id.getNamespace())) {
            return false;
        }
        // Rounds still travelling: a landed one has already burst, and the mod's own
        // long-range proxy is a picture of a round, not a round. The flag is read from the common
        // `entity.RemoteVisualState` (it used to come from client/MissileVisualSupport, which made
        // this server-side explosion listener depend on a client class).
        return !entity.onGround() && !RemoteVisualState.isRemoteVisual(entity);
    }
}
