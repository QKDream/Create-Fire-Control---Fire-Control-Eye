package com.qkdream.firecontrolcompat.client.model;

import com.qkdream.firecontrolcompat.entity.NevaAirDefenseMissileEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model of the Neva (S-125) super-heavy air-defense missile,
 * exported straight from Blockbench. The nose points towards -Z and the
 * tail fins towards +Z.
 */
public class NevaMissileModel extends GeoModel<NevaAirDefenseMissileEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "geo/s125.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "textures/entity/s125texture.png");

    @Override
    public ResourceLocation getModelResource(NevaAirDefenseMissileEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(NevaAirDefenseMissileEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(NevaAirDefenseMissileEntity animatable) {
        return null;
    }
}
