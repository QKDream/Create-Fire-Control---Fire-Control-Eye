package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.vestalihy.client.Keybinds;
import com.vestalihy.network.MalyutkaSteerPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends the 9M14 steering keys while the player sits at a fire control
 * computer.
 *
 * <p>vestalihy's own client only offers those keys to a player looking through
 * one of its scopes, so a round released by the fire control computer had
 * nobody to obey. The four directions are read from vestalihy's own keys, so a
 * player who rebound them keeps the bindings, and from the arrow pad on the
 * number pad as well. While a key is held the state is sent every tick, because
 * a round spends the input it was given on its own tick, and one last state is
 * sent when the keys are released so the round stops turning.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT)
public final class VestalihyMalytkaSteerClient {

    /** Number pad arrows, used next to vestalihy's own keys. */
    private static final int PAD_LEFT = 324;
    private static final int PAD_RIGHT = 326;
    private static final int PAD_UP = 328;
    private static final int PAD_DOWN = 322;

    /** The state sent last, so a release is sent exactly once. */
    private static int lastSent = -1;

    private VestalihyMalytkaSteerClient() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.getVehicle() == null || minecraft.getWindow() == null) {
            lastSent = -1;
            return;
        }
        long window = minecraft.getWindow().getWindow();
        boolean left = pressed(Keybinds.CONTROL_LEFT, window, PAD_LEFT);
        boolean right = pressed(Keybinds.CONTROL_RIGHT, window, PAD_RIGHT);
        boolean up = pressed(Keybinds.CONTROL_UP, window, PAD_UP);
        boolean down = pressed(Keybinds.CONTROL_DOWN, window, PAD_DOWN);
        int state = (left ? 1 : 0) | (right ? 2 : 0) | (up ? 4 : 0) | (down ? 8 : 0);
        if (state == 0 && lastSent <= 0) {
            lastSent = state;
            return;
        }
        lastSent = state;
        PacketDistributor.sendToServer(new MalyutkaSteerPacket(left, right, up, down));
    }

    private static boolean pressed(KeyMapping mapping, long window, int padKey) {
        return mapping.isDown() || InputConstants.isKeyDown(window, padKey);
    }
}
