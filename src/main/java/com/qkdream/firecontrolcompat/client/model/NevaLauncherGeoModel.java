package com.qkdream.firecontrolcompat.client.model;

import com.qkdream.firecontrolcompat.NevaLauncherBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model of the Neva launcher block: the same S-125 geometry and
 * texture the flying missile uses, stood upright by the block renderer.
 */
public class NevaLauncherGeoModel extends GeoModel<NevaLauncherBlockEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "geo/s125.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "textures/entity/s125texture.png");

    @Override
    public ResourceLocation getModelResource(NevaLauncherBlockEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(NevaLauncherBlockEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(NevaLauncherBlockEntity animatable) {
        return null;
    }
}
