package com.qkdream.firecontrolcompat.client.model;

import com.qkdream.firecontrolcompat.entity.HeavyAirDefenseMissileEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model of the heavy air-defense missile (the 48n6 family), exported
 * straight from Blockbench. The nose points towards +Z and the tail fins
 * towards -Z, so the renderer is configured with the +Z nose convention to
 * lay the nose onto the flight vector.
 */
public class HeavyAaMissileModel extends GeoModel<HeavyAirDefenseMissileEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "geo/48n6.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "textures/entity/48n6texture.png");

    @Override
    public ResourceLocation getModelResource(HeavyAirDefenseMissileEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(HeavyAirDefenseMissileEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(HeavyAirDefenseMissileEntity animatable) {
        return null;
    }
}
