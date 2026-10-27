package com.qkdream.firecontrolcompat.underwater;

import com.cainiao1053.cbcmoreshells.munitions.big_cannon.FuzedTorpedoProjectileBlock;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.TorpedoProjectileBlock;
import java.util.Map;
import java.util.UUID;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.phys.Vec3;

/**
 * The air-dropped acoustic torpedo on 面包学's aircraft weapon rack.
 *
 * <p>Their rack keeps what is on it as a block, and one of their own rounds sits
 * on it under a code of its own: the visual guided round's rack block is what a
 * torpedo hanging on a rack looks like, so that is what the rail becomes, and
 * the round itself is remembered only by a mark in its block entity. The rail
 * then fires through their own launch procedure, which is where this mod takes
 * the shot over: the round that leaves is the acoustic one, and the rail goes
 * back to being an empty rack.</p>
 *
 * <p>The round's fittings are kept beside the mark for the same reason their
 * tubes keep them: a rack holds a code and nothing else, so a fuze fitted to the
 * round before it went on the rail would be lost on the way out, and a round
 * with no fuze never bursts.</p>
 */
public final class AirdroppedTorpedoRack {

    /** Whether a rail of theirs is carrying our round. */
    public static final String LOADED_KEY = "FCAirdropAcousticTorpedo";
    /** Who put it on the rail, read back by the launch. */
    private static final String OWNER_KEY = "belong";
    /** The round's fittings, kept beside the round while it waits on the rail. */
    private static final String FUZE_TAG = "fcairdroppedfuze";
    private static final String TRACER_TAG = "fcairdroppedtracer";
    /** Where the round leaves: the rack's own muzzle, one block out and two down. */
    private static final double MUZZLE_FORWARD = 0.6D;
    private static final double MUZZLE_DROP = 2.0D;
    /** The speed their own rack throws a round out at, in blocks a tick. */
    private static final float LAUNCH_SPEED = 1.0F;

    private AirdroppedTorpedoRack() {
    }

