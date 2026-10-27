package com.qkdream.firecontrolcompat.underwater;

import com.cainiao1053.cbcmoreshells.munitions.big_cannon.FuzedTorpedoProjectileBlock;
import com.cainiao1053.cbcmoreshells.munitions.big_cannon.TorpedoProjectileBlock;
import java.util.UUID;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * The passive acoustic torpedo in 面包学's torpedo tube.
 *
 * <p>The tube keeps one round in its own slot: right-clicking with a round in
 * hand writes the kind of round into that slot, and a redstone pulse throws
 * whatever is written down the barrel. Their rounds live under their own three
 * codes, so a code of our own is all a round of ours needs - each of the two
 * here gets one - and the tube then loads, carries and fires them exactly like
 * one of its own, while nothing about the other three rounds changes.</p>
 *
 * <p>Their slot only ever holds a code, so the round's own fittings - the fuze
 * fitted to it, the tracer tip on it - are written down beside the code when
 * the round goes in and handed back to it when the round comes out. Without
 * that the round leaves the tube as a bare body: it swims, it reaches its
 * target, and it is unable to go off, because a fuze is the whole of what makes
 * it burst.</p>
 */
public final class AcousticTorpedoMianbaoTube {

    /** The tube's slot: the kind of round waiting in the barrel. */
    private static final String AMMO_TAG = "ammo";
    /** Our own code in that slot: the acoustic round. */
    private static final String AMMO_CODE = "FCAT";
    /** The code the wire-guided round is filed under. */
    private static final String WIRE_CODE = "FCWT";
    /** The round's fittings, kept beside the round while it waits in the barrel. */
    private static final String FUZE_TAG = "fuze";
    private static final String TRACER_TAG = "tracer";
    /** Where the round leaves: the block centre plus one block of barrel. */
    private static final double MUZZLE_DISTANCE = 1.0D;
    /** The speed the tube throws a round out at, in blocks a tick. */
    private static final float LAUNCH_SPEED = 2.0F;
    /** How deep the tube is: the head plus the two tail blocks behind it. */
    private static final int TUBE_DEPTH = 2;

    private AcousticTorpedoMianbaoTube() {
    }

    /**
     * Loads the round into an empty tube. The tube is only handed the round
     * when it is whole and empty, so a half-built launcher behaves the way the
     * tube's own rounds do.
     */
    public static boolean tryLoad(LevelAccessor world, double x, double y, double z, BlockState state, Entity entity) {
        if (world == null || state == null || entity == null) {
            return false;
        }
        if (!isOurs(heldItem(entity))) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        Direction facing = facing(state);
        if (facing == null || !isWholeTube(world, pos, facing) || !ammoCode(world, pos).isEmpty()) {
            return false;
        }
        if (world.isClientSide()) {
            return true;
        }
        writeAmmo(world, pos, codeFor(heldItem(entity)));
        writeFittings(world, pos, heldItem(entity));
        consumeHeld(entity);
        playSound(world, pos, "block.candle.place", SoundSource.BLOCKS, 2.0F, 1.0F);
        sendBlockUpdated(world, pos);
        return true;
    }

    /**
     * Fires the round when the tube is triggered. Anything that is not ours
     * falls straight back through to the tube's own three rounds.
     */
    public static boolean tryFire(LevelAccessor world, double x, double y, double z, BlockState state) {
        if (world == null || state == null) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        String code = ammoCode(world, pos);
        if (!AMMO_CODE.equals(code) && !WIRE_CODE.equals(code)) {
            return false;
        }
        if (world.isClientSide()) {
            return true;
        }
        Direction facing = facing(state);
        if (facing != null && isWholeTube(world, pos, facing) && world instanceof ServerLevel level) {
            launch(level, pos, facing, code);
            clearFittings(level, pos);
        }
        writeAmmo(world, pos, "");
        sendBlockUpdated(world, pos);
        return true;
    }

    /** Puts the round in the water in front of the tube and lets it go. */
    private static void launch(ServerLevel level, BlockPos pos, Direction facing, String code) {
        Vec3 muzzle = new Vec3(
                pos.getX() + 0.5D + facing.getStepX() * MUZZLE_DISTANCE,
                pos.getY() + 0.5D + facing.getStepY() * MUZZLE_DISTANCE,
                pos.getZ() + 0.5D + facing.getStepZ() * MUZZLE_DISTANCE);
        AcousticTorpedoProjectile torpedo = WIRE_CODE.equals(code)
                ? WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO_ENTITY.get().create(level)
                : AcousticTorpedoes.ACOUSTIC_TORPEDO_ENTITY.get().create(level);
        if (torpedo != null) {
            torpedo.setPos(muzzle.x, muzzle.y, muzzle.z);
            torpedo.setChargePower(0.0F);
            applyFittings(level, pos, torpedo);
            torpedo.applyLaunchSettings(TorpedoLaunchSettings.forTube(level, level.getBlockEntity(pos)));
            TorpedoDesignation.designateFromTube(level, level.getBlockEntity(pos), torpedo);
            torpedo.shoot(facing.getStepX(), facing.getStepY() + 0.01D, facing.getStepZ(), LAUNCH_SPEED, 0.0F);
            Entity owner = owner(level, pos);
            if (owner != null) {
                torpedo.setOwner(owner);
            }
            level.addFreshEntity(torpedo);
        }
        level.sendParticles(ParticleTypes.POOF, muzzle.x, muzzle.y, muzzle.z, 5, 0.5D, 0.5D, 0.5D, 0.15D);
        playSound(level, pos, "block.fire.extinguish", SoundSource.NEUTRAL, 2.0F, 0.8F);
    }

