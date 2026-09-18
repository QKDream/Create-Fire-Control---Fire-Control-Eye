package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.iff.IffBand;
import com.qkdream.firecontrolcompat.iff.IffTransponderBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client notice telling the fire control screen whether the computer
 * it belongs to has an IFF transponder wired to it, and whether that
 * transponder already carries a complete frequency band. The screen therefore
 * only offers the identity section once a transponder is connected.
 */
public record IffStatusPayload(boolean linked, boolean bandSet) implements CustomPacketPayload {

    public static final Type<IffStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "iff_status")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, IffStatusPayload> STREAM_CODEC = StreamCodec.of(
            IffStatusPayload::write,
            IffStatusPayload::read
    );

    private static volatile boolean linkedState;
    private static volatile boolean bandSetState;

    private static void write(RegistryFriendlyByteBuf buffer, IffStatusPayload payload) {
        buffer.writeBoolean(payload.linked);
        buffer.writeBoolean(payload.bandSet);
    }

    private static IffStatusPayload read(RegistryFriendlyByteBuf buffer) {
        return new IffStatusPayload(buffer.readBoolean(), buffer.readBoolean());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, IffStatusPayload::handle);
    }

    public static void handle(IffStatusPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            linkedState = payload.linked;
            bandSetState = payload.bandSet;
        });
    }

    /** Whether the computer whose screen is open currently has a transponder. */
    public static boolean isLinked() {
        return linkedState;
    }

    /** Whether that transponder already carries two frequency items. */
    public static boolean isBandSet() {
        return bandSetState;
    }

    public static void send(
            ServerPlayer player, ServerLevel level, BlockPos controllerPos, UUID controllerSubLevel) {
        IffTransponderBlockEntity transponder =
                IffTransponderBlockEntity.findForController(level, controllerPos, controllerSubLevel);
        boolean hasTransponder = transponder != null;
        boolean complete = false;
        if (hasTransponder) {
            IffBand band = transponder.band();
            complete = band.valid();
        }
        PacketDistributor.sendToPlayer(player, new IffStatusPayload(hasTransponder, complete), new CustomPacketPayload[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}