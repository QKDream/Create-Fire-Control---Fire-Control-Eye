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
        boolean gunLead, boolean verticalLaunch, double proximityRange)
        implements CustomPacketPayload {

    public static final Type<LeadSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "lead_settings")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LeadSettingsPayload> STREAM_CODEC =
            StreamCodec.of(LeadSettingsPayload::write, LeadSettingsPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, LeadSettingsPayload payload) {
        buffer.writeBoolean(payload.gunLead);
        buffer.writeBoolean(payload.verticalLaunch);
        buffer.writeDouble(payload.proximityRange);
    }

    private static LeadSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        return new LeadSettingsPayload(
                buffer.readBoolean(), buffer.readBoolean(), buffer.readDouble());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, LeadSettingsPayload::handleServer);
    }

    public static void handleServer(LeadSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FireControlLeadSettings.apply(
                    payload.gunLead, payload.verticalLaunch, payload.proximityRange);
            PacketDistributor.sendToAllPlayers(new LeadSettingsSyncPayload(
                    payload.gunLead, payload.verticalLaunch, payload.proximityRange));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
