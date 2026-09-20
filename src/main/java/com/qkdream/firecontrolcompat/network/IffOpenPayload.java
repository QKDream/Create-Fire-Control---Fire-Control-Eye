package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.iff.IffTransponderBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server request to open the IFF frequency screen of the transponder
 * wired to the given fire control computer.
 */
public record IffOpenPayload(BlockPos controllerPos, UUID controllerSubLevel)
        implements CustomPacketPayload {

    public static final Type<IffOpenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "iff_open")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, IffOpenPayload> STREAM_CODEC =
            StreamCodec.of(IffOpenPayload::write, IffOpenPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, IffOpenPayload payload) {
        buffer.writeBlockPos(payload.controllerPos);
        buffer.writeBoolean(payload.controllerSubLevel != null);
        if (payload.controllerSubLevel != null) {
            buffer.writeUUID(payload.controllerSubLevel);
        }
    }

    private static IffOpenPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        return new IffOpenPayload(pos, subLevel);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, IffOpenPayload::handleServer);
    }

    public static void handleServer(IffOpenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            IffTransponderBlockEntity transponder = IffTransponderBlockEntity.findForController(
                    level, payload.controllerPos(), payload.controllerSubLevel());
            if (transponder == null) {
                player.displayClientMessage(
                        Component.translatable("message.firecontrolcompat.iff.no_link"), true);
                return;
            }
            player.openMenu(transponder);
            IffBandPayload.send(player, transponder.band());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}