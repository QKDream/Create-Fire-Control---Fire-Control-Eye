package com.qkdream.firecontrolcompat.network;

import java.util.UUID;

import com.qkdream.firecontrolcompat.ControllerLeadState;

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
 * Client -> server: the lead settings of one fire control computer, as edited
 * on this mod's settings page.
 *
 * <p>The cannon family and the big cannon powder charge belong to the computer
 * the crew is sighting from, not to the mod as a whole, so they travel with the
 * computer's position and platform identity - the same pair the launch mode and
 * the radar call sign already use. The server writes them onto that computer and
 * answers with {@link ControllerLeadSyncPayload}, so the page is filled from what
 * was actually stored rather than from what the client hoped it sent.</p>
 */
public record ControllerLeadPayload(BlockPos pos, UUID subLevel, String kind, int charge)
        implements CustomPacketPayload {

    public static final Type<ControllerLeadPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "controller_lead")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerLeadPayload> STREAM_CODEC =
            StreamCodec.of(ControllerLeadPayload::write, ControllerLeadPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, ControllerLeadPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevel != null);
        if (payload.subLevel != null) {
            buffer.writeUUID(payload.subLevel);
        }
        buffer.writeUtf(payload.kind == null ? "" : payload.kind, 32);
        buffer.writeVarInt(payload.charge);
    }

    private static ControllerLeadPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        String kind = buffer.readUtf(32);
        int charge = buffer.readVarInt();
        return new ControllerLeadPayload(pos, subLevel, kind, charge);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, ControllerLeadPayload::handleServer);
    }

    public static void handleServer(ControllerLeadPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || payload.pos == null) {
                return;
            }
            ControllerLeadState.applyFor(player, payload.pos, payload.subLevel, payload.kind, payload.charge);
            ControllerLeadState.sendStatus(player, payload.pos, payload.subLevel);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
