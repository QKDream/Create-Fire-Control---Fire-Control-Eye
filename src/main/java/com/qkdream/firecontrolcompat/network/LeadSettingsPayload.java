package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server channel carrying the lead settings edited in the fire
 * control computer. The server applies them and echoes the values back to
 * every client through {@link LeadSettingsSyncPayload}.
 */
public record LeadSettingsPayload(
        boolean gunLead,
        boolean verticalLaunch,
        /**
         * Unused since 1.70: the settings page no longer carries a proximity range row
         * and the beam rider fuses on its own fixed radius. The field stays in the codec
         * so the payload keeps its wire shape.
         */
        double proximityRange,
        boolean speedGate,
        double speedGateSpeed,
        boolean imageLock,
        boolean torpedoHoldDepth,
        double torpedoDepth,
        double torpedoActivation)
        implements CustomPacketPayload {

    public static final Type<LeadSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "lead_settings")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LeadSettingsPayload> STREAM_CODEC =
            StreamCodec.of(LeadSettingsPayload::write, LeadSettingsPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, LeadSettingsPayload payload) {
        buffer.writeBoolean(payload.gunLead);
        buffer.writeBoolean(payload.verticalLaunch);
        // Kept on the wire although inert, so two builds stay wire compatible.
        buffer.writeDouble(payload.proximityRange);
        buffer.writeBoolean(payload.speedGate);
        buffer.writeDouble(payload.speedGateSpeed);
        buffer.writeBoolean(payload.imageLock);
        buffer.writeBoolean(payload.torpedoHoldDepth);
        buffer.writeDouble(payload.torpedoDepth);
        buffer.writeDouble(payload.torpedoActivation);
    }

    private static LeadSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        return new LeadSettingsPayload(
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readDouble());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, LeadSettingsPayload::handleServer);
    }

    public static void handleServer(LeadSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FireControlLeadSettings.apply(
                    payload.gunLead,
                    payload.verticalLaunch,
                    payload.proximityRange,
                    payload.speedGate,
                    payload.speedGateSpeed,
                    payload.imageLock,
                    payload.torpedoHoldDepth,
                    payload.torpedoDepth,
                    payload.torpedoActivation);
            PacketDistributor.sendToAllPlayers(new LeadSettingsSyncPayload(
                    payload.gunLead,
                    payload.verticalLaunch,
                    payload.proximityRange,
                    payload.speedGate,
                    payload.speedGateSpeed,
                    payload.imageLock,
                    payload.torpedoHoldDepth,
                    payload.torpedoDepth,
                    payload.torpedoActivation));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
