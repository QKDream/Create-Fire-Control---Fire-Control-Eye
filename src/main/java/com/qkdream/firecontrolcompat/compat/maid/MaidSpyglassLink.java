package com.qkdream.firecontrolcompat.compat.maid;

import java.util.List;
import java.util.UUID;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.qkdream.firecontrolcompat.SpyglassLink;
import com.qkdream.firecontrolcompat.SpyglassMarkStore;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Pointing a scope at a maid.
 *
 * <p>Right-clicking a fire control operator while holding a spyglass links her
 * to that scope, and every structure the gunner then marks with it is called
 * onto her and onto the other operators on the same scope. A second right click
 * on the same maid lets her go again, and her standing order goes with the
 * link.</p>
 *
 * <p>The click is deliberately not cancelled: the scope is what the gunner is
 * holding, so the game goes on to raise it to the eye and the gunner can mark
 * without a second gesture.</p>
 */
public final class MaidSpyglassLink {

    private static boolean registered;

    private MaidSpyglassLink() {
    }

    /** Hooks the listener once, from the maid extension that is loaded with her. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.register(MaidSpyglassLink.class);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof EntityMaid maid)) {
            return;
        }
        // Fired on both sides; only the server owns which operator a scope points at.
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack scope = event.getItemStack();
        if (!SpyglassLink.isSpyglass(scope)) {
            return;
        }
        UUID maidId = maid.getUUID();
        List<UUID> linked = SpyglassLink.linkedMaids(scope);
        if (!linked.contains(maidId) && linked.size() >= SpyglassLink.MAX_LINKS) {
            player.displayClientMessage(
                    Component.literal("这架望远镜已经指向 " + SpyglassLink.MAX_LINKS + " 名火控员了"), true);
            return;
        }
        boolean added = SpyglassLink.toggle(scope, maidId);
        if (!added) {
            SpyglassMarkStore.clearMaid(player.serverLevel(), maidId);
            player.displayClientMessage(
                    Component.literal("已断开与这名火控员的连接，她的指定目标也随之取消"), true);
            return;
        }
        player.displayClientMessage(
                Component.literal("望远镜已指向这名火控员（共 "
                        + SpyglassLink.linkedMaids(scope).size() + " 名），开镜左键即可为她指定目标"), true);
    }
}