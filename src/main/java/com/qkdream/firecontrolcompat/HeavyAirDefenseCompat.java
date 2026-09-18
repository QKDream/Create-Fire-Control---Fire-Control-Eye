package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.qkdream.firecontrolcompat.entity.HeavyAirDefenseMissileEntity;
import net.mcreator.myfirstmod.util.MissileGuidanceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Load / fire plumbing for the heavy air-defense missile in the mianbao
 * vertical launch silo. The ammo count lives in {@code fcheavy} on the silo
 * head block entity so it never collides with the four native cruise slots;
 * fire control computers and connected displays reach it through the
 * {@code MianbaoAirDefenseCompat} mixin, which reports the silo as an
 * air-defense launcher.
 */
public final class HeavyAirDefenseCompat {

    public static final String HEAVY_AA_AMMO_KEY = "fcheavy";

    private static final ResourceLocation SILO_HEAD = ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "groundmissilelauncherhead");
    private static final ResourceLocation SILO_TAIL = ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "groundmissilelaunchertail");

    private HeavyAirDefenseCompat() {
    }

    public static boolean isSiloHead(BlockState state) {
        return state != null && SILO_HEAD.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isSiloTail(BlockState state) {
        return state != null && SILO_TAIL.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Head with the two native tail blocks directly underneath. */
    public static boolean isVerticalSilo(LevelAccessor world, BlockPos head) {
        return world != null && head != null
                && isSiloHead(world.getBlockState(head))
                && isSiloTail(world.getBlockState(head.below()))
                && isSiloTail(world.getBlockState(head.below(2)));
    }

    public static boolean isHeavyMissile(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.HEAVY_AIR_DEFENSE_MISSILE.get();
    }

    /** Resolves a binding ref to a silo head carrying the heavy air-defense missile. */
    public static boolean hasHeavyAmmo(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return false;
        }
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null) {
            blockEntity = ref.resolveRebuiltAirDefenseLauncher(level);
        }
        return blockEntity != null
                && isSiloHead(blockEntity.getBlockState())
                && blockEntity.getPersistentData().getDouble(HEAVY_AA_AMMO_KEY) > 0.0;
    }

    /**
     * Loads the held heavy air-defense missile into the vertical silo at the
     * given head position. Capacity is one; the silo must be structurally
     * complete and free of native cruise ammunition.
     */
    public static boolean tryLoadSilo(LevelAccessor world, double x, double y, double z, Entity entity) {
        if (!isHeavyMissile(BeamMissileCompat.heldItem(entity))) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world.isClientSide()) {
            return true;
        }
        if (!isVerticalSilo(world, pos)) {
            BeamMissileCompat.message(entity, "垂直发射器结构不完整");
            return true;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null) {
            return true;
        }
        double nativeAmmo = blockEntity.getPersistentData().getDouble("loadmissile")
                + blockEntity.getPersistentData().getDouble("loadmissile2")
                + blockEntity.getPersistentData().getDouble("loadmissile3")
                + blockEntity.getPersistentData().getDouble("loadmissile4");
        double heavy = blockEntity.getPersistentData().getDouble(HEAVY_AA_AMMO_KEY);
        if (nativeAmmo > 0.0 || heavy > 0.0) {
            BeamMissileCompat.message(entity, "发射井已占用，请先清空");
            return true;
        }
        blockEntity.getPersistentData().putDouble(HEAVY_AA_AMMO_KEY, 1.0);
        blockEntity.setChanged();
        BeamMissileCompat.consumeHeld(entity);
        BeamMissileCompat.playLoadSound(world, pos);
        BeamMissileCompat.message(entity, "已装填重型防空导弹");
        BeamMissileCompat.sendBlockUpdated(world, pos);
        return true;
    }

    /**
     * Fires the heavy air-defense missile from the silo head. The projectile
     * leaves straight up exactly like the native vertical launcher; fire
     * control's air-defense guidance adopts it right after spawn.
     */
    public static boolean tryLaunchSilo(LevelAccessor world, double x, double y, double z) {
        if (world.isClientSide() || !(world instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!isVerticalSilo(world, pos)) {
            return false;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.getPersistentData().getDouble(HEAVY_AA_AMMO_KEY) < 1.0) {
            return false;
        }
        HeavyAirDefenseMissileEntity missile = BeamMissileRegistry.HEAVY_AA_TANSHE.get().create(level);
        if (missile == null) {
            return false;
        }
        missile.markLaunchSource(pos);
        BeamMissileCompat.markLaunchSubLevel(missile, level, pos);
        missile.setPos(x + 0.5, y + 1.0, z + 0.5);
        missile.shoot(0.0, 2.0, 0.0, 2.0F, 0.0F);
        missile.setBaseDamage(20.0F);
        missile.setSilent(true);
        MissileGuidanceData.copyFromLauncher(world, pos, missile);
        boolean added = level.addFreshEntity(missile);
        blockEntity.getPersistentData().putDouble(HEAVY_AA_AMMO_KEY, 0.0);
        blockEntity.setChanged();
        BeamMissileCompat.playLaunchSound(world, pos, 12.0F, 0.8F);
        BeamMissileCompat.sendBlockUpdated(world, pos);
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] heavy air-defense missile fired added={} pos={} delta={} removed={}",
                added, missile.position(), missile.getDeltaMovement(), missile.isRemoved());
        return added;
    }
}