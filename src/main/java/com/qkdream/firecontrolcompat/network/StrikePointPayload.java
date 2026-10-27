package com.qkdream.firecontrolcompat.network;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.StrikePoints;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Client -> server: the strike board tells the computer it is bound to where to
 * strike or forgets the place.
 */
public record StrikePointPayload(
        BindingRef controller,
        int action,
        double x,
        double y,
        double z) implements CustomPacketPayload {

    /** Writes the marker on the ground. */
    public static final int ACTION_SET = 0;
    /** Takes the marker off. */
    public static final int ACTION_CLEAR = 1;

    public static final Type<StrikePointPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "strike_point"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StrikePointPayload> STREAM_CODEC =
            StreamCodec.of(StrikePointPayload::write, StrikePointPayload::read);


    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, StrikePointPayload payload) {
        buffer.writeNbt(payload.controller == null ? new net.minecraft.nbt.CompoundTag() : payload.controller.save());
        buffer.writeVarInt(payload.action);
        buffer.writeDouble(payload.x);
        buffer.writeDouble(payload.y);
        buffer.writeDouble(payload.z);
    }

    private static StrikePointPayload read(RegistryFriendlyByteBuf buffer) {
        net.minecraft.nbt.CompoundTag tag = buffer.readNbt();
        BindingRef controller = tag == null || tag.isEmpty() ? null : BindingRef.loadTag(tag);
        return new StrikePointPayload(controller, buffer.readVarInt(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble());
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(TYPE, STREAM_CODEC, StrikePointPayload::handleServer);
    }

    public static void handleServer(StrikePointPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            BlockEntity blockEntity = resolve(player, payload.controller);
            if (!(blockEntity instanceof StabilizerControllerBlockEntity controller)) {
                return;
            }
            switch (payload.action) {
                case ACTION_SET -> {
                    StrikePoints.set(controller, new Vec3(payload.x, payload.y, payload.z));
                    FireControlCompat.LOGGER.info("[firecontrolcompat] strike point set controller={} at={},{},{}",
                            controller.getBlockPos(), (int) payload.x, (int) payload.y, (int) payload.z);
                }
                case ACTION_CLEAR -> {
                    StrikePoints.clear(controller);
                    FireControlCompat.LOGGER.info("[firecontrolcompat] strike point cleared controller={}",
                            controller.getBlockPos());
                }
                default -> {
                }
            }
        });
    }

    /** The computer a board is bound to, in the level the player is in. */
    private static BlockEntity resolve(ServerPlayer player, BindingRef ref) {
        if (ref == null) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        if (!ref.isInDimension(level)) {
            return null;
        }
        return ref.resolve(level);
    }

    /** Sent by the board when the player picks a square of the map. */
    public static void sendSet(BindingRef controller, BlockPos point) {
        PacketDistributor.sendToServer(new StrikePointPayload(controller, ACTION_SET,
                point.getX() + 0.5D, point.getY(), point.getZ() + 0.5D));
    }

    /** Sent by the board when the player takes the marker off. */
    public static void sendClear(BindingRef controller) {
        PacketDistributor.sendToServer(new StrikePointPayload(controller, ACTION_CLEAR, 0.0D, 0.0D, 0.0D));
    }

}
