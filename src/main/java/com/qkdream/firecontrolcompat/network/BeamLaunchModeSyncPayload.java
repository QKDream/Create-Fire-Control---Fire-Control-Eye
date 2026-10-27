package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.BeamLaunchModeState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client answer carrying the launch mode already stored for the
 * computer whose screen was just opened.
 */
public record BeamLaunchModeSyncPayload(boolean vertical) implements CustomPacketPayload {

    public static final Type<BeamLaunchModeSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "beam_launch_mode_sync")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, BeamLaunchModeSyncPayload> STREAM_CODEC =
            StreamCodec.of(BeamLaunchModeSyncPayload::write, BeamLaunchModeSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, BeamLaunchModeSyncPayload payload) {
        buffer.writeBoolean(payload.vertical);
    }

    private static BeamLaunchModeSyncPayload read(RegistryFriendlyByteBuf buffer) {
        return new BeamLaunchModeSyncPayload(buffer.readBoolean());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, BeamLaunchModeSyncPayload::handleClient);
    }

    public static void handleClient(BeamLaunchModeSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BeamLaunchModeState.applyScreenVertical(payload.vertical));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}