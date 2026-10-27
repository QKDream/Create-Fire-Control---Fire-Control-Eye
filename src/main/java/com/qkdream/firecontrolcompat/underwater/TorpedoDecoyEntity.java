package com.qkdream.firecontrolcompat.underwater;

import com.qkdream.firecontrolcompat.SonicPulse;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The decoy in the air, and then in the water: a grenade body with a noise
 * maker inside it and no charge at all.
 *
 * <p>It flies the lob the launcher gives it, and once it has run into anything
 * it stops dead where it is and stays there - nothing carries it on and nothing
 * sets it off. It goes quiet for the first second, and from then on it sings
 * once a second for a minute: that is the noise a homing torpedo turns onto and
 * the noise the sculk network answers.</p>
 */
public class TorpedoDecoyEntity extends ThrowableItemProjectile {

    /** Ticks between the throw and the moment the noise maker starts: one second. */
    private static final int WAKE_DELAY = 20;
    /** Ticks between two shouts: one a second, the rate a gun going off is filed at. */
    private static final int PULSE_PERIOD = 20;
    /** How long it sings before the maker runs down: one minute. */
    private static final int LIFETIME = 1200;

    /** Set once it has run into something: from then on it stays where it stopped. */
    private boolean firecontrolcompat$settled;

    public TorpedoDecoyEntity(EntityType<? extends TorpedoDecoyEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item getDefaultItem() {
        return TorpedoDecoy.TORPEDO_DECOY_ITEM.get();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.firecontrolcompat$settled) {
            this.setDeltaMovement(Vec3.ZERO);
        }
        if (this.level().isClientSide) {
            return;
        }
        if (this.tickCount >= LIFETIME) {
            this.discard();
            return;
        }
        if (this.tickCount < WAKE_DELAY || this.tickCount % PULSE_PERIOD != 0) {
            return;
        }
        SonicPulse.decoy(this.level(), this.position(), this.getUUID());
    }

    /**
     * It carries no charge, so it is not set off by what it reaches: it drops
     * onto it and floats there, and only a block stops it. Brushing a creature
     * on the way does nothing at all.
     */
    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (result.getType() != HitResult.Type.BLOCK) {
            return;
        }
        this.firecontrolcompat$settled = true;
        this.setDeltaMovement(Vec3.ZERO);
        this.hasImpulse = true;
    }
}