    /**
     * Keeps the round's fittings with the round. The tube writes the kind of
     * round into its slot and nothing else, so what was fitted to the round is
     * written down next to that code and read back when the round is thrown.
     */
    private static void writeFittings(LevelAccessor world, BlockPos pos, ItemStack round) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        CompoundTag data = blockEntity.getPersistentData();
        data.put(FUZE_TAG, FuzedTorpedoProjectileBlock.getFuzeFromItemStack(round)
                .saveOptional(world.registryAccess()));
        data.put(TRACER_TAG, TorpedoProjectileBlock.getTracerFromItemStack(round)
                .saveOptional(world.registryAccess()));
    }

    /** Hands the round the fuze and the tracer tip the tube kept for it. */
    private static void applyFittings(ServerLevel level, BlockPos pos, AcousticTorpedoProjectile torpedo) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        CompoundTag data = blockEntity.getPersistentData();
        torpedo.setFuze(ItemStack.parseOptional(level.registryAccess(), data.getCompound(FUZE_TAG)));
        torpedo.setTracer(ItemStack.parseOptional(level.registryAccess(), data.getCompound(TRACER_TAG)));
    }

    /** Drops the fittings of a round that has just left, so the next one gets its own. */
    private static void clearFittings(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            CompoundTag data = blockEntity.getPersistentData();
            data.remove(FUZE_TAG);
            data.remove(TRACER_TAG);
        }
    }

    private static boolean isOurs(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.getItem() == AcousticTorpedoes.ACOUSTIC_TORPEDO_ITEM.get()
                || stack.getItem() == WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO_ITEM.get();
    }

    /** The code a round is filed under in the tube's slot. */
    private static String codeFor(ItemStack stack) {
        return stack.getItem() == WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO_ITEM.get() ? WIRE_CODE : AMMO_CODE;
    }

    private static ItemStack heldItem(Entity entity) {
        return entity instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
    }

    /** Only the player who loaded the tube gets an item taken off them. */
    private static void consumeHeld(Entity entity) {
        if (!(entity instanceof Player player) || player.getAbilities().instabuild) {
            return;
        }
        player.getMainHandItem().shrink(1);
    }

    /** Which way the head points, read off its own facing property. */
    private static Direction facing(BlockState state) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        return property instanceof DirectionProperty directionProperty ? state.getValue(directionProperty) : null;
    }

    /** A tube is a head with its two tail blocks behind it. */
    private static boolean isWholeTube(LevelAccessor world, BlockPos pos, Direction facing) {
        for (int depth = 1; depth <= TUBE_DEPTH; depth++) {
            BlockState behind = world.getBlockState(pos.relative(facing.getOpposite(), depth));
            if (!behind.is(MianbaosModernwarfareModBlocks.TORPEDOLAUNCHERTAIL.get())) {
                return false;
            }
        }
        return true;
    }

    private static String ammoCode(LevelAccessor world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity == null ? "" : blockEntity.getPersistentData().getString(AMMO_TAG);
    }

    private static void writeAmmo(LevelAccessor world, BlockPos pos, String code) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntity.getPersistentData().putString(AMMO_TAG, code);
        }
    }

    /** The player the tube belongs to, kept the way the tube keeps it. */
    private static Entity owner(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        String stored = blockEntity.getPersistentData().getString("belong");
        if (stored.isEmpty()) {
            return null;
        }
        try {
            return level.getEntity(UUID.fromString(stored));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void sendBlockUpdated(LevelAccessor world, BlockPos pos) {
        if (world instanceof Level level) {
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    private static void playSound(LevelAccessor world, BlockPos pos, String sound, SoundSource source,
                                  float volume, float pitch) {
        if (!(world instanceof Level level)) {
            return;
        }
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(sound));
        if (event == null) {
            return;
        }
        if (level.isClientSide()) {
            level.playLocalSound(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, event, source, volume, pitch, false);
        } else {
            level.playSound(null, pos, event, source, volume, pitch);
        }
    }
}
