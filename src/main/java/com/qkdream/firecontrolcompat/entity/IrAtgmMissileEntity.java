package com.qkdream.firecontrolcompat.entity;

import com.qkdream.firecontrolcompat.AtgmBoom;
import com.qkdream.firecontrolcompat.IrAtgm;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The infrared anti-tank round: an infrared airframe flown over the top, with a shaped charge.
 *
 * <p>Everything that makes it different from the infrared round is in {@link IrAtgm} - the lock it
 * was released against, the four-leg climb / cruise / dive / terminal profile, the speed of each
 * leg and the HEAT warhead. This class is the join points where that behaviour has to be injected:
 * the aim point and the speed each tick, how long the round lives, and what happens at detonation.
 * Keeping it a separate entity type (rather than a flag on the infrared round) means the two rounds
 * can be loaded, tracked, rendered and logged apart, which is what a crew arming a launcher
 * actually needs.</p>
 */
public class IrAtgmMissileEntity extends InfraredMissileEntity {

    /**
     * How long this round may fly, in ticks (60 s).
     *
     * <p>It has to be longer than the infrared round's twenty seconds (400): the legs are flown at
     * {@code IrAtgm.CLIMB_SPEED 1.4 / CRUISE_SPEED 2.0 / DIVE_SPEED 2.6} blocks per tick, and a shot
     * at the outer edge of the round's thousand block lock range plus the over-the-top detour is
     * comfortably inside 60 s (2.0 x 1200 = 2400 blocks of cruise alone). The figure is therefore
     * <b>kept</b> at 1200 rather than shortened when the speeds went up: a pursuit that is cut short
     * is exactly what "the attack never arrived" looks like. It is also what keeps a round that was
     * handed to fire control's mathematical mode (whose engine counter is parked) from reading itself
     * as expired.</p>
     */
    private static final int IR_ATGM_LIFETIME_TICKS = 1200;

    public IrAtgmMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
    }

    /**
     * The top attack, on top of the seeker's own answer.
     *
     * <p>The round is released against a ground point, which a heat seeker cannot see: while it is
     * climbing and running in, the lock is the whole aim. Once it dives, {@link IrAtgm#topAttackPoint}
     * blends the lock with whatever the seeker has found, so a target that moved during the climb is
     * still met at the bottom of the dive.</p>
     *
     * <p>The speed is applied <b>after</b> the aim, because the aim is what advances the flight
     * phase: the speed of a tick has to be the speed of the leg that tick is on.</p>
     */
    @Override
    protected Vec3 guidancePoint() {
        Vec3 base;
        try {
            base = super.guidancePoint();
        } catch (Throwable failure) {
            base = null;
        }
        try {
            Vec3 aim = IrAtgm.topAttackPoint(this, base);
            IrAtgm.applySpeed(this);
            return aim;
        } catch (Throwable failure) {
            return base;
        }
    }

    /** The leg's speed, which is what the base class's acceleration ramp targets. */
    @Override
    protected double guidanceSpeed() {
        try {
            return IrAtgm.speedFor(this);
        } catch (Throwable failure) {
            return IrAtgm.CRUISE_SPEED;
        }
    }

    @Override
    protected int maxLifetimeTicks() {
        return IR_ATGM_LIFETIME_TICKS;
    }

    @Override
    protected int maxAgeTicks() {
        return IR_ATGM_LIFETIME_TICKS;
    }

    /**
     * The warhead: 面包学's anti-tank blast, then this mod's shaped-charge jet.
     *
     * <p>Order matters. The blast is what removes the round and plays the explosion; the jet is what
     * goes through the roof. Spawning the jet first would let it be born inside the round's own
     * explosion and be deflected by it.</p>
     *
     * <p>The blast itself is {@link com.qkdream.firecontrolcompat.AtgmBoom}: the reference round goes
     * off through {@code Antitankmissile1explodeProcedure} (radius 2.0, the twin {@code
     * missile_explode}/{@code explode_far_sound3} sounds, the {@code explosionsmall} hemisphere and two
     * {@code shrapnel_2} fragments), not through the 12 block blast this mod's other missiles use. See
     * {@code ir-atgm boom: style=.. } in the log for which of the two ran.</p>
     */
    @Override
    public void detonate(Vec3 position, String cause) {
        if (this.level().isClientSide()) {
            return;
        }
        // One warhead per round. The interception sweep, the cook-off pass and this round's own
        // fuze can all reach this method for the same kill; only the first call gets to spend the
        // shaped charge (see {@code BeamRidingMissileEntity#firecontrolcompat$beginDetonation}).
        if (!this.firecontrolcompat$beginDetonation()) {
            return;
        }
        Vec3 at = position;
        boolean heat = IrAtgm.isTopAttack(this);
        AtgmBoom.detonate(this, at, cause, heat);
        if (heat && at != null) {
            IrAtgm.heatWarhead(this, at);
        }
    }
}
