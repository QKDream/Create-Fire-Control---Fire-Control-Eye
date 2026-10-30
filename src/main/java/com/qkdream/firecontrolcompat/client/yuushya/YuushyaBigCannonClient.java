package com.qkdream.firecontrolcompat.client.yuushya;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaBigCannonCompat;
import net.minecraft.world.level.block.Block;
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
 *
 * <h2>Optional dependency (1.70.6)</h2>
 *
 * <p>The first line is the mod-loaded check, and everything that names a Yuushya Modelling class
 * lives in {@link YuushyaBarrelModelSwap}, which is only loaded after that check has passed. This
 * class is registered for <b>every</b> client, so without the split a pack without the modelling
 * mod threw {@code NoClassDefFoundError} at the first model bake.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class YuushyaBigCannonClient {

    private YuushyaBigCannonClient() {
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!YuushyaBigCannonCompat.isAvailable()) {
            YuushyaBigCannonCompat.logCompatSkippedOnce("model-swap");
            return;
        }
        Block barrel = YuushyaBigCannonCompat.SHOW_BIG_CANNON_BARREL == null
                ? null
                : YuushyaBigCannonCompat.SHOW_BIG_CANNON_BARREL.get();
        if (barrel == null) {
            return;
        }
        YuushyaBarrelModelSwap.apply(event, barrel);
    }
}
