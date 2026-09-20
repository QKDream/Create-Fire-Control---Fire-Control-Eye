package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.iff.IffTransponderMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Client -> server request to forget the band recorded on the open transponder. */
public record IffClearBandPayload() implements CustomPacketPayload {

    public static final Type<IffClearBandPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "iff_band_clear")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, IffClearBandPayload> STREAM_CODEC =
            StreamCodec.unit(new IffClearBandPayload());

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, IffClearBandPayload::handleServer);
    }

    public static void handleServer(IffClearBandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer
                    && serverPlayer.containerMenu instanceof IffTransponderMenu menu) {
                menu.clearBand();
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}