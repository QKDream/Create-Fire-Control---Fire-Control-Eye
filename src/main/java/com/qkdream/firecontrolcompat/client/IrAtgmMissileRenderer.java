package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.client.model.IrAtgmMissileModel;
import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * GeckoLib renderer for the infrared anti-tank round.
 *
 * <p>Its airframe is its own (a Spike-class round, not the fim92 the plain infrared round uses),
 * and unlike the fim92 export it carries the nose towards -Z, so this renderer keeps the default
 * nose convention of {@link GeoMissileRenderer}. The top attack is a flight profile, not a look:
 * the round climbs and dives with the same model the whole way.</p>
 */
public class IrAtgmMissileRenderer extends GeoMissileRenderer<InfraredMissileEntity> {

    public IrAtgmMissileRenderer(EntityRendererProvider.Context context) {
        super(context, new IrAtgmMissileModel());
    }
}