    /** Right-clicking a rack with the round in hand hangs it on the rail. */
    public static boolean tryLoad(LevelAccessor world, double x, double y, double z, Entity entity) {
        ItemStack held = held(entity);
        if (!isOurs(held)) {
            return false;
        }
        if (world == null) {
            return true;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world.isClientSide()) {
            return true;
        }
        if (world.getBlockState(pos).getBlock() != MianbaosModernwarfareModBlocks.RACK.get()) {
            message(entity, "只能装填到空挂架上");
            return true;
        }
        replaceBlockPreservingData(world, pos, MianbaosModernwarfareModBlocks.HIGHEXPLOSIVEVISUALGUIDEDTORPEDORACK.get());
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null && entity != null) {
            CompoundTag data = blockEntity.getPersistentData();
            data.putBoolean(LOADED_KEY, true);
            data.putString(OWNER_KEY, entity.getStringUUID());
            data.put(FUZE_TAG, FuzedTorpedoProjectileBlock.getFuzeFromItemStack(held)
                    .saveOptional(world.registryAccess()));
            data.put(TRACER_TAG, TorpedoProjectileBlock.getTracerFromItemStack(held)
                    .saveOptional(world.registryAccess()));
        }
        consume(entity, held);
        playSound(world, pos, "block.candle.place", SoundSource.BLOCKS, 1.0F, 1.0F);
        message(entity, "已装填空投被动声自导鱼雷");
        sendBlockUpdated(world, pos);
        return true;
    }

    /**
     * Their rack's own launch, taken over: the rail lets our round go instead of
     * the visual one, and is left empty and unmarked behind it.
     */
    public static boolean tryFire(LevelAccessor world, double x, double y, double z) {
        if (world == null || world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null || !blockEntity.getPersistentData().getBoolean(LOADED_KEY)) {
            return false;
        }
        if (!(world instanceof ServerLevel level)) {
            return false;
        }
        Direction facing = facing(world.getBlockState(pos));
        Vec3 muzzle = new Vec3(
                pos.getX() + 0.5D + facing.getStepX() * MUZZLE_FORWARD,
                pos.getY() - MUZZLE_DROP + facing.getStepY() * MUZZLE_FORWARD,
                pos.getZ() + 0.5D + facing.getStepZ() * MUZZLE_FORWARD);
        AcousticTorpedoProjectile torpedo = AcousticTorpedoes.AIRDROPPED_ACOUSTIC_TORPEDO_ENTITY.get().create(level);
        if (torpedo != null) {
            torpedo.setPos(muzzle.x, muzzle.y, muzzle.z);
            torpedo.setChargePower(0.0F);
            applyFittings(level, blockEntity, torpedo);
            torpedo.applyLaunchSettings(TorpedoLaunchSettings.forTube(level, blockEntity));
            Entity owner = owner(level, blockEntity.getPersistentData().getString(OWNER_KEY));
            if (owner != null) {
                torpedo.setOwner(owner);
            }
            torpedo.shoot(facing.getStepX(), facing.getStepY(), facing.getStepZ(), LAUNCH_SPEED, 0.0F);
            level.addFreshEntity(torpedo);
        }
        level.sendParticles(ParticleTypes.POOF, muzzle.x, muzzle.y, muzzle.z, 5, 0.5D, 0.5D, 0.5D, 0.15D);
        playSound(level, pos, "block.fire.extinguish", SoundSource.NEUTRAL, 3.0F, 0.8F);
        replaceBlockPreservingData(level, pos, MianbaosModernwarfareModBlocks.RACK.get(), true);
        sendBlockUpdated(level, pos);
        return true;
    }

    /** Hands the round the fuze and the tracer tip the rail kept for it. */
    private static void applyFittings(ServerLevel level, BlockEntity blockEntity, AcousticTorpedoProjectile torpedo) {
        CompoundTag data = blockEntity.getPersistentData();
        torpedo.setFuze(ItemStack.parseOptional(level.registryAccess(), data.getCompound(FUZE_TAG)));
        torpedo.setTracer(ItemStack.parseOptional(level.registryAccess(), data.getCompound(TRACER_TAG)));
    }

    /** Whether the round in hand is the air-dropped one. */
    private static boolean isOurs(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == AcousticTorpedoes.AIRDROPPED_ACOUSTIC_TORPEDO_ITEM.get();
    }

    private static ItemStack held(Entity entity) {
        return entity instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
    }

    private static void consume(Entity entity, ItemStack held) {
        if (entity instanceof Player player && !player.isCreative()) {
            player.getInventory().clearOrCountMatchingItems(
                    stack -> stack.getItem() == held.getItem(), 1, player.inventoryMenu.getCraftSlots());
        }
    }

    private static Entity owner(ServerLevel level, String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        try {
            return level.getEntity(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void message(Entity entity, String text) {
        if (entity instanceof Player player && !player.level().isClientSide()) {
            player.displayClientMessage(Component.literal(text), true);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Direction facing(BlockState state) {
        if (state.getBlock().getStateDefinition().getProperty("facing") instanceof DirectionProperty directionProperty) {
            return state.getValue(directionProperty);
        }
        if (state.getBlock().getStateDefinition().getProperty("axis") instanceof EnumProperty enumProperty) {
            Object axis = state.getValue(enumProperty);
            if (axis instanceof Axis value) {
                return Direction.fromAxisAndDirection(value, AxisDirection.POSITIVE);
            }
        }
        return Direction.NORTH;
    }

    private static void playSound(LevelAccessor world, BlockPos pos, String id, SoundSource source, float volume, float pitch) {
        if (world instanceof Level level) {
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(id));
            level.playSound(null, pos, event, source, volume, pitch);
        }
    }

    private static void sendBlockUpdated(LevelAccessor world, BlockPos pos) {
        if (world instanceof Level level) {
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    /** MCreator-style block swap that keeps the block entity data and state properties. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceBlockPreservingData(LevelAccessor world, BlockPos pos, Block targetBlock) {
        replaceBlockPreservingData(world, pos, targetBlock, false);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceBlockPreservingData(LevelAccessor world, BlockPos pos, Block targetBlock, boolean clearMark) {
        BlockState current = world.getBlockState(pos);
        BlockState target = targetBlock.defaultBlockState();
        for (Map.Entry<Property<?>, Comparable<?>> entry : current.getValues().entrySet()) {
            Property property = target.getBlock().getStateDefinition().getProperty(entry.getKey().getName());
            if (property != null && entry.getValue() != null) {
                try {
                    target = target.setValue(property, (Comparable) entry.getValue());
                } catch (Exception ignored) {
                }
            }
        }
        BlockEntity oldEntity = world.getBlockEntity(pos);
        CompoundTag saved = null;
        if (oldEntity != null) {
            saved = oldEntity.saveWithFullMetadata(world.registryAccess());
            oldEntity.setRemoved();
        }
        if (clearMark && saved != null) {
            saved.remove(LOADED_KEY);
            saved.remove(FUZE_TAG);
            saved.remove(TRACER_TAG);
            saved.remove(OWNER_KEY);
        }
        world.setBlock(pos, target, 3);
        if (saved != null) {
            BlockEntity newEntity = world.getBlockEntity(pos);
            if (newEntity != null) {
                try {
                    newEntity.loadWithComponents(saved, world.registryAccess());
                } catch (Exception ignored) {
                }
            }
        }
    }
}
