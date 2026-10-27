package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.RadarNameState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client call signs of the emitters behind the RWR threats of the
 * current radar session, sent right after the session itself.
 */
public record RadarThreatLabelPayload(List<RadarThreatLabelPayload.Entry> entries)
        implements CustomPacketPayload {

    /** One threat and the call sign of the radar that raised it. */
    public record Entry(UUID id, String name) {
    }

    public static final Type<RadarThreatLabelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "radar_threat_labels")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, RadarThreatLabelPayload> STREAM_CODEC =
            StreamCodec.of(RadarThreatLabelPayload::write, RadarThreatLabelPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, RadarThreatLabelPayload payload) {
        List<Entry> entries = payload.entries == null ? List.of() : payload.entries;
        buffer.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeUUID(entry.id());
            buffer.writeUtf(entry.name() == null ? "" : entry.name(), 32);
        }
    }

    private static RadarThreatLabelPayload read(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>(Math.max(0, size));
        for (int index = 0; index < size; index++) {
            UUID id = buffer.readUUID();
            entries.add(new Entry(id, buffer.readUtf(32)));
        }
        return new RadarThreatLabelPayload(List.copyOf(entries));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, RadarThreatLabelPayload::handleClient);
    }

    public static void handleClient(RadarThreatLabelPayload payload, IPayloadContext context) {
        Map<UUID, String> names = new HashMap<>();
        if (payload.entries != null) {
            for (Entry entry : payload.entries) {
                if (entry.name() != null && !entry.name().isEmpty()) {
                    names.put(entry.id(), entry.name());
                }
            }
        }
        context.enqueueWork(() -> RadarNameState.applyThreatNames(names));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
