package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.client.model.Fim92MissileModel;
import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * GeckoLib renderer for the ground-launched infrared missile (fim92): the
 * Blockbench model is driven straight from the geo file instead of the old
 * mianbao man-portable model. The model carries its nose towards +Z, so the
 * renderer uses the +Z nose convention to keep the nose on the flight vector.
 */
public class Fim92MissileRenderer extends GeoMissileRenderer<InfraredMissileEntity> {

    public Fim92MissileRenderer(EntityRendererProvider.Context context) {
        super(context, new Fim92MissileModel(), true);
    }
}
