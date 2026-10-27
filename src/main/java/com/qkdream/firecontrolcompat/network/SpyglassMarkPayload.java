package com.qkdream.firecontrolcompat.network;

import java.util.List;
import java.util.UUID;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.SpyglassLink;
import com.qkdream.firecontrolcompat.SpyglassMarkRay;
import com.qkdream.firecontrolcompat.SpyglassMarkStore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server spyglass mark: the trigger was pulled through a scoped
 * spyglass.
 *
 * <p>The packet carries nothing, because both halves of the decision are made
 * where they can actually be made. Which operators a mark is meant for is read
 * off the scope the player is holding, and whether the sight line met a
 * structure is answered by walking the ray on the server - the client cannot
 * tell a ship on the horizon from empty sky, and a click at empty sky is the
 * gesture that takes a mark back.</p>
 */
public record SpyglassMarkPayload() implements CustomPacketPayload {

    public static final Type<SpyglassMarkPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "spyglass_mark"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpyglassMarkPayload> STREAM_CODEC =
            StreamCodec.unit(new SpyglassMarkPayload());

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, SpyglassMarkPayload::handleServer);
    }

    public static void handleServer(SpyglassMarkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            ItemStack scope = heldSpyglass(player);
            if (scope == null) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] spyglass mark: no scope in hand player={}",
                        player.getName().getString());
                return;
            }
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            SpyglassMarkRay.Result result = SpyglassMarkRay.find(
                    level, eye, look, player.position(), SpyglassMarkRay.REACH);
            SpyglassMarkRay.Hit hit = result.hit();
            boolean blocked = false;
            if (hit != null && SpyglassMarkRay.blocked(level, eye, look, hit)) {
                hit = null;
                blocked = true;
            }
            List<UUID> linked = SpyglassLink.linkedMaids(scope);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] spyglass pick hulls={} candidates={} blockHits={} "
                            + "ownSkipped={} noContainer={} reach={} blocked={} hit={}",
                    result.hulls(), result.candidates(), result.blockHits(), result.ownSkipped(),
                    result.noContainer(), (int) result.reach(), blocked,
                    hit == null ? "none" : hit.structureId());
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] spyglass mark player={} maids={} hit={}",
                    player.getName().getString(), linked.size(),
                    hit == null ? "none" : hit.structureId());
            if (hit == null) {
                // A click at empty sky is the gesture that takes the mark back,
                // and the gunner is told which of the two just happened: the
                // frame lives far away on a hull that may be off screen, where
                // it cannot answer for itself.
                boolean cleared = SpyglassMarkStore.clearLast(level, player);
                SpyglassMarkClientPayload.send(player, false, null, null);
                player.displayClientMessage(Component.literal(
                        cleared ? "已解除指定目标" : "准星前方没有可以指定的结构"), true);
                return;
            }
            // A scope that is pointed at nobody has nobody to call: say so rather
            // than silently swallowing the click, which reads as marking being
            // broken all over again.
            if (linked.isEmpty()) {
                player.displayClientMessage(
                        Component.literal("这架望远镜还没有指向任何火控员：先右键一名火控员连接"), true);
                return;
            }
            SpyglassMarkStore.mark(level, player, hit.structureId(), linked);
            SpyglassMarkClientPayload.send(player, true, hit.structureId(), hit.point());
            player.displayClientMessage(Component.literal(
                    "已把目标指派给 " + linked.size() + " 名火控员，距离 "
                            + Math.round(player.getEyePosition().distanceTo(hit.point())) + " 格"), true);
        });
    }

    /** The scope the click came from: the one being held to the eye, else either hand. */
    private static ItemStack heldSpyglass(ServerPlayer player) {
        ItemStack used = player.getUseItem();
        if (SpyglassLink.isSpyglass(used)) {
            return used;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (SpyglassLink.isSpyglass(stack)) {
                return stack;
            }
        }
        return null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}