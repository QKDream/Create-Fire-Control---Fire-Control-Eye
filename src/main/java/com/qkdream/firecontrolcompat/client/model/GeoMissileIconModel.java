package com.qkdream.firecontrolcompat.client.model;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/**
 * Item-icon model: loads a Blockbench missile geometry and texture by path so
 * the inventory icon of a missile item shows the same model the projectile
 * renders with.
 *
 * @param <T> the item animatable the renderer passes in
 */
public class GeoMissileIconModel<T extends GeoAnimatable> extends GeoModel<T> {

    private final ResourceLocation model;
    private final ResourceLocation texture;

    public GeoMissileIconModel(String geoPath, String texturePath) {
        this.model = ResourceLocation.fromNamespaceAndPath("firecontrolcompat", geoPath);
        this.texture = ResourceLocation.fromNamespaceAndPath("firecontrolcompat", texturePath);
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return this.model;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return this.texture;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return null;
    }
}
