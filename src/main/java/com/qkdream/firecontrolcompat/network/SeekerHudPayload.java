package com.qkdream.firecontrolcompat.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client channel telling the seated player whether an infrared
 * missile can be launched, so the HUD can draw the pre-launch seeker range
 * frame. Ground flag: a bound AA launcher carries infrared missiles.
 * Aircraft flag: the selected aircraft weapon is the infrared missile rack.
 */
public record SeekerHudPayload(
        boolean groundIrReady,
        boolean aircraftIrSelected,
        Vec3 origin,
        Vec3 direction) implements CustomPacketPayload {

    public static final Type<SeekerHudPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "seeker_hud")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, SeekerHudPayload> STREAM_CODEC = StreamCodec.of(
            SeekerHudPayload::write,
            SeekerHudPayload::read
    );

    private static volatile boolean groundReady;
    private static volatile boolean aircraftSelected;
    private static volatile Vec3 cachedOrigin;
    private static volatile Vec3 cachedDirection;
    private static volatile long updatedTick = Long.MIN_VALUE;

    private static void write(RegistryFriendlyByteBuf buffer, SeekerHudPayload payload) {
        buffer.writeBoolean(payload.groundIrReady);
        buffer.writeBoolean(payload.aircraftIrSelected);
        buffer.writeBoolean(payload.origin != null);
        if (payload.origin != null) {
            buffer.writeDouble(payload.origin.x);
            buffer.writeDouble(payload.origin.y);
            buffer.writeDouble(payload.origin.z);
        }
        buffer.writeBoolean(payload.direction != null);
        if (payload.direction != null) {
            buffer.writeDouble(payload.direction.x);
            buffer.writeDouble(payload.direction.y);
            buffer.writeDouble(payload.direction.z);
        }
    }

    private static SeekerHudPayload read(RegistryFriendlyByteBuf buffer) {
        boolean ground = buffer.readBoolean();
        boolean aircraft = buffer.readBoolean();
        boolean hasOrigin = buffer.readBoolean();
        Vec3 readOrigin = hasOrigin
                ? new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())
                : null;
        boolean hasDirection = buffer.readBoolean();
        Vec3 readDirection = hasDirection
                ? new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())
                : null;
        return new SeekerHudPayload(ground, aircraft, readOrigin, readDirection);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, SeekerHudPayload::handle);
    }

    public static void handle(SeekerHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            groundReady = payload.groundIrReady;
            aircraftSelected = payload.aircraftIrSelected;
            cachedOrigin = payload.origin;
            cachedDirection = payload.direction;
            updatedTick = System.currentTimeMillis();
        });
    }

    /** Whether the seeker range frame is fresh enough to display. */
    public static boolean isFresh(long nowMillis) {
        return nowMillis - updatedTick < 1000L;
    }

    public static boolean isGroundIrReady() {
        return groundReady;
    }

    public static boolean isAircraftIrSelected() {
        return aircraftSelected;
    }

    /** World-space launch geometry of the first ready launcher / rack, or null. */
    public static Vec3 launchOrigin() {
        return cachedOrigin;
    }

    public static Vec3 launchDirection() {
        return cachedDirection;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
