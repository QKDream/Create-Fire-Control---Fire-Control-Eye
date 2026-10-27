package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.RadarNameStore;
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
 * Client -> server call sign for the fire control computer whose screen is
 * open. The server stores it under the platform identity and answers with the
 * stored value through {@link RadarNameSyncPayload}.
 */
public record RadarNamePayload(BlockPos pos, UUID subLevel, String name) implements CustomPacketPayload {

    public static final Type<RadarNamePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "radar_name")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RadarNamePayload> STREAM_CODEC =
            StreamCodec.of(RadarNamePayload::write, RadarNamePayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, RadarNamePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevel != null);
        if (payload.subLevel != null) {
            buffer.writeUUID(payload.subLevel);
        }
        buffer.writeUtf(payload.name == null ? "" : payload.name, 32);
    }

    private static RadarNamePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        return new RadarNamePayload(pos, subLevel, buffer.readUtf(32));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, RadarNamePayload::handleServer);
    }

    public static void handleServer(RadarNamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            RadarNameStore.setName(player, payload.pos, payload.subLevel, payload.name);
            RadarNameStore.sendStatus(player, player.serverLevel(), payload.pos, payload.subLevel);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
