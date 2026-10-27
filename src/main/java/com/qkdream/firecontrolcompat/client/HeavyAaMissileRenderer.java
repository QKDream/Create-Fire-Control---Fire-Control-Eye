package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.client.model.HeavyAaMissileModel;
import com.qkdream.firecontrolcompat.entity.HeavyAirDefenseMissileEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * GeckoLib renderer for the heavy air-defense missile (48n6). The Blockbench
 * model is driven straight from the geo file instead of the old item-model
 * pipeline, and {@link GeoMissileRenderer} lays the nose onto the flight
 * vector every frame. The model carries its nose towards +Z, so the renderer
 * uses the +Z nose convention to keep the nose on the flight vector.
 */
public class HeavyAaMissileRenderer extends GeoMissileRenderer<HeavyAirDefenseMissileEntity> {

    public HeavyAaMissileRenderer(EntityRendererProvider.Context context) {
        super(context, new HeavyAaMissileModel(), true);
    }
}
