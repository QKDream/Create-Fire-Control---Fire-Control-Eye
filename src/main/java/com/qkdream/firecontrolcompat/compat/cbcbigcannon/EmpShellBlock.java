package com.qkdream.firecontrolcompat.compat.cbcbigcannon;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.SimpleShellBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonCommonShellProperties;

/**
 * The EMP shell block, built on CBC's own fuzed projectile block so it loads,
 * fuzes and renders exactly like a vanilla big cannon shell.
 */
public class EmpShellBlock extends SimpleShellBlock<EmpShellProjectile> {

    private static final MapCodec<ProjectileBlock> CODEC = simpleCodec(EmpShellBlock::new);

    public EmpShellBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public boolean isBaseFuze() {
        return ((BigCannonCommonShellProperties) CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                .getPropertiesOf(this.getAssociatedEntityType())).fuze().baseFuze();
    }

    @Override
    public EntityType<? extends EmpShellProjectile> getAssociatedEntityType() {
        return CbcBigCannonCompat.EMP_SHELL_ENTITY.get();
    }
}