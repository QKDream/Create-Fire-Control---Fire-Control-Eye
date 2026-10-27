package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.AtgmMediumcannonProjectile;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMunitionPropertiesHandlers;
import riftyboi.cbcmodernwarfare.munitions.ItemStackDataUtil;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonRoundItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.MediumcannonProjectilePropertiesComponent;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.heap.HEAPMediumcannonProjectileProperties;

/** The gun launched missile, loaded into a medium cannon like any other shell. */
public class AtgmMediumcannonRoundItem extends MediumcannonRoundItem {

    public AtgmMediumcannonRoundItem(Properties properties) {
        super(properties);
    }

    @Override
    public AbstractMediumcannonProjectile getMediumcannonProjectile(ItemStack stack, Level level) {
        AtgmMediumcannonProjectile projectile = CbcModernWarfareCompat.ATGM_MEDIUMSHELL.get().create(level);
        if (projectile != null) {
            // The gun's own firing path: the round was rammed into a barrel, so its shaped charge
            // throws a metal jet where it goes off.
            projectile.markGunLaunched();
            if (ItemStackDataUtil.isTracer(stack)) {
                projectile.setTracer(true);
            }
        }
        return projectile;
    }

    @Override
    public MediumcannonProjectilePropertiesComponent getMediumcannonProperties(ItemStack itemStack) {
        return ((HEAPMediumcannonProjectileProperties) CBCModernWarfareMunitionPropertiesHandlers.HEAP_MEDIUMCANNON
                .getPropertiesOf(this.getEntityType(itemStack))).mediumcannonProperties();
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return CbcModernWarfareCompat.ATGM_MEDIUMSHELL.get();
    }

    @Override
    public ItemStack getCartridgeType() {
        return new ItemStack(CbcModernWarfareCompat.ATGM_MEDIUMCANNON_CARTRIDGE.get());
    }
}