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
 * frame. Ground flag: a bound launcher carries infrared missiles. Aircraft
 * flag: the selected aircraft weapon is the infrared missile rack.
 *
 * <p>Once the trigger has opened a seeker, {@code searchActive} is set for the
 * ten seconds the seeker watches and {@code lockedTarget} carries the contact it
 * settled on, so the HUD can square it with the small lock frame.</p>
 */
public record SeekerHudPayload(
        boolean groundIrReady,
        boolean aircraftIrSelected,
        String aircraftGroundName,
        String groundWeaponName,
        boolean searchActive,
        Vec3 origin,
        Vec3 direction,
        Vec3 lockedTarget,
        Vec3 frameAnchor) implements CustomPacketPayload {

    public static final Type<SeekerHudPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "seeker_hud")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, SeekerHudPayload> STREAM_CODEC = StreamCodec.of(
            SeekerHudPayload::write,
            SeekerHudPayload::read
    );

    private static volatile boolean groundReady;
    private static volatile boolean aircraftSelected;
    private static volatile String aircraftGround;
    private static volatile String groundWeapon;
    private static volatile boolean searching;
    private static volatile Vec3 cachedOrigin;
    private static volatile Vec3 cachedDirection;
    private static volatile Vec3 cachedLock;
    private static volatile Vec3 cachedAnchor;
    private static volatile long updatedTick = Long.MIN_VALUE;

    private static void write(RegistryFriendlyByteBuf buffer, SeekerHudPayload payload) {
        buffer.writeBoolean(payload.groundIrReady);
        buffer.writeBoolean(payload.aircraftIrSelected);
        buffer.writeUtf(payload.aircraftGroundName == null ? "" : payload.aircraftGroundName, 32);
        buffer.writeUtf(payload.groundWeaponName == null ? "" : payload.groundWeaponName, 32);
        buffer.writeBoolean(payload.searchActive);
        writeVector(buffer, payload.origin);
        writeVector(buffer, payload.direction);
        writeVector(buffer, payload.lockedTarget);
        writeVector(buffer, payload.frameAnchor);
    }

    private static void writeVector(RegistryFriendlyByteBuf buffer, Vec3 vector) {
        buffer.writeBoolean(vector != null);
        if (vector != null) {
            buffer.writeDouble(vector.x);
            buffer.writeDouble(vector.y);
            buffer.writeDouble(vector.z);
        }
    }

    private static Vec3 readVector(RegistryFriendlyByteBuf buffer) {
        return buffer.readBoolean() ? new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()) : null;
    }

    private static SeekerHudPayload read(RegistryFriendlyByteBuf buffer) {
        boolean ground = buffer.readBoolean();
        boolean aircraft = buffer.readBoolean();
        String aircraftGroundName = buffer.readUtf(32);
        String groundWeaponName = buffer.readUtf(32);
        boolean active = buffer.readBoolean();
        return new SeekerHudPayload(ground, aircraft, aircraftGroundName.isEmpty() ? null : aircraftGroundName,
                groundWeaponName.isEmpty() ? null : groundWeaponName,
                active, readVector(buffer), readVector(buffer),
                readVector(buffer), readVector(buffer));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, SeekerHudPayload::handle);
    }

    public static void handle(SeekerHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            groundReady = payload.groundIrReady;
            aircraftSelected = payload.aircraftIrSelected;
            aircraftGround = payload.aircraftGroundName;
            groundWeapon = payload.groundWeaponName;
            searching = payload.searchActive;
            cachedOrigin = payload.origin;
            cachedDirection = payload.direction;
            cachedLock = payload.lockedTarget;
            cachedAnchor = payload.frameAnchor;
            updatedTick = System.currentTimeMillis();
        });
    }

    /** Whether the seeker range frame is fresh enough to display. */
    public static boolean isFresh(long nowMillis) {
        return updatedTick != Long.MIN_VALUE && nowMillis - updatedTick < 1000L;
    }

    /**
     * Whether the last reported weapon-slot name is recent enough to label a row with.
     *
     * <p>A much longer window than {@link #isFresh}: the frames must vanish with the seeker, but
     * the <em>name</em> of what is on a rail must not. The payload is only sent while the player is
     * seated in a fire-control vehicle, so a crew that steps out to open the input page stops
     * receiving it - with the one-second window the row snapped back to the pack's stock wording
     * while the anti-radiation round was still on the rail. Ten seconds covers any menu visit and
     * still expires on its own once the player walks away from the aircraft.</p>
     */
    public static boolean isNameFresh(long nowMillis) {
        return updatedTick != Long.MIN_VALUE && nowMillis - updatedTick < 10000L;
    }

    public static boolean isGroundIrReady() {
        return groundReady;
    }

    public static boolean isAircraftIrSelected() {
        return aircraftSelected;
    }

    /**
     * Whether the selected aircraft weapon is 面包学's cruise rail, so the HUD
     * names that slot after the round on it instead of the air-to-ground one.
     */
    /** The name the air-to-ground line of the HUD reads, or null for the stock one. */
    public static String groundSlotName() {
        return aircraftGround;
    }

    /**
     * The name the <b>ground</b> weapon line reads while an anti-tank launcher holds this mod's round,
     * or null to keep 面包学/火控学's own wording.
     *
     * <p>0.9.4 names its three ground slots from {@code FireControlWeaponInputs.GroundWeapon
     * .displayName()} - "反坦克导弹", "防空导弹", "高速防空导弹" - which says what the launcher is, not
     * what is loaded in it. The infrared anti-tank round is a different weapon from the pack's own
     * anti-tank missile, so the slot is named after it while <em>and only while</em> a bound launcher
     * is holding it. A launcher with a native round on it keeps the pack's wording, and a computer
     * with no such launcher bound keeps it too.</p>
     */
    public static String groundWeaponSlotName() {
        return groundWeapon;
    }

    /** Whether a trigger press already opened the seeker and it is still watching. */
    public static boolean isSearchActive() {
        return searching;
    }

    /**
     * Where the cone frames are centred: the contact the fire control radar holds
     * while the head is slaved to one, otherwise null so the frames fall back to
     * the launch line.
     */
    public static Vec3 frameAnchorPoint() {
        return cachedAnchor;
    }

    /** The contact the open seeker has locked, or null while it has none. */
    public static Vec3 lockedTargetPosition() {
        return cachedLock;
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



