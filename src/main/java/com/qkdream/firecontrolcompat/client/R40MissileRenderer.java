package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.client.model.R40MissileModel;
import com.qkdream.firecontrolcompat.entity.SemiActiveMissileEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/** GeckoLib renderer for the semi-active round. */
public class R40MissileRenderer extends GeoMissileRenderer<SemiActiveMissileEntity> {

    /**
     * Drawn with back faces kept.
     *
     * <p>The R-40 model's nose is built from single-sided surface pieces (Blockbench's approximate
     * triangle surfaces). With the default cutout type the back of those pieces is culled, so the
     * nose shows up as one visible sheet - "only one piece" - instead of a cone. Keeping back faces
     * draws the whole nose.</p>
     */
    @Override
    public net.minecraft.client.renderer.RenderType getRenderType(SemiActiveMissileEntity animatable,
            net.minecraft.resources.ResourceLocation texture,
            net.minecraft.client.renderer.MultiBufferSource bufferSource, float partialTick) {
        return net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texture);
    }

    public R40MissileRenderer(EntityRendererProvider.Context context) {
        // The R-40 is a big round and reads small on a rail; it is drawn half again as large.
        super(context, new R40MissileModel(), true, 1.35F);
    }
}
