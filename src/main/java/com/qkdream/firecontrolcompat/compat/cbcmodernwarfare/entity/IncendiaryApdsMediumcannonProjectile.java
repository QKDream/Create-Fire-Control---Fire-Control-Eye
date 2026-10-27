package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apds.APDSMediumcannonProjectile;

/**
 * The armour piercing dart thrown out by the sensor fuzed shell's submunitions:
 * a plain APDS dart with an incendiary tip. The first thing the dart touches
 * takes a burst of flame - the same blast the incendiary tips of CBC Modern
 * Warfare's autocannon rounds make, which breaks nothing but burns - while the
 * dart keeps punching on through the armour like any other APDS round, so the
 * shot that carries the flame into the hull still lands.
 */
public class IncendiaryApdsMediumcannonProjectile extends APDSMediumcannonProjectile {

    /** Strength of the flame burst, the same as CBC Modern Warfare's incendiary tip. */
    private static final float BURST_STRENGTH = 1.0F;

    private boolean firecontrolcompat$burst;

    public IncendiaryApdsMediumcannonProjectile(EntityType<? extends IncendiaryApdsMediumcannonProjectile> type,
            Level level) {
        super(type, level);
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(CBCModernWarfareItem.APDS_MEDIUMCANNON_ROUND.get());
    }

    @Override
    protected boolean onImpact(HitResult hit, AbstractCannonProjectile.ImpactResult result, ProjectileContext context) {
        if (hit != null) {
            this.firecontrolcompat$burn(hit.getLocation());
        }
        return super.onImpact(hit, result, context);
    }

    @Override
    protected boolean onHitEntity(Entity target, ProjectileContext context) {
        boolean remove = super.onHitEntity(target, context);
        if (!this.level().isClientSide() && target != null) {
            target.lavaHurt();
        }
        return remove;
    }

    /**
     * One burst of flame on the first thing the dart runs into. Anything after
     * that is the plain armour piercing round carrying on into the hull.
     */
    private void firecontrolcompat$burn(Vec3 position) {
        if (this.firecontrolcompat$burst || this.level().isClientSide() || position == null) {
            return;
        }
        this.firecontrolcompat$burst = true;
        this.level().explode(this, this.indirectArtilleryFire(false), null,
                position.x, position.y, position.z, BURST_STRENGTH, true,
                Level.ExplosionInteraction.NONE);
    }
}