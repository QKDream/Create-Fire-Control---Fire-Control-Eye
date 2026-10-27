package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.client.model.NevaMissileModel;
import com.qkdream.firecontrolcompat.entity.NevaAirDefenseMissileEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * GeckoLib renderer for the Neva (S-125) super-heavy air-defense missile.
 * {@link GeoMissileRenderer} lays the nose onto the flight vector every
 * frame and keeps the long-range trail / proxy suppression behavior. The
 * model carries its nose towards -Z (the tail fins sit at +Z), so the
 * renderer uses the -Z nose convention to keep the nose on the flight
 * vector, and matches the 2.8 scale of the placed launcher block (doubled on request).
 */
public class NevaMissileRenderer extends GeoMissileRenderer<NevaAirDefenseMissileEntity> {

    public NevaMissileRenderer(EntityRendererProvider.Context context) {
        super(context, new NevaMissileModel(), false);
        withScale(2.8F);
    }
}
