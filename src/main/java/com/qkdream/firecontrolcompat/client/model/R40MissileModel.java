package com.qkdream.firecontrolcompat.client.model;

import com.qkdream.firecontrolcompat.entity.SemiActiveMissileEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/** GeckoLib model of the semi-active round (R-40), nose towards +Z like the other missiles. */
public class R40MissileModel extends GeoModel<SemiActiveMissileEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "geo/r40.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "textures/entity/r40_texture.png");

    @Override
    public ResourceLocation getModelResource(SemiActiveMissileEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SemiActiveMissileEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(SemiActiveMissileEntity animatable) {
        return null;
    }
}
