package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

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
 *
 * <h2>Optional dependency (1.70.6)</h2>
 *
 * <p>Nothing of vestalihy is named in this class's bytecode any more: the two classes used
 * ({@code com.vestalihy.client.Keybinds} and {@code com.vestalihy.network.MalyutkaSteerPacket}) are
 * reached through {@link VestalihySteerApi}, which reflects into them. The old direct references
 * were a link-time hazard, not a runtime one - the verifier had to resolve the packet class to
 * type-check the send call, so a pack without vestalihy threw {@code NoClassDefFoundError} before
 * {@code onClientTick} had a chance to ask whether the mod was there. Now the first line of the
 * tick asks, the probe caches the answer, and an absent or changed API costs one {@code return}.</p>
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
        // First line on purpose: with vestalihy absent (or its API changed) the probe answers from
        // its cache and nothing below - least of all a vestalihy class - is touched.
        if (!VestalihySteerApi.available()) {
            lastSent = -1;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.getVehicle() == null || minecraft.getWindow() == null) {
            lastSent = -1;
            return;
        }
        long window = minecraft.getWindow().getWindow();
        boolean left = pressed(VestalihySteerApi.key(0), window, PAD_LEFT);
        boolean right = pressed(VestalihySteerApi.key(1), window, PAD_RIGHT);
        boolean up = pressed(VestalihySteerApi.key(2), window, PAD_UP);
        boolean down = pressed(VestalihySteerApi.key(3), window, PAD_DOWN);
        int state = (left ? 1 : 0) | (right ? 2 : 0) | (up ? 4 : 0) | (down ? 8 : 0);
        if (state == 0 && lastSent <= 0) {
            lastSent = state;
            return;
        }
        lastSent = state;
        VestalihySteerApi.send(left, right, up, down);
    }

    private static boolean pressed(KeyMapping mapping, long window, int padKey) {
        return mapping.isDown() || InputConstants.isKeyDown(window, padKey);
    }
}
