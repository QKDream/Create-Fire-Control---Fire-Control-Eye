package com.qkdream.firecontrolcompat.client.model;

import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model of the ground-launched infrared missile (the Stinger-class
 * fim92), exported straight from Blockbench. The nose points towards +Z and
 * the tail fins towards -Z, so the renderer is configured with the +Z nose
 * convention to lay the nose onto the flight vector.
 */
public class Fim92MissileModel extends GeoModel<InfraredMissileEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "geo/fim92.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "textures/entity/fim92texture.png");

    @Override
    public ResourceLocation getModelResource(InfraredMissileEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(InfraredMissileEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(InfraredMissileEntity animatable) {
        return null;
    }
}
