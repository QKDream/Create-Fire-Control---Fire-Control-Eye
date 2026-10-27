package com.qkdream.firecontrolcompat.network;

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
 * Client -> server: the settings page lands the values it holds for one set on
 * that set alone, so a computer running several dishes keeps them apart.
 */
public record SonarSettingsPayload(
        BlockPos controllerPos,
        UUID controllerSubLevel,
        long sonarPos,
        UUID sonarSubLevel,
        double range,
        double halfAngle,
        double elevationUp,
        double elevationDown,
        boolean active,
        double hudX,
        double hudY,
        double hudScale,
        String name) implements CustomPacketPayload {

    public static final Type<SonarSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "sonar_settings")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, SonarSettingsPayload> STREAM_CODEC =
            StreamCodec.of(SonarSettingsPayload::write, SonarSettingsPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, SonarSettingsPayload payload) {
        buffer.writeBlockPos(payload.controllerPos);
        buffer.writeBoolean(payload.controllerSubLevel != null);
        if (payload.controllerSubLevel != null) {
            buffer.writeUUID(payload.controllerSubLevel);
        }
        buffer.writeLong(payload.sonarPos);
        buffer.writeBoolean(payload.sonarSubLevel != null);
        if (payload.sonarSubLevel != null) {
            buffer.writeUUID(payload.sonarSubLevel);
        }
        buffer.writeDouble(payload.range);
        buffer.writeDouble(payload.halfAngle);
        buffer.writeDouble(payload.elevationUp);
        buffer.writeDouble(payload.elevationDown);
        buffer.writeBoolean(payload.active);
        buffer.writeDouble(payload.hudX);
        buffer.writeDouble(payload.hudY);
        buffer.writeDouble(payload.hudScale);
        buffer.writeUtf(payload.name == null ? "" : payload.name, 32);
    }

    private static SonarSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos controllerPos = buffer.readBlockPos();
        UUID controllerSubLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        long sonarPos = buffer.readLong();
        UUID sonarSubLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        return new SonarSettingsPayload(
                controllerPos, controllerSubLevel, sonarPos, sonarSubLevel,
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readUtf(32));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, SonarSettingsPayload::handleServer);
    }

    public static void handleServer(SonarSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            SonarSettings.apply(
                    player, payload.controllerPos, payload.controllerSubLevel,
                    payload.sonarPos, payload.sonarSubLevel,
                    payload.range, payload.halfAngle, payload.elevationUp, payload.elevationDown,
                    payload.active, payload.hudX, payload.hudY, payload.hudScale, payload.name);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
