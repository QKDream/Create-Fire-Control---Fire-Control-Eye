package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.network.SpyglassMarkPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Turns a left click made through a scoped spyglass into a mark: clicking on
 * anything marks what lies on that bearing, clicking at empty sky takes the
 * mark back again.
 *
 * <p>The click is read off the raw mouse button and not off the usual block
 * interaction event, because the game discards every attack click while an item
 * is in use - it drains the attack key without ever acting on it - and holding a
 * spyglass to the eye is exactly that. The interaction event therefore never
 * arrives for the one click this is waiting for.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SpyglassMarkClient {

    private static final int LEFT_BUTTON = 0;
    private static final int PRESS = 1;

    private SpyglassMarkClient() {
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != LEFT_BUTTON || event.getAction() != PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || minecraft.screen != null || minecraft.getOverlay() != null) {
            return;
        }
        boolean scoped = scoped(minecraft.player);
        boolean holding = holdsSpyglass(minecraft.player);
        if (scoped || holding) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] spyglass click scoped={} holding={}", scoped, holding);
        }
        if (!scoped) {
            return;
        }
        PacketDistributor.sendToServer(new SpyglassMarkPayload());
    }

    /** Whether the player currently has the scope to their eye. */
    private static boolean scoped(Player player) {
        return player.isUsingItem() && player.getUseItem().is(Items.SPYGLASS);
    }

    private static boolean holdsSpyglass(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(Items.SPYGLASS)) {
                return true;
            }
        }
        return false;
    }
}