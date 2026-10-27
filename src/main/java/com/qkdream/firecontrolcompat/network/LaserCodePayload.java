package com.qkdream.firecontrolcompat.network;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.laser.LaserSpots;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server laser code for the computer whose settings screen is open.
 *
 * <p>The code is written on the computer itself and kept there, so it is part of
 * the machine rather than of any one round: a pod lights a spot only for rounds
 * carrying its own code, and a round takes the code of the computer that fired
 * it. Writing the same number on two computers is what lets one crew paint for
 * the other. A code of zero clears the written one and leaves the computer on the
 * code derived from where it stands.</p>
 */
public record LaserCodePayload(BlockPos pos, UUID subLevel, int code) implements CustomPacketPayload {

    public static final Type<LaserCodePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "laser_code")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, LaserCodePayload> STREAM_CODEC =
            StreamCodec.of(LaserCodePayload::write, LaserCodePayload::read);

    /** How near the named position the computer is looked for. */
    private static final double REACH = 6.0D;

    private static void write(RegistryFriendlyByteBuf buffer, LaserCodePayload payload) {
        buffer.writeBlockPos(payload.pos);
        buffer.writeBoolean(payload.subLevel != null);
        if (payload.subLevel != null) {
            buffer.writeUUID(payload.subLevel);
        }
        buffer.writeVarInt(payload.code);
    }

    private static LaserCodePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevel = buffer.readBoolean() ? buffer.readUUID() : null;
        return new LaserCodePayload(pos, subLevel, buffer.readVarInt());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, LaserCodePayload::handleServer);
    }

    public static void handleServer(LaserCodePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            StabilizerControllerBlockEntity controller = LaserSpots.nearestController(
                    player.serverLevel(), Vec3.atCenterOf(payload.pos()), REACH);
            if (controller == null) {
                return;
            }
            if (payload.code > 0) {
                controller.getPersistentData().putInt(LaserSpots.CODE_OVERRIDE_KEY, payload.code);
            } else {
                controller.getPersistentData().remove(LaserSpots.CODE_OVERRIDE_KEY);
            }
            controller.setChanged();
            FireControlCompat.LOGGER.info("[firecontrolcompat] laser code of computer at {} written as {}",
                    controller.getBlockPos(), LaserSpots.codeFor(controller));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
