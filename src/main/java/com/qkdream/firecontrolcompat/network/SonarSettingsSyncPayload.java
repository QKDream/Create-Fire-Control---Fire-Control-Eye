package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.TorpedoSettingsStore;
import com.qkdream.firecontrolcompat.sonar.SonarSelectInputState;
import com.qkdream.firecontrolcompat.sonar.SonarSettingsState;
import com.qkdream.firecontrolcompat.sonar.TorpedoSettingsState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client answer carrying every set wired to the computer whose screen
 * was just opened, plus the swim that computer files for its torpedoes and the
 * inputs it answers to for the sonar pick, so the pages list them and show the
 * values the computer actually runs.
 */
public record SonarSettingsSyncPayload(
        boolean bound,
        boolean torpedoHoldDepth,
        double torpedoDepth,
        double torpedoActivation,
        SonarSettingsSyncPayload.Select select,
        List<SonarSettingsSyncPayload.Entry> sonars) implements CustomPacketPayload {

    /**
     * The bindings the computer answers to for the sonar pick, as the page draws
     * them: the names of the two frequency items and the two remote signals.
     */
    public record Select(String first, String second, String normalKey, String advancedKey,
            String wirelessKey) {

        /** The bindings of a computer that answers to nothing but its key. */
        public static Select none() {
            return new Select("", "", "", "", "");
        }
    }

    /** One set, as the page needs it: who it is plus everything written on it. */
    public record Entry(
            long pos,
            UUID subLevel,
            double range,
            double halfAngle,
            double elevationUp,
            double elevationDown,
            boolean active,
            double hudX,
            double hudY,
            double hudScale,
            String name) {
    }

    public static final Type<SonarSettingsSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "sonar_settings_sync")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, SonarSettingsSyncPayload> STREAM_CODEC =
            StreamCodec.of(SonarSettingsSyncPayload::write, SonarSettingsSyncPayload::read);

    /** Sets one answer carries at most. */
    public static final int MAX_SONARS = 8;

    /** The answer for a computer with no set wired to it: still what it has on file. */
    public static SonarSettingsSyncPayload none(TorpedoSettingsStore.Entry torpedo, Select select) {
        return new SonarSettingsSyncPayload(
                false, torpedo.holdDepth(), torpedo.depth(), torpedo.activation(), select, List.of());
    }

    private static void write(RegistryFriendlyByteBuf buffer, SonarSettingsSyncPayload payload) {
        buffer.writeBoolean(payload.bound);
        buffer.writeBoolean(payload.torpedoHoldDepth);
        buffer.writeDouble(payload.torpedoDepth);
        buffer.writeDouble(payload.torpedoActivation);
        Select select = payload.select == null ? Select.none() : payload.select;
        buffer.writeUtf(select.first(), 64);
        buffer.writeUtf(select.second(), 64);
        buffer.writeUtf(select.normalKey(), 64);
        buffer.writeUtf(select.advancedKey(), 64);
        // The full-keyboard controller (火控学's WIRELESS channel, which also carries the mouse
        // wheel binds) is the fourth channel the one sonar action answers to.
        buffer.writeUtf(select.wirelessKey(), 64);
        int count = Math.min(payload.sonars.size(), MAX_SONARS);
        buffer.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            SonarSettingsSyncPayload.Entry entry = payload.sonars.get(index);
            buffer.writeLong(entry.pos());
            buffer.writeBoolean(entry.subLevel() != null);
            if (entry.subLevel() != null) {
                buffer.writeUUID(entry.subLevel());
            }
            buffer.writeDouble(entry.range());
            buffer.writeDouble(entry.halfAngle());
            buffer.writeDouble(entry.elevationUp());
            buffer.writeDouble(entry.elevationDown());
            buffer.writeBoolean(entry.active());
            buffer.writeDouble(entry.hudX());
            buffer.writeDouble(entry.hudY());
            buffer.writeDouble(entry.hudScale());
            buffer.writeUtf(entry.name() == null ? "" : entry.name(), 32);
        }
    }

    private static SonarSettingsSyncPayload read(RegistryFriendlyByteBuf buffer) {
        boolean bound = buffer.readBoolean();
        boolean torpedoHoldDepth = buffer.readBoolean();
        double torpedoDepth = buffer.readDouble();
        double torpedoActivation = buffer.readDouble();
        Select select = new Select(buffer.readUtf(64), buffer.readUtf(64), buffer.readUtf(64),
                buffer.readUtf(64), buffer.readUtf(64));
        int count = Math.max(0, Math.min(buffer.readVarInt(), MAX_SONARS));
        List<SonarSettingsSyncPayload.Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long pos = buffer.readLong();
            UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
            entries.add(new SonarSettingsSyncPayload.Entry(
                    pos, subLevel,
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readUtf(32)));
        }
        return new SonarSettingsSyncPayload(
                bound, torpedoHoldDepth, torpedoDepth, torpedoActivation, select, entries);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, SonarSettingsSyncPayload::handleClient);
    }

    public static void handleClient(SonarSettingsSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            TorpedoSettingsState.accept(
                    payload.torpedoHoldDepth(), payload.torpedoDepth(), payload.torpedoActivation());
            Select select = payload.select() == null ? Select.none() : payload.select();
            SonarSelectInputState.accept(select.first(), select.second(), select.normalKey(),
                    select.advancedKey(), select.wirelessKey());
            SonarSettingsState.accept(payload.bound(), payload.sonars());
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
