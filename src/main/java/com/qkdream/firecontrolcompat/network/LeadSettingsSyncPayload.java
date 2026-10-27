package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client echo of the lead settings, keeping every client in sync
 * on dedicated servers. Runs on its own channel id so the server-bound and
 * client-bound directions never collide in the payload registry.
 */
public record LeadSettingsSyncPayload(
        boolean gunLead,
        boolean verticalLaunch,
        double proximityRange,
        boolean speedGate,
        double speedGateSpeed,
        boolean imageLock,
        boolean torpedoHoldDepth,
        double torpedoDepth,
        double torpedoActivation)
        implements CustomPacketPayload {

    public static final Type<LeadSettingsSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "lead_settings_sync")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LeadSettingsSyncPayload> STREAM_CODEC =
            StreamCodec.of(LeadSettingsSyncPayload::write, LeadSettingsSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, LeadSettingsSyncPayload payload) {
        buffer.writeBoolean(payload.gunLead);
        buffer.writeBoolean(payload.verticalLaunch);
        buffer.writeDouble(payload.proximityRange);
        buffer.writeBoolean(payload.speedGate);
        buffer.writeDouble(payload.speedGateSpeed);
        buffer.writeBoolean(payload.imageLock);
        buffer.writeBoolean(payload.torpedoHoldDepth);
        buffer.writeDouble(payload.torpedoDepth);
        buffer.writeDouble(payload.torpedoActivation);
    }

    private static LeadSettingsSyncPayload read(RegistryFriendlyByteBuf buffer) {
        return new LeadSettingsSyncPayload(
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
        registrar.playToClient(TYPE, STREAM_CODEC, LeadSettingsSyncPayload::handleClient);
    }

    public static void handleClient(LeadSettingsSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> FireControlLeadSettings.apply(
                payload.gunLead,
                payload.verticalLaunch,
                payload.proximityRange,
                payload.speedGate,
                payload.speedGateSpeed,
                payload.imageLock,
                payload.torpedoHoldDepth,
                payload.torpedoDepth,
                payload.torpedoActivation));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
