package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.client.SonarHudState;
import com.qkdream.firecontrolcompat.sonar.SonarKind;
import com.qkdream.firecontrolcompat.sonar.SonarWeight;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client sonar picture for every set wired to the station being
 * operated: where each dish is, where it looks, the window it listens through,
 * where its picture belongs on the screen, and what it has heard. Marks travel
 * as a weight and a position - the picture colours them, so nothing else has to.
 *
 * <p>The picture also carries the one mark the operator has picked, so the
 * screen can put the bars around it. The pick is filed on the computer rather
 * than on a set, so it travels beside the panels instead of inside one of them,
 * and a set that cannot hear the mark simply draws no bars from it.</p>
 *
 * <p>What was picked is named as well - what it sounded like - so the screen can
 * say what the bars are around.</p>
 */
public record SonarSessionPayload(
        boolean active,
        Vec3 lock,
        SonarKind lockKind,
        List<SonarSessionPayload.Panel> panels)
        implements CustomPacketPayload {

    /** One set's picture: the dish, its window, its place on the screen and its marks. */
    public record Panel(
            Vec3 origin,
            Vec3 forward,
            double range,
            double halfAngle,
            double elevationUp,
            double elevationDown,
            boolean pinging,
            double hudX,
            double hudY,
            double hudScale,
            String name,
            List<SonarSessionPayload.Mark> marks) {
    }

    /** One mark on the picture: what was heard, how loud, how strong, and where. */
    public record Mark(SonarKind kind, SonarWeight weight, int echo, Vec3 position) {
    }

    public static final Type<SonarSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "sonar_session")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, SonarSessionPayload> STREAM_CODEC =
            StreamCodec.of(SonarSessionPayload::write, SonarSessionPayload::read);

    /** Sets one picture can carry at most. */
    public static final int MAX_PANELS = 6;
    /** Marks a single set carries at most. */
    public static final int MAX_MARKS_PER_PANEL = 32;
    /** Marks a whole packet carries at most, so a wall of sets stays affordable. */
    public static final int MAX_MARKS_TOTAL = 128;

    /** The answer for a station with no set to show. */
    public static SonarSessionPayload inactive() {
        return new SonarSessionPayload(false, null, null, List.of());
    }

    private static void write(RegistryFriendlyByteBuf buffer, SonarSessionPayload payload) {
        buffer.writeBoolean(payload.active);
        Vec3 lock = payload.lock;
        buffer.writeBoolean(lock != null);
        if (lock != null) {
            buffer.writeFloat((float) lock.x);
            buffer.writeFloat((float) lock.y);
            buffer.writeFloat((float) lock.z);
        }
        buffer.writeByte(lock == null || payload.lockKind == null ? -1 : payload.lockKind.ordinal());
        int panels = Math.min(payload.panels.size(), MAX_PANELS);
        buffer.writeVarInt(panels);
        int budget = MAX_MARKS_TOTAL;
        for (int index = 0; index < panels; index++) {
            SonarSessionPayload.Panel panel = payload.panels.get(index);
            buffer.writeDouble(panel.origin().x);
            buffer.writeDouble(panel.origin().y);
            buffer.writeDouble(panel.origin().z);
            buffer.writeFloat((float) panel.forward().x);
            buffer.writeFloat((float) panel.forward().y);
            buffer.writeFloat((float) panel.forward().z);
            buffer.writeDouble(panel.range());
            buffer.writeFloat((float) panel.halfAngle());
            buffer.writeFloat((float) panel.elevationUp());
            buffer.writeFloat((float) panel.elevationDown());
            buffer.writeBoolean(panel.pinging());
            buffer.writeFloat((float) panel.hudX());
            buffer.writeFloat((float) panel.hudY());
            buffer.writeFloat((float) panel.hudScale());
            buffer.writeUtf(panel.name() == null ? "" : panel.name(), 32);
            int count = Math.max(0, Math.min(panel.marks().size(), Math.min(MAX_MARKS_PER_PANEL, budget)));
            budget -= count;
            buffer.writeVarInt(count);
            for (int mark = 0; mark < count; mark++) {
                SonarSessionPayload.Mark entry = panel.marks().get(mark);
                Vec3 position = entry.position();
                buffer.writeByte(entry.weight().ordinal());
                buffer.writeByte(entry.kind().ordinal());
                buffer.writeByte(entry.echo());
                buffer.writeFloat((float) position.x);
                buffer.writeFloat((float) position.y);
                buffer.writeFloat((float) position.z);
            }
        }
    }

    private static SonarSessionPayload read(RegistryFriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        Vec3 lock = buffer.readBoolean()
                ? new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat())
                : null;
        int kindOrdinal = buffer.readByte();
        SonarKind lockKind = lock == null || kindOrdinal < 0 || kindOrdinal >= SonarKind.values().length
                ? null
                : SonarKind.byOrdinal(kindOrdinal);
        int panels = Math.max(0, Math.min(buffer.readVarInt(), MAX_PANELS));
        List<SonarSessionPayload.Panel> received = new ArrayList<>(panels);
        for (int index = 0; index < panels; index++) {
            Vec3 origin = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            Vec3 forward = new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
            double range = buffer.readDouble();
            double halfAngle = buffer.readFloat();
            double elevationUp = buffer.readFloat();
            double elevationDown = buffer.readFloat();
            boolean pinging = buffer.readBoolean();
            double hudX = buffer.readFloat();
            double hudY = buffer.readFloat();
            double hudScale = buffer.readFloat();
            String name = buffer.readUtf(32);
            int count = Math.max(0, Math.min(buffer.readVarInt(), MAX_MARKS_PER_PANEL));
            List<SonarSessionPayload.Mark> marks = new ArrayList<>(count);
            for (int mark = 0; mark < count; mark++) {
                SonarWeight weight = SonarWeight.byOrdinal(buffer.readByte());
                SonarKind kind = SonarKind.byOrdinal(buffer.readByte());
                int echo = Math.max(0, buffer.readByte());
                marks.add(new SonarSessionPayload.Mark(
                        kind, weight, echo, new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat())));
            }
            received.add(new SonarSessionPayload.Panel(
                    origin, forward, range, halfAngle, elevationUp, elevationDown,
                    pinging, hudX, hudY, hudScale, name, marks));
        }
        return new SonarSessionPayload(active, lock, lockKind, received);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, SonarSessionPayload::handleClient);
    }

    public static void handleClient(SonarSessionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SonarHudState.accept(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
