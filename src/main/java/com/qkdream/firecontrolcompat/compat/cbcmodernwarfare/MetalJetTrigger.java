package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.MetalJetProjectile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one place a shaped charge charge throws its metal jet.
 *
 * <p>Every round with a shaped charge warhead goes off on its own terms - the
 * medium cannon HEAT round on its fuze or on contact, the big cannon HEAT shell
 * on its fuze, the gun launched missile of this pack on contact - and each of
 * them detonates from its own method. Rather than repeat the spawn in three
 * places, all three call this one, and the round itself carries the note that
 * its jet has already gone: a round that goes off twice (a fuze and a contact,
 * or a subclass that calls its parent's detonation) still throws one jet.</p>
 *
 * <p>The note is the projectile's own persistent tag, which is server side data
 * on an entity that lives for a fraction of a second. It is never synced and
 * never saved, and it dies with the round.</p>
 */
public final class MetalJetTrigger {

    /** Tag marking a round whose jet has already been thrown. */
    private static final String THROWN_KEY = "firecontrolcompatMetalJet";

    private MetalJetTrigger() {
    }

    /**
     * Throws this round's jet, once, along its own nose.
     *
     * @param round  the round that is going off
     * @param level  level it went off in
     * @param at     point of detonation, or null for the round's own position
     */
    public static void throwJet(Entity round, Level level, Vec3 at) {
        if (round == null || level == null || level.isClientSide()) {
            return;
        }
        try {
            if (round.getPersistentData().getBoolean(THROWN_KEY)) {
                return;
            }
            round.getPersistentData().putBoolean(THROWN_KEY, true);
        } catch (Throwable ignored) {
            // Without the tag the jet is still thrown; only the once-only guard is lost.
        }
        Vec3 from = at == null ? round.position() : at;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] heat round detonated: throwing jet round={} at={} velocity={}",
                round.getType().toShortString(), from, round.getDeltaMovement());
        MetalJetProjectile.spawnFrom(level, from, round.getDeltaMovement(), round);
    }
}
