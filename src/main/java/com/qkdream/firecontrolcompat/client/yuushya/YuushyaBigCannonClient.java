package com.qkdream.firecontrolcompat.client.yuushya;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaBigCannonCompat;
import com.yuushya.modelling.client.NeoShowBlockModel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Model of the barrel block.
 *
 * <p>A modelling block does not draw itself from its own textures: the block
 * model is a stand-in that hands the drawing over to the block entity, which is
 * why the modelling mod swaps the baked model of its two blocks for its own
 * model class at load time. A block of another mod is not on that list, so the
 * swap is done here for the barrel block as well - without it the barrel would
 * show up as an empty block and the model the player put inside it would be
 * invisible.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class YuushyaBigCannonClient {

    private YuushyaBigCannonClient() {
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Block barrel = YuushyaBigCannonCompat.SHOW_BIG_CANNON_BARREL == null
                ? null
                : YuushyaBigCannonCompat.SHOW_BIG_CANNON_BARREL.get();
        if (barrel == null) {
            return;
        }
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