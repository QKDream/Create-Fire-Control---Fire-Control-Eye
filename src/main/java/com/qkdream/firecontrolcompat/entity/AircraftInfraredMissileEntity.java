package com.qkdream.firecontrolcompat.entity;

import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Aircraft infrared-homing proximity missile. Same seeker and fuze as the
 * ground infrared missile; exclusively carried by the aircraft weapon rack.
 */
public class AircraftInfraredMissileEntity extends InfraredMissileEntity {

    public AircraftInfraredMissileEntity(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item missileItem() {
        return BeamMissileRegistry.AIRCRAFT_INFRARED_MISSILE.get();
    }
}