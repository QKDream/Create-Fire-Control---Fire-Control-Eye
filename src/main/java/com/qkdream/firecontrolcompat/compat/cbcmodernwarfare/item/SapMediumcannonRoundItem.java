package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMunitionPropertiesHandlers;
import riftyboi.cbcmodernwarfare.munitions.ItemStackDataUtil;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.FuzedMediumItemMunition;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonRoundItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.aphe.APHEMediumcannonProjectileProperties;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.MediumcannonProjectilePropertiesComponent;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.SapMediumcannonProjectile;

public class SapMediumcannonRoundItem extends MediumcannonRoundItem implements FuzedMediumItemMunition {

    public SapMediumcannonRoundItem(Properties properties) {
        super(properties);
    }

    @Override
    public AbstractMediumcannonProjectile getMediumcannonProjectile(ItemStack stack, Level level) {
        SapMediumcannonProjectile projectile = CbcModernWarfareCompat.SAP_MEDIUMSHELL.get().create(level);
        if (projectile != null) {
            // A semi-armour-piercing shell makes no metal jet: that belongs to the HEAT rounds.
            ItemStack fuze = ItemStackDataUtil.getFuze(stack);
            if (!fuze.isEmpty()) {
                projectile.setFuze(fuze);
            }
            if (ItemStackDataUtil.isTracer(stack)) {
                projectile.setTracer(true);
            }
        }
        return projectile;
    }

    @Override
    public MediumcannonProjectilePropertiesComponent getMediumcannonProperties(ItemStack itemStack) {
        return ((APHEMediumcannonProjectileProperties) CBCModernWarfareMunitionPropertiesHandlers.APHE_MEDIUMCANNON
                .getPropertiesOf(this.getEntityType(itemStack))).mediumcannonProperties();
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return CbcModernWarfareCompat.SAP_MEDIUMSHELL.get();
    }

    @Override
    public ItemStack getCartridgeType() {
        return new ItemStack(CbcModernWarfareCompat.SAP_MEDIUMCANNON_CARTRIDGE.get());
    }
}
