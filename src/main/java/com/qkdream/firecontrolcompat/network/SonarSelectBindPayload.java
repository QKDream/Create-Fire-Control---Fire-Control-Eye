package com.qkdream.firecontrolcompat.network;

import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.qkdream.firecontrolcompat.sonar.SonarSelectInputStore;
import com.qkdream.firecontrolcompat.sonar.SonarSettings;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server: the target select page binds one of the four inputs the
 * sonar pick answers to on the computer the page was opened from.
 *
 * <p>A wireless half is bound from what the player is holding, the way the
 * computer's own frequency buttons work: the page only says which half, and the
 * item in hand is what gets filed. A remote key is bound from the signal the
 * page captured, an empty one clearing it. The sonar page's own answer is sent
 * straight back, which is where the page reads the filed bindings from.</p>
 */
public record SonarSelectBindPayload(
        BlockPos controllerPos,
        UUID controllerSubLevel,
        int slot,
        String key) implements CustomPacketPayload {

    /** The first half of the wireless pair. */
    public static final int SLOT_WIRELESS_FIRST = 0;
    /** The second half of the wireless pair. */
    public static final int SLOT_WIRELESS_SECOND = 1;
    /** The key the ordinary remote controller answers to. */
    public static final int SLOT_REMOTE_NORMAL = 2;
    /** The key the advanced remote controller answers to. */
    public static final int SLOT_REMOTE_ADVANCED = 3;
    /**
     * The key the full-keyboard wireless controller answers to - 火控学's own
     * {@code RemoteControllerCompat.Type.WIRELESS} channel, the one whose binds are a whole
     * keyboard (and the mouse wheel since 0.9.3) rather than a controller's button list.
     */
    public static final int SLOT_REMOTE_WIRELESS = 4;
    /** Asks for the filed bindings and changes nothing, so a page can draw them. */
    public static final int SLOT_REFRESH = 5;

    public static final Type<SonarSelectBindPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "sonar_select_bind")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, SonarSelectBindPayload> STREAM_CODEC =
            StreamCodec.of(SonarSelectBindPayload::write, SonarSelectBindPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, SonarSelectBindPayload payload) {
        buffer.writeBlockPos(payload.controllerPos);
        buffer.writeBoolean(payload.controllerSubLevel != null);
        if (payload.controllerSubLevel != null) {
            buffer.writeUUID(payload.controllerSubLevel);
        }
        buffer.writeVarInt(payload.slot);
        buffer.writeUtf(payload.key == null ? "" : payload.key, 64);
    }

    private static SonarSelectBindPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos controllerPos = buffer.readBlockPos();
        UUID controllerSubLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        return new SonarSelectBindPayload(
                controllerPos, controllerSubLevel, buffer.readVarInt(), buffer.readUtf(64));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, SonarSelectBindPayload::handleServer);
    }

    public static void handleServer(SonarSelectBindPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            switch (payload.slot) {
                // Both halves are filed the way 火控学 files its own frequencies - one slot at a
                // time, from the item in the player's hand (SetWirelessFrequencyPayload does exactly
                // this on the pack's own input page). The page used to bind both halves from the two
                // hands at once, which is a rule of this mod's own and left the second half nearly
                // unbindable; an empty hand now clears the half that was pressed.
                case SLOT_WIRELESS_FIRST -> SonarSelectInputStore.setWireless(
                        player, payload.controllerPos, payload.controllerSubLevel, true, player.getMainHandItem());
                case SLOT_WIRELESS_SECOND -> SonarSelectInputStore.setWireless(
                        player, payload.controllerPos, payload.controllerSubLevel, false, player.getMainHandItem());
                case SLOT_REMOTE_NORMAL -> SonarSelectInputStore.setRemoteKey(
                        player, payload.controllerPos, payload.controllerSubLevel,
                        RemoteControllerCompat.Type.NORMAL, payload.key);
                case SLOT_REMOTE_ADVANCED -> SonarSelectInputStore.setRemoteKey(
                        player, payload.controllerPos, payload.controllerSubLevel,
                        RemoteControllerCompat.Type.ADVANCED, payload.key);
                case SLOT_REMOTE_WIRELESS -> SonarSelectInputStore.setRemoteKey(
                        player, payload.controllerPos, payload.controllerSubLevel,
                        RemoteControllerCompat.Type.WIRELESS, payload.key);
                default -> {
                }
            }
            SonarSettings.sendStatus(player, payload.controllerPos, payload.controllerSubLevel);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
