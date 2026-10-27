package com.qkdream.firecontrolcompat.network;

import java.util.UUID;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.SpyglassMarkState;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client answer carrying the mark the gunner's scope just set, so a
 * frame can be drawn around the structure. The position comes from the server
 * because only the server knows where the sight line actually met a sub-level:
 * the client's own pick is far too short to find a ship at range.
 */
public record SpyglassMarkClientPayload(boolean active, UUID structureId, Vec3 point) implements CustomPacketPayload {

    public static final Type<SpyglassMarkClientPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "spyglass_mark_client"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpyglassMarkClientPayload> STREAM_CODEC =
            StreamCodec.of(SpyglassMarkClientPayload::write, SpyglassMarkClientPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, SpyglassMarkClientPayload payload) {
        buffer.writeBoolean(payload.active);
        buffer.writeBoolean(payload.structureId != null);
        if (payload.structureId != null) {
            buffer.writeUUID(payload.structureId);
        }
        Vec3 point = payload.point;
        buffer.writeDouble(point == null ? 0.0 : point.x);
        buffer.writeDouble(point == null ? 0.0 : point.y);
        buffer.writeDouble(point == null ? 0.0 : point.z);
    }

    private static SpyglassMarkClientPayload read(RegistryFriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        UUID structureId = buffer.readBoolean() ? buffer.readUUID() : null;
        Vec3 point = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        return new SpyglassMarkClientPayload(active, structureId, point);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, SpyglassMarkClientPayload::handleClient);
    }

    /** Hands the mark to one gunner; nothing is sent when the mark was taken back. */
    public static void send(ServerPlayer player, boolean active, UUID structureId, Vec3 point) {
        PacketDistributor.sendToPlayer(player, new SpyglassMarkClientPayload(active, structureId, point));
    }

    public static void handleClient(SpyglassMarkClientPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] spyglass mark client active={} structure={} point={}",
                    payload.active, payload.structureId, payload.point);
            SpyglassMarkState.apply(payload.active, payload.structureId, payload.point);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}