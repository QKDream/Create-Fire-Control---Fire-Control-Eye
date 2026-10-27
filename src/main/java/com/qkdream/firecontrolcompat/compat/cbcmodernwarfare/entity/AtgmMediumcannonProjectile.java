package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CannonMissileGuidance;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;

import net.minecraft.core.Position;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.heap.HEAPMediumcannonProjectile;

/**
 * Gun launched missile: a medium cannon round that keeps flying after leaving
 * the barrel and rides the fire control computer aim beam like an anti tank
 * missile, leaving a smoke trail behind it. It always goes off on impact, no
 * fuze needed.
 */
public class AtgmMediumcannonProjectile extends HEAPMediumcannonProjectile {

    /** How long the Missile stays on the beam before it goes ballistic again. */
    public static final int MAX_GUIDED_TICKS = 400;
    private static final double TRAIL_BACKSTEP = 0.35;

    private StabilizerControllerBlockEntity firecontrolcompat$controller;
    private int firecontrolcompat$age;
    private boolean firecontrolcompat$spawnTrail = true;

    /**
     * Set by the gun that loaded this round.
     *
     * <p>Reported nowhere now but kept as the record of the firing path: the jet
     * itself hangs off the detonation, which only ever happens to a round that
     * came out of a barrel.</p>
     */
    private boolean firecontrolcompat$gunLaunched;

    /** Marks this round as fired from a cannon; called on the firing path. */
    public void markGunLaunched() {
        this.firecontrolcompat$gunLaunched = true;
    }

    public AtgmMediumcannonProjectile(EntityType<? extends AtgmMediumcannonProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            this.firecontrolcompat$updateGuidance();
        }
        super.tick();
        if (!this.level().isClientSide) {
            this.firecontrolcompat$updateTrail();
        }
    }

    private void firecontrolcompat$updateGuidance() {
        if (this.firecontrolcompat$age > MAX_GUIDED_TICKS) {
            return;
        }
        this.firecontrolcompat$age++;
        Vec3 worldPosition = CannonMissileGuidance.worldPosition(this.level(), this.position());
        if (SubLevelPositions.inPlotSpace(this.level(), this.position())) {
            return;
        }
        if (this.firecontrolcompat$controller == null || this.firecontrolcompat$controller.isRemoved()) {
            this.firecontrolcompat$controller = CannonMissileGuidance.findController(this.level(), worldPosition);
        }
        Vec3 guided = CannonMissileGuidance.guidedVelocity(this, this.firecontrolcompat$controller,
                this.firecontrolcompat$age, worldPosition);
        if (guided == null) {
            return;
        }
        this.nextVelocity = null;
        this.setDeltaMovement(guided);
        this.hasImpulse = true;
        this.setOrientation(guided.normalize());
    }

    private void firecontrolcompat$updateTrail() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 tail = this.position();
        Vec3 velocity = this.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-6) {
            tail = tail.subtract(velocity.normalize().scale(TRAIL_BACKSTEP));
        }
        server.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, tail.x, tail.y, tail.z, 1,
                0.02, 0.02, 0.02, 0.0);
        server.sendParticles(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 1, 0.01, 0.01, 0.01, 0.0);
        if (this.tickCount % 2 == 0) {
            server.sendParticles(ParticleTypes.FLAME, tail.x, tail.y, tail.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(CbcModernWarfareCompat.ATGM_MEDIUMCANNON_ROUND.get());
    }

    /**
     * The missile carries no fuze, so unlike the stock HEAP shell it also goes
     * off when nothing has been fitted to it.
     */
    @Override
    protected boolean onImpact(HitResult hitResult, ImpactResult impactResult, ProjectileContext projectileContext) {
        if (super.onImpact(hitResult, impactResult, projectileContext)) {
            return true;
        }
        if (!this.level().isClientSide) {
            this.detonate(hitResult.getLocation());
        }
        return true;
    }

    /**
     * The missile's own blast, and the molten jet of its shaped charge.
     *
     * <p>This round extends CBC Modern Warfare's HEAT round, whose own detonation
     * throws a jet as well, so the once-only guard inside {@code MetalJetTrigger}
     * is what keeps one round from throwing two. The round never carries a fuze,
     * so its detonation is the impact - which is what the jet hangs off.</p>
     */
    @Override
    protected void detonate(Position position) {
        com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.MetalJetTrigger.throwJet(this, this.level(),
                position == null ? null : new Vec3(position.x(), position.y(), position.z()));
        super.detonate(position);
    }
}