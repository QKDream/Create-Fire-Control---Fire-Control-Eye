package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.BeamLaunchModeStore;
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
 * Client -> server launch mode of the fire control computer whose screen is
 * open. The server files it under that computer's platform identity, so the
 * setting follows the rig rather than every rig in the world.
 */
public record BeamLaunchModePayload(BlockPos pos, UUID subLevel, boolean vertical) implements CustomPacketPayload {

    public static final Type<BeamLaunchModePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "beam_launch_mode")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, BeamLaunchModePayload> STREAM_CODEC =
            StreamCodec.of(BeamLaunchModePayload::write, BeamLaunchModePayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, BeamLaunchModePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevel != null);
        if (payload.subLevel != null) {
            buffer.writeUUID(payload.subLevel);
        }
        buffer.writeBoolean(payload.vertical);
    }

    private static BeamLaunchModePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        return new BeamLaunchModePayload(pos, subLevel, buffer.readBoolean());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, BeamLaunchModePayload::handleServer);
    }

    public static void handleServer(BeamLaunchModePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BeamLaunchModeStore.setMode(player, payload.pos, payload.subLevel, payload.vertical);
            BeamLaunchModeStore.sendStatus(player, player.serverLevel(), payload.pos, payload.subLevel);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}