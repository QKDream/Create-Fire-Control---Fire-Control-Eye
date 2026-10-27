package com.qkdream.firecontrolcompat.client.model;

import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model of the infrared anti-tank round's airframe.
 *
 * <p>The airframe the user supplied is a Bockbench {@code elements} export (format 1.9.0), which
 * GeckoLib refuses - and because {@code GeckoLibCache} walks the whole {@code geo/} folder on every
 * resource reload, that refusal showed up in the log even while no renderer named the file. It is
 * converted to {@code format_version: "1.12.0"} + {@code minecraft:geometry[].bones[].cubes[]} by
 * {@code _work/convert_ir_atgm.py} (side: {@code _work/render_ir_atgm_preview.py}), so this model
 * is the airframe as authored: a 1.57 block long round with four mid fins and four tail fins, nose
 * towards -Z, motor nozzle towards +Z, drawn from one 512x256 atlas that carries all six of the
 * source textures at their original UV mapping.</p>
 *
 * <h2>Why no texel of the atlas is left transparent</h2>
 *
 * <p>GeckoLib draws an entity with {@code RenderType.entityCutoutNoCull}
 * ({@code GeoModel#getRenderType}, javap of 4.9.2), so an unpainted texel is a <em>hole</em> in the
 * model, and a mipmap averages a narrow painted strip together with the transparent canvas around
 * it until the strip fails the alpha test at range. The source art only paints the panels it needs,
 * so the converter fills every transparent texel of every atlas cell (two texels of neighbour
 * colour, then the cell's painted average). The art stays exactly where the author put it, every
 * face samples only opaque texels, and the round cannot show holes or fade out with distance.</p>
 */
public class IrAtgmMissileModel extends GeoModel<InfraredMissileEntity> {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "geo/ir_atgm.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat",
                    "textures/entity/ir_atgm_texture.png");

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
