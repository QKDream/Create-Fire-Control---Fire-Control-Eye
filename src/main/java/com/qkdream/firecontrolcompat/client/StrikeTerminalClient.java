package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.qkdream.firecontrolcompat.StrikeTerminalItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Opens the strike board's map. Only ever touched on the client. */
@OnlyIn(Dist.CLIENT)
public final class StrikeTerminalClient {

    private StrikeTerminalClient() {
    }

    /** Opens the board, or says why it cannot be opened. */
    public static void open(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        BindingRef controller = StrikeTerminalItem.controller(stack);
        if (controller == null || minecraft == null) {
            return;
        }
        minecraft.setScreen(new StrikeTerminalScreen(controller, stack));
    }
}
