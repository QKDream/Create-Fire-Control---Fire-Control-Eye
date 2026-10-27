package com.qkdream.firecontrolcompat.network;

import java.util.UUID;

import com.qkdream.firecontrolcompat.ControllerLeadClientState;
import com.qkdream.firecontrolcompat.LeadCannonKind;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client: what one fire control computer actually carries for its lead.
 *
 * <p>Sent when the computer is opened and again after an edit, so this mod's
 * settings page is filled from the computer itself instead of from the mod's
 * global defaults. Several computers in one world each answer with their own
 * pair, which is what makes two rigs sight two different guns.</p>
 */
public record ControllerLeadSyncPayload(BlockPos pos, UUID subLevel, String kind, int charge)
        implements CustomPacketPayload {

    public static final Type<ControllerLeadSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "controller_lead_sync")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerLeadSyncPayload> STREAM_CODEC =
            StreamCodec.of(ControllerLeadSyncPayload::write, ControllerLeadSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, ControllerLeadSyncPayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevel != null);
        if (payload.subLevel != null) {
            buffer.writeUUID(payload.subLevel);
        }
        buffer.writeUtf(payload.kind == null ? "" : payload.kind, 32);
        buffer.writeVarInt(payload.charge);
    }

    private static ControllerLeadSyncPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        String kind = buffer.readUtf(32);
        int charge = buffer.readVarInt();
        return new ControllerLeadSyncPayload(pos, subLevel, kind, charge);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, ControllerLeadSyncPayload::handleClient);
    }

    public static void handleClient(ControllerLeadSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            LeadCannonKind parsed;
            try {
                parsed = payload.kind == null || payload.kind.isEmpty()
                        ? null
                        : LeadCannonKind.valueOf(payload.kind);
            } catch (IllegalArgumentException unknown) {
                parsed = null;
            }
            ControllerLeadClientState.apply(payload.pos, payload.subLevel, parsed, payload.charge);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
