package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import com.qkdream.firecontrolcompat.entity.LoiteringMissileEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Map;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModBlocks;
import net.mcreator.myfirstmod.util.MissileGuidanceData;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Shared load / fire plumbing for the beam-riding missile across the mianbao
 * arsenal launchers (AA, ATGM, mixed) and the aircraft weapon rack.
 *
 * <p>All launchers are MCreator blocks whose ammo lives in
 * {@code BlockEntity#getPersistentData()}. We store the beam missile count in
 * {@code fcbeamammo} while mirroring it into the native ammo keys
 * ({@code missileammo} / {@code missile1ammo} / {@code AAmissile}) so the
 * vanilla launcher models and the fire-control bindings keep working.</p>
 */
public final class BeamMissileCompat {

    public static final String BEAM_AMMO_KEY = "fcbeamammo";
    public static final String IR_AA_AMMO_KEY = "fcirraammo";
    public static final String IR_RACK_KEY = "fcirrack";
    public static final String LOITER_ATGM_AMMO_KEY = "fcloiter";

    public static final String AA_AMMO_KEY = "missileammo";
    public static final String ATGM_AMMO_KEY_1 = "missile1ammo";
    public static final String ATGM_AMMO_KEY_2 = "missile2ammo";
    public static final String MIXED_AA_KEY = "AAmissile";
    public static final String MIXED_AT_KEY_1 = "ATmissile1";
    public static final String MIXED_AT_KEY_2 = "ATmissile2";

    private BeamMissileCompat() {
    }

    public static boolean isBeamMissile(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.BEAM_RIDING_MISSILE.get();
    }

    public static boolean isInfraredMissile(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.INFRARED_MISSILE.get();
    }

    public static boolean isAircraftInfraredMissile(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.AIRCRAFT_INFRARED_MISSILE.get();
    }

    public static boolean isLoiteringMunition(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.LOITERING_MUNITION.get();
    }

    public static ItemStack heldItem(Entity entity) {
        return entity instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
    }

    public static double nbtDouble(LevelAccessor world, BlockPos pos, String key) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity != null ? blockEntity.getPersistentData().getDouble(key) : 0.0;
    }

    public static void nbtPutDouble(LevelAccessor world, BlockPos pos, String key, double value) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntity.getPersistentData().putDouble(key, value);
        }
    }

    public static boolean nbtFlag(LevelAccessor world, BlockPos pos, String key) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity != null && blockEntity.getPersistentData().getBoolean(key);
    }

    public static Direction facing(LevelAccessor world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Property<?> facingProperty = state.getBlock().getStateDefinition().getProperty("facing");
        if (facingProperty instanceof DirectionProperty directionProperty) {
            return state.getValue(directionProperty);
        }
        Property<?> axisProperty = state.getBlock().getStateDefinition().getProperty("axis");
        if (axisProperty instanceof EnumProperty<?> enumProperty
                && !enumProperty.getPossibleValues().isEmpty()
                && enumProperty.getPossibleValues().toArray()[0] instanceof Direction.Axis) {
            return Direction.fromAxisAndDirection((Direction.Axis) state.getValue(enumProperty), Direction.AxisDirection.POSITIVE);
        }
        return Direction.NORTH;
    }

    public static void consumeHeld(Entity entity) {
        if (!(entity instanceof Player player) || player.getAbilities().instabuild) {
            return;
        }
        player.getMainHandItem().shrink(1);
    }

    public static void message(Entity entity, String text) {
        if (entity instanceof Player player && !player.level().isClientSide()) {
            player.displayClientMessage(Component.literal(text), true);
        }
    }

    public static void sendBlockUpdated(LevelAccessor world, BlockPos pos) {
        if (world instanceof Level level) {
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    public static void playLoadSound(LevelAccessor world, BlockPos pos) {
        playSound(world, pos, "block.candle.place", SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    public static void playLaunchSound(LevelAccessor world, BlockPos pos, float volume, float pitch) {
        playSound(world, pos, "mianbaos_modernwarfare:missile_launcher", SoundSource.NEUTRAL, volume, pitch);
    }

    private static void playSound(LevelAccessor world, BlockPos pos, String sound, SoundSource source, float volume, float pitch) {
        if (world instanceof Level level) {
            SoundEvent soundEvent = (SoundEvent) BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(sound));
            if (!level.isClientSide()) {
                level.playSound(null, pos, soundEvent, source, volume, pitch);
            } else {
                level.playLocalSound(pos.getX(), pos.getY(), pos.getZ(), soundEvent, source, volume, pitch, false);
            }
        }
    }

    /** AA launcher family: muzzle = block center + facing * 2.6, speed 2.0. */
    public static boolean tryLoadAntiAir(LevelAccessor world, double x, double y, double z, Entity entity, double capacity) {
        boolean beam = isBeamMissile(heldItem(entity));
        boolean infrared = isInfraredMissile(heldItem(entity));
        if (!beam && !infrared) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world.isClientSide()) {
            return true;
        }
        double nativeAmmo = nbtDouble(world, pos, AA_AMMO_KEY);
        double beamAmmo = nbtDouble(world, pos, BEAM_AMMO_KEY);
        double irAmmo = nbtDouble(world, pos, IR_AA_AMMO_KEY);
        if (nativeAmmo < capacity) {
            nbtPutDouble(world, pos, AA_AMMO_KEY, nativeAmmo + 1.0);
            if (beam) {
                nbtPutDouble(world, pos, BEAM_AMMO_KEY, beamAmmo + 1.0);
                message(entity, "已装填架束近炸导弹" + (int) (nativeAmmo + 1.0) + "发");
            } else {
                nbtPutDouble(world, pos, IR_AA_AMMO_KEY, irAmmo + 1.0);
                message(entity, "已装填红外锁定导弹" + (int) (nativeAmmo + 1.0) + "发");
            }
            consumeHeld(entity);
            playLoadSound(world, pos);
            sendBlockUpdated(world, pos);
        } else {
            message(entity, "装填完成");
        }
        return true;
    }

    public static boolean tryFireAntiAir(LevelAccessor world, double x, double y, double z, double muzzleDistance, float speed) {
        if (world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (nbtDouble(world, pos, AA_AMMO_KEY) <= 0.0) {
            return false;
        }
        boolean infrared = nbtDouble(world, pos, IR_AA_AMMO_KEY) > 0.0;
        if (!infrared && nbtDouble(world, pos, BEAM_AMMO_KEY) <= 0.0) {
            return false;
        }
        Direction direction = facing(world, pos);
        spawnBeamMissile(world, pos, simpleMuzzle(pos, direction, muzzleDistance, 0.0), direction, speed,
                infrared ? BeamMissileRegistry.INFRARED_TANSHE.get() : BeamMissileRegistry.BEAMRIDER_TANSHE.get(), null);
        nbtPutDouble(world, pos, AA_AMMO_KEY, Math.max(0.0, nbtDouble(world, pos, AA_AMMO_KEY) - 1.0));
        if (infrared) {
            nbtPutDouble(world, pos, IR_AA_AMMO_KEY, Math.max(0.0, nbtDouble(world, pos, IR_AA_AMMO_KEY) - 1.0));
        } else {
            nbtPutDouble(world, pos, BEAM_AMMO_KEY, Math.max(0.0, nbtDouble(world, pos, BEAM_AMMO_KEY) - 1.0));
        }
        playLaunchSound(world, pos, 3.0F, 0.8F);
        sendBlockUpdated(world, pos);
        return true;
    }

    /** ATGM launcher family. Fills slot 1 then slot 2 up to the launcher's native capacity. */
    public static boolean tryLoadAtgm(LevelAccessor world, double x, double y, double z, Entity entity,
            double slot1Capacity, double slot2Capacity) {
        ItemStack held = heldItem(entity);
        boolean beam = isBeamMissile(held);
        boolean loitering = isLoiteringMunition(held);
        if (!beam && !loitering) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world.isClientSide()) {
            return true;
        }
        double slot1 = nbtDouble(world, pos, ATGM_AMMO_KEY_1);
        double slot2 = nbtDouble(world, pos, ATGM_AMMO_KEY_2);
        double beamAmmo = nbtDouble(world, pos, BEAM_AMMO_KEY);
        double loiterAmmo = nbtDouble(world, pos, LOITER_ATGM_AMMO_KEY);
        if (beamAmmo <= 0.0 && loiterAmmo <= 0.0 && (slot1 > 0.0 || slot2 > 0.0)) {
            message(entity, "发射器已占用，请先清空");
            return true;
        }
        if (beam && loiterAmmo > 0.0 || loitering && beamAmmo > 0.0) {
            message(entity, "发射器已占用，请先清空");
            return true;
        }
        if (slot1 < slot1Capacity) {
            slot1 += 1.0;
            nbtPutDouble(world, pos, ATGM_AMMO_KEY_1, slot1);
        } else if (slot2 < slot2Capacity) {
            slot2 += 1.0;
            nbtPutDouble(world, pos, ATGM_AMMO_KEY_2, slot2);
        } else {
            message(entity, "装填完成");
            return true;
        }
        double total = slot1 + slot2;
        consumeHeld(entity);
        playLoadSound(world, pos);
        if (loitering) {
            nbtPutDouble(world, pos, LOITER_ATGM_AMMO_KEY, loiterAmmo + 1.0);
            message(entity, "已装填小型巡飞弹" + (int) (loiterAmmo + 1.0) + "发");
        } else {
            nbtPutDouble(world, pos, BEAM_AMMO_KEY, total);
            message(entity, "已装填架束近炸导弹" + (int) total + "发");
        }
        sendBlockUpdated(world, pos);
        return true;
    }

    public static boolean tryFireAtgm(LevelAccessor world, double x, double y, double z, boolean specialMuzzle) {
        if (world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        double slot1 = nbtDouble(world, pos, ATGM_AMMO_KEY_1);
        double slot2 = nbtDouble(world, pos, ATGM_AMMO_KEY_2);
        double beamAmmo = nbtDouble(world, pos, BEAM_AMMO_KEY);
        double loiterAmmo = nbtDouble(world, pos, LOITER_ATGM_AMMO_KEY);
        boolean beam = beamAmmo > 0.0;
        boolean loitering = loiterAmmo > 0.0;
        if (!beam && !loitering || slot1 <= 0.0 && slot2 <= 0.0) {
            return false;
        }
        if (slot1 > 0.0) {
            slot1 -= 1.0;
            nbtPutDouble(world, pos, ATGM_AMMO_KEY_1, slot1);
        } else {
            slot2 -= 1.0;
            nbtPutDouble(world, pos, ATGM_AMMO_KEY_2, slot2);
        }
        Direction direction = facing(world, pos);
        Vec3 muzzle = specialMuzzle ? atgmMainMuzzle(pos, direction) : simpleMuzzle(pos, direction, 2.6, 0.0);
        if (loitering) {
            if (!(world instanceof ServerLevel level)) {
                return false;
            }
            LoiteringMissileEntity missile = BeamMissileRegistry.LOITERING_TANSHE.get().create(level);
            if (missile == null) {
                return false;
            }
            // Launchers sit on Sable ships: project the muzzle and the launch
            // direction into world space exactly like the aircraft rack path,
            // otherwise the missile alternates between sublevel-local and
            // world velocity every tick and flies the wrong way.
            Vec3 worldMuzzle = Sable.HELPER.projectOutOfSubLevel(level, muzzle);
            Vec3 localForward = muzzle.add(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            Vec3 worldForward = Sable.HELPER.projectOutOfSubLevel(level, localForward);
            Vec3 worldDirection = worldForward.subtract(worldMuzzle);
            if (worldDirection.lengthSqr() < 1.0E-8) {
                worldDirection = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            }
            worldDirection = worldDirection.normalize();
            missile.markLaunchSource(pos);
            missile.setLaunchProfile(worldDirection, 3.0);
            missile.setPos(worldMuzzle.x, worldMuzzle.y, worldMuzzle.z);
            missile.shoot(worldDirection.x, worldDirection.y + 0.05, worldDirection.z, 3.0F, 0.0F);
            missile.setBaseDamage(20.0F);
            missile.setSilent(true);
            MissileGuidanceData.copyFromLauncher(world, pos, missile);
            markLaunchSubLevel(missile, level, pos);
            boolean added = level.addFreshEntity(missile);
            missile.moveTo(worldMuzzle.x, worldMuzzle.y, worldMuzzle.z);
            Vec3 velocity = missile.getDeltaMovement();
            missile.setYRot((float) Math.toDegrees(Mth.atan2(velocity.x, velocity.z)));
            missile.setXRot((float) Math.toDegrees(Mth.atan2(velocity.y, velocity.horizontalDistance())));
            missile.bindNearestController(level, pos);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] loitering munition fired added={} pos={} delta={} removed={}",
                    added, missile.position(), missile.getDeltaMovement(), missile.isRemoved());
            nbtPutDouble(world, pos, LOITER_ATGM_AMMO_KEY, Math.max(0.0, loiterAmmo - 1.0));
        } else {
            spawnBeamMissile(world, pos, muzzle, direction, 3.0F, BeamMissileRegistry.BEAMRIDER_TANSHE.get(), null);
            nbtPutDouble(world, pos, BEAM_AMMO_KEY, Math.max(0.0, slot1 + slot2));
        }
        playLaunchSound(world, pos, 5.0F, 0.8F);
        sendBlockUpdated(world, pos);
        return true;
    }

    /** Mixed launcher: our missile rides the AA bay (capacity 2), mirrored into {@code AAmissile}. */
    public static boolean tryLoadMixed(LevelAccessor world, double x, double y, double z, Entity entity) {
        if (!isBeamMissile(heldItem(entity))) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world.isClientSide()) {
            return true;
        }
        double aaAmmo = nbtDouble(world, pos, MIXED_AA_KEY);
        if (aaAmmo < 2.0
                && nbtDouble(world, pos, MIXED_AT_KEY_1) == 0.0
                && nbtDouble(world, pos, MIXED_AT_KEY_2) == 0.0) {
            aaAmmo += 1.0;
            nbtPutDouble(world, pos, MIXED_AA_KEY, aaAmmo);
            nbtPutDouble(world, pos, BEAM_AMMO_KEY, aaAmmo);
            consumeHeld(entity);
            playLoadSound(world, pos);
            message(entity, "已装填架束近炸导弹" + (int) aaAmmo + "发");
            sendBlockUpdated(world, pos);
        } else {
            message(entity, "挂架已占用，请先清空");
        }
        return true;
    }

    public static boolean tryFireMixed(LevelAccessor world, double x, double y, double z) {
        if (world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (nbtDouble(world, pos, BEAM_AMMO_KEY) <= 0.0 || nbtDouble(world, pos, MIXED_AA_KEY) <= 0.0) {
            return false;
        }
        Direction direction = facing(world, pos);
        spawnBeamMissile(world, pos, simpleMuzzle(pos, direction, 2.6, 0.0), direction, 4.0F,
                BeamMissileRegistry.BEAMRIDER_TANSHE.get(), null);
        nbtPutDouble(world, pos, MIXED_AA_KEY, Math.max(0.0, nbtDouble(world, pos, MIXED_AA_KEY) - 1.0));
        nbtPutDouble(world, pos, BEAM_AMMO_KEY, Math.max(0.0, nbtDouble(world, pos, BEAM_AMMO_KEY) - 1.0));
        playLaunchSound(world, pos, 3.0F, 0.8F);
        sendBlockUpdated(world, pos);
        return true;
    }

    /** Aircraft weapon rack: the aircraft infrared missile loads on the native close-missile rack. */
    public static boolean tryLoadRack(LevelAccessor world, double x, double y, double z, Entity entity) {
        if (!isAircraftInfraredMissile(heldItem(entity))) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world.isClientSide()) {
            return true;
        }
        if (world.getBlockState(pos).getBlock() == MianbaosModernwarfareModBlocks.RACK.get()) {
            replaceBlockPreservingData(world, pos, MianbaosModernwarfareModBlocks.CLOSE_MISSILE_1RACK.get(), false);
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity != null) {
                blockEntity.getPersistentData().putBoolean(IR_RACK_KEY, true);
            }
            consumeHeld(entity);
            playLoadSound(world, pos);
            message(entity, "已装填空载红外锁定导弹");
            sendBlockUpdated(world, pos);
        } else {
            message(entity, "只能装填到空挂架上");
        }
        return true;
    }

    public static boolean tryFireRack(LevelAccessor world, double x, double y, double z) {
        if (world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!nbtFlag(world, pos, IR_RACK_KEY)) {
            return false;
        }
        Direction direction = facing(world, pos);
        Vec3 carrierVelocity = carrierVelocityAt(world, pos);
        spawnBeamMissileAtWorldMuzzle(world, pos, closeRackMuzzle(pos, direction), direction, 6.0F,
                BeamMissileRegistry.AIRCRAFT_INFRARED_TANSHE.get(), carrierVelocity);
        playLaunchSound(world, pos, 5.0F, 0.8F);
        replaceBlockPreservingData(world, pos, MianbaosModernwarfareModBlocks.RACK.get(), true);
        sendBlockUpdated(world, pos);
        return true;
    }

    /** Verbatim muzzle offsets of the mianbao close-missile rack go-procedure. */
    private static Vec3 closeRackMuzzle(BlockPos pos, Direction direction) {
        return new Vec3(
                pos.getX() + 0.5 + direction.getStepX() * 2.6,
                pos.getY() - 1.0 + direction.getStepY() * 2.6,
                pos.getZ() + 0.5 + direction.getStepZ() * 2.6
        );
    }

    /** Whether the bound AA launcher carries infrared missiles. */
    public static boolean hasIrAntiAirAmmo(Level level, BindingRef ref) {
        BlockEntity blockEntity = ref == null ? null : ref.resolve(level);
        return blockEntity != null && blockEntity.getPersistentData().getDouble(IR_AA_AMMO_KEY) > 0.0;
    }

    /** Whether the bound aircraft rack carries our infrared missile. */
    public static boolean isIrRackLoaded(Level level, BindingRef ref) {
        BlockEntity blockEntity = ref == null ? null : ref.resolve(level);
        return blockEntity != null && blockEntity.getPersistentData().getBoolean(IR_RACK_KEY);
    }

    private static Vec3 simpleMuzzle(BlockPos pos, Direction direction, double distance, double extraDown) {
        return new Vec3(
                pos.getX() + 0.5 + direction.getStepX() * distance,
                pos.getY() + 0.5 + extraDown + direction.getStepY() * distance,
                pos.getZ() + 0.5 + direction.getStepZ() * distance
        );
    }

    /** Muzzle point of the AA missile launcher at the given block position. */
    public static Vec3 antiAirMuzzle(LevelAccessor world, BlockPos pos) {
        return simpleMuzzle(pos, facing(world, pos), 2.6, 0.0);
    }

    /** Muzzle point of the aircraft close-missile rack at the given block position. */
    public static Vec3 rackMuzzle(LevelAccessor world, BlockPos pos) {
        return closeRackMuzzle(pos, facing(world, pos));
    }

    /** Verbatim muzzle offsets of the original ATGM launcher go-procedure. */
    private static Vec3 atgmMainMuzzle(BlockPos pos, Direction direction) {
        double fromX = pos.getX() + 0.5 + direction.getStepX() * 0.6;
        double fromY = pos.getY() + 0.5 + direction.getStepY() * 0.6;
        double fromZ = pos.getZ() + 0.5 + direction.getStepZ() * 0.6;
        if (direction == Direction.NORTH) {
            fromZ = pos.getZ() - 2.5 + direction.getStepZ() * 0.6;
        } else if (direction == Direction.SOUTH) {
            fromZ = pos.getZ() + 3.5 + direction.getStepZ() * 0.6;
        } else if (direction == Direction.WEST) {
            fromX = pos.getX() - 2.5 + direction.getStepX() * 0.6;
        } else if (direction == Direction.EAST) {
            fromX = pos.getX() + 3.5 + direction.getStepX() * 0.6;
        }
        return new Vec3(fromX, fromY, fromZ);
    }

    private static void spawnBeamMissile(LevelAccessor world, BlockPos launcherPos, Vec3 muzzle, Direction direction, float speed,
            EntityType<? extends BeamRidingMissileEntity> entityType, Vec3 carrierVelocity) {
        if (!(world instanceof ServerLevel level)) {
            return;
        }
        BeamRidingMissileEntity missile = entityType.create(level);
        if (missile == null) {
            return;
        }
        missile.markLaunchSource(launcherPos);
        missile.setPos(muzzle.x, muzzle.y, muzzle.z);
        missile.shoot(direction.getStepX(), direction.getStepY() + 0.05, direction.getStepZ(), speed, 0.0F);
        missile.setBaseDamage(20.0F);
        missile.setSilent(true);
        MissileGuidanceData.copyFromLauncher(world, launcherPos, missile);
        markLaunchSubLevel(missile, level, launcherPos);
        boolean added = level.addFreshEntity(missile);
        if (missile.getType() == BeamMissileRegistry.INFRARED_TANSHE.get()) {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ground ir missile spawned added={} entityTicking={} pos={} delta={} removed={}",
                    added, level.isPositionEntityTicking(BlockPos.containing(missile.position())),
                    missile.position(), missile.getDeltaMovement(), missile.isRemoved());
        }
        if (carrierVelocity != null && carrierVelocity.lengthSqr() > 1.0E-8) {
            missile.setDeltaMovement(missile.getDeltaMovement().add(carrierVelocity));
        }
    }

    /**
     * Aircraft racks live inside Sable sublevels. Spawning at the rack's local
     * coordinates relies on Sable kicking the projectile through the sublevel
     * pose on {@code addFreshEntity}, and its clip overwrite can then hand the
     * arrow sublevel-local hit coordinates. Mirroring fire control's own rack
     * path, we project the muzzle and the launch direction into world space
     * ourselves and re-assert the world pose after spawning in case the kick ran.
     */
    private static void spawnBeamMissileAtWorldMuzzle(LevelAccessor world, BlockPos launcherPos, Vec3 localMuzzle,
            Direction direction, float speed, EntityType<? extends BeamRidingMissileEntity> entityType, Vec3 carrierVelocity) {
        if (!(world instanceof ServerLevel level)) {
            return;
        }
        Vec3 worldMuzzle = Sable.HELPER.projectOutOfSubLevel(level, localMuzzle);
        Vec3 localForward = localMuzzle.add(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        Vec3 worldForward = Sable.HELPER.projectOutOfSubLevel(level, localForward);
        Vec3 worldDirection = worldForward.subtract(worldMuzzle);
        if (worldDirection.lengthSqr() < 1.0E-8) {
            worldDirection = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        }
        worldDirection = worldDirection.normalize();

        BeamRidingMissileEntity missile = entityType.create(level);
        if (missile == null) {
            return;
        }
        missile.markLaunchSource(BlockPos.containing(worldMuzzle));
        markLaunchSubLevel(missile, level, launcherPos);
        missile.setPos(worldMuzzle.x, worldMuzzle.y, worldMuzzle.z);
        missile.shoot(worldDirection.x, worldDirection.y + 0.05, worldDirection.z, speed, 0.0F);
        missile.setBaseDamage(20.0F);
        missile.setSilent(true);
        MissileGuidanceData.copyFromLauncher(world, launcherPos, missile);
        boolean added = level.addFreshEntity(missile);
        missile.moveTo(worldMuzzle.x, worldMuzzle.y, worldMuzzle.z);
        Vec3 velocity = missile.getDeltaMovement();
        if (carrierVelocity != null && carrierVelocity.lengthSqr() > 1.0E-8) {
            velocity = velocity.add(carrierVelocity);
        }
        missile.setDeltaMovement(velocity);
        missile.setYRot((float) Math.toDegrees(Mth.atan2(velocity.x, velocity.z)));
        missile.setXRot((float) Math.toDegrees(Mth.atan2(velocity.y, velocity.horizontalDistance())));
        boolean entityTicking = level.isPositionEntityTicking(BlockPos.containing(worldMuzzle));
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] aircraft ir missile spawned added={} removed={} addedToLevel={} entityTicking={} local={} world={} dir={} carrier={}",
                added, missile.isRemoved(), missile.isAddedToLevel(), entityTicking, localMuzzle, worldMuzzle, worldDirection, carrierVelocity);
    }

    /** Remembers the Sable sublevel that owns the launcher so the seeker skips the carrier aircraft. */
    public static void markLaunchSubLevel(BeamRidingMissileEntity missile, ServerLevel level, BlockPos launcherPos) {
        try {
            SubLevel launchSubLevel = Sable.HELPER.getContaining(level, launcherPos);
            if (launchSubLevel != null) {
                missile.markLaunchSubLevel(launchSubLevel.getUniqueId());
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Per-tick velocity of the Sable sublevel that owns the given local block
     * position. Aircraft-launched missiles inherit this so the moving carrier
     * cannot run over the missile before its motor spools up.
     */
    private static Vec3 carrierVelocityAt(LevelAccessor world, BlockPos localPos) {
        if (!(world instanceof ServerLevel serverLevel)) {
            return Vec3.ZERO;
        }
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                return Vec3.ZERO;
            }
            Vector3d local = new Vector3d(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5);
            SubLevel plotOwner = Sable.HELPER.getContaining(serverLevel, localPos);
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                Vector3d current = new Vector3d(local);
                Vector3d previous = new Vector3d(local);
                subLevel.logicalPose().transformPosition(current);
                subLevel.lastPose().transformPosition(previous);
                boolean ownPlot = plotOwner != null && plotOwner.getUniqueId().equals(subLevel.getUniqueId());
                if (!ownPlot && !subLevel.boundingBox().toMojang().contains(current.x, current.y, current.z)) {
                    continue;
                }
                Vec3 velocity = new Vec3(current.x - previous.x, current.y - previous.y, current.z - previous.z);
                return velocity.lengthSqr() <= 4096.0 ? velocity : Vec3.ZERO;
            }
        } catch (Throwable ignored) {
        }
        return Vec3.ZERO;
    }

    /** MCreator-style block swap that keeps the block entity data and state properties. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceBlockPreservingData(LevelAccessor world, BlockPos pos, Block targetBlock, boolean clearBeamFlag) {
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
        if (clearBeamFlag && saved != null) {
            saved.remove(IR_RACK_KEY);
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
