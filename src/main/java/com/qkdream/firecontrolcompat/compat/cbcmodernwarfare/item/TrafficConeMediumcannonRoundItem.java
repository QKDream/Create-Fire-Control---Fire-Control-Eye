package com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.item;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMunitionPropertiesHandlers;
import riftyboi.cbcmodernwarfare.munitions.ItemStackDataUtil;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonRoundItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.InertMediumcannonProjectileProperties;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.MediumcannonProjectilePropertiesComponent;

import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.CbcModernWarfareCompat;
import com.qkdream.firecontrolcompat.compat.cbcmodernwarfare.entity.TrafficConeMediumcannonProjectile;

public class TrafficConeMediumcannonRoundItem extends MediumcannonRoundItem {

    public TrafficConeMediumcannonRoundItem(Properties properties) {
        super(properties);
    }

    @Override
    public AbstractMediumcannonProjectile getMediumcannonProjectile(ItemStack stack, Level level) {
        TrafficConeMediumcannonProjectile projectile =
                CbcModernWarfareCompat.TRAFFIC_CONE_MEDIUMSHELL.get().create(level);
        if (projectile != null && ItemStackDataUtil.isTracer(stack)) {
            projectile.setTracer(true);
        }
        return projectile;
    }

    @Override
    public MediumcannonProjectilePropertiesComponent getMediumcannonProperties(ItemStack itemStack) {
        return ((InertMediumcannonProjectileProperties) CBCModernWarfareMunitionPropertiesHandlers.INERT_MEDIUMCANNON_PROJECTILE
                .getPropertiesOf(this.getEntityType(itemStack))).mediumcannonProperties();
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return CbcModernWarfareCompat.TRAFFIC_CONE_MEDIUMSHELL.get();
    }

    @Override
    public ItemStack getCartridgeType() {
        return new ItemStack(CbcModernWarfareCompat.TRAFFIC_CONE_MEDIUMCANNON_CARTRIDGE.get());
    }
}
