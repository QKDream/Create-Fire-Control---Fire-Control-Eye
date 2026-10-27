package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.RadarNameState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client answer carrying the call sign already stored for the
 * computer whose screen was just opened, so the name box can be filled in.
 */
public record RadarNameSyncPayload(String name) implements CustomPacketPayload {

    public static final Type<RadarNameSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "radar_name_sync")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RadarNameSyncPayload> STREAM_CODEC =
            StreamCodec.of(RadarNameSyncPayload::write, RadarNameSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, RadarNameSyncPayload payload) {
        buffer.writeUtf(payload.name == null ? "" : payload.name, 32);
    }

    private static RadarNameSyncPayload read(RegistryFriendlyByteBuf buffer) {
        return new RadarNameSyncPayload(buffer.readUtf(32));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, RadarNameSyncPayload::handleClient);
    }

    public static void handleClient(RadarNameSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RadarNameState.applyScreenName(payload.name));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
