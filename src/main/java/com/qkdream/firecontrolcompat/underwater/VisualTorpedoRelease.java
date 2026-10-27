package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.UUID;
import net.mcreator.myfirstmod.entity.HighexplosivevisualguidedtorpedotansheEntity;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Lets 面包学's own visually guided round out of the carrier a CBCMS tube threw.
 *
 * <p>A CBCMS tube knows one kind of round - its own torpedo blocks - so the
 * round that leaves it is one of ours for exactly one tick: on that tick it
 * hands over to 面包学's own visual round at the same place and on the same
 * bearing, and is gone. The round that takes over is the mod's own, with the
 * mod's own swim and the mod's own hit; the numbers it is handed here - damage,
 * knockback, launch speed - are the ones 面包学's own tube hands it.</p>
 */
public final class VisualTorpedoRelease {

    /** Damage 面包学's own tube gives the round. */
    private static final double BASE_DAMAGE = 20.0D;
    /** Knockback 面包学's own tube gives the round. */
    private static final int KNOCKBACK = 5;
    /** The speed 面包学's own tube throws the round out at, in blocks a tick. */
    private static final float LAUNCH_SPEED = 2.0F;
    /** The bearing the round is given when the carrier is standing still. */
    private static final Vec3 FALLBACK_HEADING = new Vec3(0.0D, 0.0D, 1.0D);

    private VisualTorpedoRelease() {
    }

    public static void release(WireGuidedTorpedoProjectile carrier) {
        Vec3 from = carrier.position();
        Vec3 movement = carrier.getDeltaMovement();
        String gunner = carrier.visualOwner();
        Level level = carrier.level();
        carrier.discard();
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        HighexplosivevisualguidedtorpedotansheEntity round =
                MianbaosModernwarfareModEntities.HIGHEXPLOSIVEVISUALGUIDEDTORPEDOTANSHE.get().create(server);
        if (round == null) {
            return;
        }
        Vec3 heading = movement.lengthSqr() < 1.0E-8D ? FALLBACK_HEADING : movement.normalize();
        round.setPos(from.x, from.y, from.z);
        round.setOwner(aimer(server, gunner));
        round.setBaseDamage(BASE_DAMAGE);
        round.setKnockback(KNOCKBACK);
        round.setSilent(true);
        round.shoot(heading.x, heading.y, heading.z, LAUNCH_SPEED, 0.0F);
        if (server.addFreshEntity(round)) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] 面包学's visual round left the tube, "
                    + "swimming the sight line of {}", gunner == null || gunner.isEmpty() ? "nobody" : gunner);
        }
    }

    /** The eyes the round swims towards: whoever fired the tube, or whoever loaded it. */
    private static Entity aimer(ServerLevel level, String gunner) {
        if (gunner == null || gunner.isEmpty()) {
            return null;
        }
        try {
            return level.getEntity(UUID.fromString(gunner));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
