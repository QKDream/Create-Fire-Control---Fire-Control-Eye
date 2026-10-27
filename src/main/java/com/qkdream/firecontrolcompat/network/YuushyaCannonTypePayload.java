package com.qkdream.firecontrolcompat.network;

import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaBigCannonCompat;
import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaCannonType;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server cannon type of the modelling block whose screen is open.
 * The server converts the block in place, keeping its model and layers. The
 * cannon types are shared with the fire control mod, so the same button can
 * pick its medium barrel and the big cannon barrel of this mod.
 */
public record YuushyaCannonTypePayload(BlockPos pos, int cannonType) implements CustomPacketPayload {

    public static final Type<YuushyaCannonTypePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "yuushya_cannon_type")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, YuushyaCannonTypePayload> STREAM_CODEC =
            StreamCodec.of(YuushyaCannonTypePayload::write, YuushyaCannonTypePayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, YuushyaCannonTypePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeVarInt(payload.cannonType);
    }

    private static YuushyaCannonTypePayload read(RegistryFriendlyByteBuf buffer) {
        return new YuushyaCannonTypePayload(buffer.readBlockPos(), buffer.readVarInt());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, YuushyaCannonTypePayload::handleServer);
    }

    public static void handleServer(YuushyaCannonTypePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!YuushyaBigCannonCompat.isAvailable()
                    || !player.canInteractWithBlock(payload.pos, 1.0)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            YuushyaBigCannonCompat.setCannonType(level, payload.pos, YuushyaCannonType.byOrdinal(payload.cannonType));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}