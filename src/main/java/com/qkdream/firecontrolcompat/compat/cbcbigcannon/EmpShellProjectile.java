package com.qkdream.firecontrolcompat.compat.cbcbigcannon;

import com.qkdream.firecontrolcompat.EmpEffect;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Explosion.BlockInteraction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.ShellExplosion;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonCommonShellProperties;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonFuzePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonProjectilePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.EntityDamagePropertiesComponent;

/**
 * The flying EMP shell.
 *
 * <p>It bursts like any other shell - same fuze handling, same blast wave, same
 * damage to whatever stands in the open - with one difference: the explosion is
 * carried out as a {@link BlockInteraction#KEEP} blast with terrain damage
 * switched off, so it leaves the ground (and cracked stone, and every other
 * impact transform CBC would normally apply) exactly as it was. What it does
 * instead is {@link EmpEffect#burst hand the detonation to the blackout}, which
 * switches off fire control radars and 面包学's active protection inside its
 * reach for ten seconds.</p>
 */
public class EmpShellProjectile extends FuzedBigCannonProjectile {

    public EmpShellProjectile(EntityType<? extends EmpShellProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    protected void detonate(Position position) {
        BigCannonCommonShellProperties properties = this.getAllProperties();
        Level level = this.level();
        ShellExplosion explosion = new ShellExplosion(
                level,
                this,
                this.indirectArtilleryFire(false),
                position.x(),
                position.y(),
                position.z(),
                properties.explosion().blockDamagePower(),
                properties.explosion().entityDamagePower(),
                false,
                BlockInteraction.KEEP);
        explosion.setCanDamageTerrain(false);
        explosion.explode();
        explosion.clearToBlow();
        explosion.finalizeExplosion(level.isClientSide);
        if (level instanceof ServerLevel server) {
            for (ServerPlayer player : server.players()) {
                explosion.sendExplosionToClient(player);
            }
        }
        EmpEffect.burst(level, new Vec3(position.x(), position.y(), position.z()));
    }

    @Override
    public BlockState getRenderedBlockState() {
        return CbcBigCannonCompat.EMP_SHELL.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH);
    }

    @Override
    protected BigCannonFuzePropertiesComponent getFuzeProperties() {
        return this.getAllProperties().fuze();
    }

    @Override
    protected BigCannonProjectilePropertiesComponent getBigCannonProjectileProperties() {
        return this.getAllProperties().bigCannonProperties();
    }

    @Override
    public EntityDamagePropertiesComponent getDamageProperties() {
        return this.getAllProperties().damage();
    }

    @Override
    protected BallisticPropertiesComponent getBallisticProperties() {
        return this.getAllProperties().ballistics();
    }

    protected BigCannonCommonShellProperties getAllProperties() {
        return (BigCannonCommonShellProperties) CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                .getPropertiesOf(this);
    }
}