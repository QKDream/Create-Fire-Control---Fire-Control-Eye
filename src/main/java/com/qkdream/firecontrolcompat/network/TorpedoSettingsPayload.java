package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.TorpedoSettingsStore;
import com.qkdream.firecontrolcompat.sonar.SonarSettings;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server: the acoustic torpedo page files the three values it holds on
 * the computer the page was opened from, so every other computer in the world
 * keeps its own answer. The sonar page's own sync is sent straight back, which
 * is where the client reads the filed values from the next time it is opened.
 */
public record TorpedoSettingsPayload(
        BlockPos controllerPos,
        UUID controllerSubLevel,
        boolean holdDepth,
        double depth,
        double activation) implements CustomPacketPayload {

    public static final Type<TorpedoSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "torpedo_settings")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, TorpedoSettingsPayload> STREAM_CODEC =
            StreamCodec.of(TorpedoSettingsPayload::write, TorpedoSettingsPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, TorpedoSettingsPayload payload) {
        buffer.writeBlockPos(payload.controllerPos);
        buffer.writeBoolean(payload.controllerSubLevel != null);
        if (payload.controllerSubLevel != null) {
            buffer.writeUUID(payload.controllerSubLevel);
        }
        buffer.writeBoolean(payload.holdDepth);
        buffer.writeDouble(payload.depth);
        buffer.writeDouble(payload.activation);
    }

    private static TorpedoSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos controllerPos = buffer.readBlockPos();
        UUID controllerSubLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        return new TorpedoSettingsPayload(
                controllerPos, controllerSubLevel, buffer.readBoolean(), buffer.readDouble(), buffer.readDouble());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, TorpedoSettingsPayload::handleServer);
    }

    public static void handleServer(TorpedoSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            TorpedoSettingsStore.set(
                    player, payload.controllerPos, payload.controllerSubLevel,
                    payload.holdDepth, payload.depth, payload.activation);
            SonarSettings.sendStatus(player, payload.controllerPos, payload.controllerSubLevel);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
