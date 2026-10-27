package com.qkdream.firecontrolcompat.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The semi-active air-to-air missile: a Sidewinder-family round that is illuminated, not passive.
 *
 * <p>The seeker only has something to follow while the launcher keeps the target lit. That is the
 * whole difference from the infrared rounds beside it, and it is expressed here as one rule: the
 * round reads an illumination countdown, and when it has run out the seeker returns no target at
 * all and the round flies on as a free rocket. Whoever launched the round refreshes that countdown
 * while it still holds the lock, so the countdown is what "the lock was kept" means - a launcher
 * that loses the target, turns away, or stops designating simply stops refreshing, and the round
 * stops homing two seconds later. Flight, fuze and damage are the infrared round's own behaviour.</p>
 */
public class SemiActiveMissileEntity extends InfraredMissileEntity {

    /** How long one refresh keeps the seeker lit, in ticks. */
    public static final int ILLUMINATION_TICKS = 40;

    /** The key the launching side writes to keep the seeker lit. */
    public static final String ILLUMINATION_KEY = "semiActiveLit";

    public SemiActiveMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item missileItem() {
        // TEMP-DISABLED(semi-active): the item is no longer registered, so a round that somehow
        // still exists answers with air instead of asking the registry for a holder that is gone.
        // return com.qkdream.firecontrolcompat.BeamMissileRegistry.SEMI_ACTIVE_MISSILE.get();
        return net.minecraft.world.item.Items.AIR;
    }

    /** Semi-active: no illumination, no target. */
    @Override
    protected Vec3 trackedTargetPosition() {
        if (this.getPersistentData().getInt(ILLUMINATION_KEY) <= 0) {
            return null;
        }
        return super.trackedTargetPosition();
    }

    @Override
    public void tick() {
        CompoundTag data = this.getPersistentData();
        int lit = data.getInt(ILLUMINATION_KEY);
        if (lit > 0) {
            data.putInt(ILLUMINATION_KEY, lit - 1);
        }
        super.tick();
    }

    /** Keeps the seeker lit for another moment; called while the launcher still holds the target. */
    public void refreshIllumination() {
        this.getPersistentData().putInt(ILLUMINATION_KEY, ILLUMINATION_TICKS);
    }

    /** Whether the round is still being guided at all. */
    public boolean illuminated() {
        return this.getPersistentData().getInt(ILLUMINATION_KEY) > 0;
    }
}
