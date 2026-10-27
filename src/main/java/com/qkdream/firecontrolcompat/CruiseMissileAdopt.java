package com.qkdream.firecontrolcompat;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.qkdream.firecontrolcompat.laser.LaserRoundTickets;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Hands a cruise missile that has just come into the world to
 * {@link CruiseMissileRackCompat}.
 *
 * <p>A rail standing on a welded structure throws her round without the round
 * ever standing in the world the rail stands in, so the look around the muzzle
 * finds nothing and no one ever claims her - and an unclaimed round is a round
 * nothing keeps in the air, which is what leaves her hanging in place once she
 * runs past the chunks the game is ticking. Whichever level she comes into, she
 * is matched to the rail that fired her the moment she arrives.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID)
public final class CruiseMissileAdopt {

    private CruiseMissileAdopt() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        CruiseMissileRackCompat.adopt(event.getEntity());
        LaserRoundTickets.adopt(event.getEntity());
    }
}
