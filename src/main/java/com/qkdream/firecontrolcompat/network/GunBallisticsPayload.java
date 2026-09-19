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
 * Server -> client copy of the bound cannon's ballistic profile.
 *
 * <p>The lead indicator has to know how hard the shell drops, and the client
 * cannot work that out on its own: the profile depends on the loaded
 * projectile, the propellant charge and the barrel length, all of which only
 * the server can inspect. The values come from the fire control mod's own
 * cannon analysis, so the indicator agrees with the mod's automatic aim.</p>
 *
 * <p>A payload with {@code valid = false} clears the client copy and sends the
 * indicator back to its straight-line fallback.</p>
 */
public record GunBallisticsPayload(
        boolean valid,
        double muzzleSpeed,
        double gravity,
        double drag,
        boolean quadraticDrag,
        int maxFlightTicks,
        Vec3 carrierVelocity) implements CustomPacketPayload {

    public static final Type<GunBallisticsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "gun_ballistics")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, GunBallisticsPayload> STREAM_CODEC =
            StreamCodec.of(GunBallisticsPayload::write, GunBallisticsPayload::read);

    /** Age limit of the cached profile; the server refreshes it twice a second. */
    private static final long CACHE_TTL_MILLIS = 4000L;

    private static volatile boolean cachedValid;

    private static volatile double cachedMuzzleSpeed;

    private static volatile double cachedGravity;

    private static volatile double cachedDrag;

    private static volatile boolean cachedQuadraticDrag;

    private static volatile int cachedMaxFlightTicks;

    private static volatile Vec3 cachedCarrierVelocity = Vec3.ZERO;

    private static volatile long cachedAtMillis;

    private static void write(RegistryFriendlyByteBuf buffer, GunBallisticsPayload payload) {
        buffer.writeBoolean(payload.valid);
        buffer.writeDouble(payload.muzzleSpeed);
        buffer.writeDouble(payload.gravity);
        buffer.writeDouble(payload.drag);
        buffer.writeBoolean(payload.quadraticDrag);
        buffer.writeVarInt(payload.maxFlightTicks);
        Vec3 carrier = payload.carrierVelocity == null ? Vec3.ZERO : payload.carrierVelocity;
        buffer.writeDouble(carrier.x);
        buffer.writeDouble(carrier.y);
        buffer.writeDouble(carrier.z);
    }

    private static GunBallisticsPayload read(RegistryFriendlyByteBuf buffer) {
        return new GunBallisticsPayload(
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, GunBallisticsPayload::handle);
    }

    public static void handle(GunBallisticsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            cachedValid = payload.valid;
            cachedMuzzleSpeed = payload.muzzleSpeed;
            cachedGravity = payload.gravity;
            cachedDrag = payload.drag;
            cachedQuadraticDrag = payload.quadraticDrag;
            cachedMaxFlightTicks = payload.maxFlightTicks;
            cachedCarrierVelocity = payload.carrierVelocity == null ? Vec3.ZERO : payload.carrierVelocity;
            cachedAtMillis = System.currentTimeMillis();
        });
    }

    /** Clears the client copy, used when no ballistic profile applies. */
    public static void clear() {
        cachedValid = false;
        cachedAtMillis = System.currentTimeMillis();
    }

    /** True while a usable cannon profile is cached. */
    public static boolean isUsable() {
        return cachedValid && System.currentTimeMillis() - cachedAtMillis < CACHE_TTL_MILLIS;
    }

    public static double speed() {
        return cachedMuzzleSpeed;
    }

    public static double drop() {
        return cachedGravity;
    }

    public static double airDrag() {
        return cachedDrag;
    }

    public static boolean squaredDrag() {
        return cachedQuadraticDrag;
    }

    public static int flightTickLimit() {
        return cachedMaxFlightTicks;
    }

    public static Vec3 platformVelocity() {
        return cachedCarrierVelocity;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}