package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.client.OpticalLockFollowState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client flag telling the gunner whether the controller's current
 * optical lock is a main-world block lock, so the HUD can switch the lock
 * reticle to the block-lock style.
 */
public record OpticalBlockLockPayload(boolean blockLock) implements CustomPacketPayload {

    public static final Type<OpticalBlockLockPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "optical_block_lock")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, OpticalBlockLockPayload> STREAM_CODEC =
            StreamCodec.of(OpticalBlockLockPayload::write, OpticalBlockLockPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, OpticalBlockLockPayload payload) {
        buffer.writeBoolean(payload.blockLock);
    }

    private static OpticalBlockLockPayload read(RegistryFriendlyByteBuf buffer) {
        return new OpticalBlockLockPayload(buffer.readBoolean());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(TYPE, STREAM_CODEC, OpticalBlockLockPayload::handleClient);
    }

    public static void handleClient(OpticalBlockLockPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> OpticalLockFollowState.applyBlockLock(payload.blockLock));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
