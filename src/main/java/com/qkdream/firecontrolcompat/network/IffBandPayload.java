package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.iff.IffBand;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client copy of the band a transponder has recorded. The block keeps
 * no inventory, so the screen draws the two items as ghosts from this copy
 * instead of from stored slots.
 */
public record IffBandPayload(ItemStack first, ItemStack second) implements CustomPacketPayload {

    public static final Type<IffBandPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "iff_band")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, IffBandPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_STREAM_CODEC, IffBandPayload::first,
                    ItemStack.OPTIONAL_STREAM_CODEC, IffBandPayload::second,
                    IffBandPayload::new);

    private static volatile ItemStack cachedFirst = ItemStack.EMPTY;
    private static volatile ItemStack cachedSecond = ItemStack.EMPTY;

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, IffBandPayload::handle);
    }

    public static void handle(IffBandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            cachedFirst = payload.first();
            cachedSecond = payload.second();
        });
    }

    /** First item of the band the open screen belongs to; empty when unset. */
    public static ItemStack cachedFirst() {
        return cachedFirst;
    }

    /** Second item of the band the open screen belongs to; empty when unset. */
    public static ItemStack cachedSecond() {
        return cachedSecond;
    }

    public static void send(Player player, IffBand band) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        IffBand value = band == null ? IffBand.EMPTY : band;
        PacketDistributor.sendToPlayer(serverPlayer, new IffBandPayload(value.first(), value.second()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}