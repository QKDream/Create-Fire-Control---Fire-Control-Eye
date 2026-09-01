package com.qkdream.firecontrolcompat.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client channel carrying the position the infrared missile seeker
 * is currently tracking, so the fire control seat HUD can draw a red lock
 * square around the missile's chosen target.
 */
public record MissileTrackPayload(UUID missileId, Vec3 targetPos) implements CustomPacketPayload {

    public static final Type<MissileTrackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "missile_track")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, MissileTrackPayload> STREAM_CODEC = StreamCodec.of(
            MissileTrackPayload::write,
            MissileTrackPayload::read
    );

    private static final Map<UUID, MissileTrackPayload.TrackPoint> TRACKS = new HashMap<>();

    private static void write(RegistryFriendlyByteBuf buffer, MissileTrackPayload payload) {
        buffer.writeUUID(payload.missileId);
        buffer.writeDouble(payload.targetPos.x);
        buffer.writeDouble(payload.targetPos.y);
        buffer.writeDouble(payload.targetPos.z);
    }

    private static MissileTrackPayload read(RegistryFriendlyByteBuf buffer) {
        return new MissileTrackPayload(
                buffer.readUUID(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, MissileTrackPayload::handle);
    }

    public static void handle(MissileTrackPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }
            synchronized (TRACKS) {
                TRACKS.put(payload.missileId, new MissileTrackPayload.TrackPoint(
                        payload.targetPos, minecraft.level.getGameTime()));
            }
        });
    }

    /** Seeker targets received within the last few ticks; stale entries are pruned. */
    public static Map<UUID, Vec3> freshTargets(long now) {
        Map<UUID, Vec3> result = new HashMap<>();
        synchronized (TRACKS) {
            TRACKS.values().removeIf(point -> now - point.tick > 40L);
            TRACKS.forEach((id, point) -> {
                if (now - point.tick <= 6L) {
                    result.put(id, point.position);
                }
            });
        }
        return result;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private record TrackPoint(Vec3 position, long tick) {
    }
}
