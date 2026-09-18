package com.qkdream.firecontrolcompat.network;

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
 * Server -> client side channel carrying the category of every radar contact,
 * so the lock-box label never has to guess from locally loaded entities
 * (which are missing for far-away ammunition).
 */
public record ContactTypePayload(List<ContactTypePayload.Entry> entries) implements CustomPacketPayload {

    public static final byte SABLE = 0;
    public static final byte AMMO = 1;
    public static final byte LIVING = 2;
    public static final byte OTHER = 3;

    /** Friend-or-foe marking carried next to the category. */
    public static final byte IFF_NONE = 0;
    public static final byte IFF_FRIENDLY = 1;
    public static final byte IFF_ENEMY = 2;
    public static final byte IFF_UNKNOWN = 3;

    public static final Type<ContactTypePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "contact_types")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, ContactTypePayload> STREAM_CODEC = StreamCodec.of(
            ContactTypePayload::write,
            ContactTypePayload::read
    );

    private static volatile Map<UUID, Entry> TYPES = Map.of();

    private static void write(RegistryFriendlyByteBuf buffer, ContactTypePayload payload) {
        buffer.writeVarInt(payload.entries.size());
        for (ContactTypePayload.Entry entry : payload.entries) {
            buffer.writeUUID(entry.id());
            buffer.writeByte(entry.type());
            buffer.writeByte(entry.iff());
        }
    }

    private static ContactTypePayload read(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<ContactTypePayload.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new ContactTypePayload.Entry(buffer.readUUID(), buffer.readByte(), buffer.readByte()));
        }
        return new ContactTypePayload(entries);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, ContactTypePayload::handle);
    }

    public static void handle(ContactTypePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Map<UUID, Entry> map = new HashMap<>(Math.max(4, payload.entries.size() * 2));
            for (ContactTypePayload.Entry entry : payload.entries) {
                map.put(entry.id(), entry);
            }
            TYPES = map;
        });
    }

    /** Latest server-reported type for a contact id, or null if unknown. */
    public static Byte lookup(UUID id) {
        Entry entry = TYPES.get(id);
        return entry == null ? null : entry.type();
    }

    /** Latest server-reported friend-or-foe marking, {@link #IFF_NONE} when unmarked. */
    public static byte lookupIff(UUID id) {
        Entry entry = TYPES.get(id);
        return entry == null ? IFF_NONE : entry.iff();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Entry(UUID id, byte type, byte iff) {
    }
}
