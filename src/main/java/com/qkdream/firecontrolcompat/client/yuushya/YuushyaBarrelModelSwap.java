package com.qkdream.firecontrolcompat.client.yuushya;

import com.yuushya.modelling.client.NeoShowBlockModel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * The one call that really needs Yuushya Modelling's client classes.
 *
 * <p>Split out of {@link YuushyaBigCannonClient} on purpose. That class carries
 * {@code @EventBusSubscriber(value = Dist.CLIENT)}, so it is loaded on <b>every</b> client - a pack
 * without the modelling mod included. Holding {@code new NeoShowBlockModel(..)} there meant the
 * verifier had to resolve the modelling class to check that the result fits
 * {@code Map<ModelResourceLocation, BakedModel>}, and a client without Yuushya died with
 * {@code NoClassDefFoundError} the moment the bake event fired, before the guard could return.</p>
 *
 * <p>Here the modelling reference only exists in a class that is loaded after the guard has passed
 * - i.e. only when {@code isModLoaded("yuushya_modelling")} answered true.</p>
 */
public final class YuushyaBarrelModelSwap {

    private YuushyaBarrelModelSwap() {
    }

    /** Gives every state of the barrel block the modelling mod's own block model. */
    public static void apply(ModelEvent.ModifyBakingResult event, Block barrel) {
        for (BlockState state : barrel.getStateDefinition().getPossibleStates()) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            BakedModel original = event.getModels().get(location);
            if (original == null) {
                continue;
            }
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            event.getModels().put(location, new NeoShowBlockModel(facing, original));
        }
    }
}
