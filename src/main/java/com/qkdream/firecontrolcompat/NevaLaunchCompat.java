package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.qkdream.firecontrolcompat.entity.NevaAirDefenseMissileEntity;
import net.mcreator.myfirstmod.util.MissileGuidanceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fire plumbing for the Neva super-heavy air-defense missile. The placed
 * block is the missile itself, so it is always ready; fire control computers
 * and connected displays reach it through the {@code MianbaoAirDefenseCompat}
 * mixin, which reports the block as an air-defense launcher and fires it
 * through this class.
 */
public final class NevaLaunchCompat {

    private static final ResourceLocation NEVA_LAUNCHER_BLOCK =
            ResourceLocation.fromNamespaceAndPath("firecontrolcompat", "neva_launcher");

    private NevaLaunchCompat() {
    }

    public static boolean isNevaLauncher(BlockState state) {
        return state != null && NEVA_LAUNCHER_BLOCK.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** The placed missile block counts as one ready round. */
    public static int ammoCount(LevelAccessor world, BlockPos pos) {
        BlockEntity blockEntity = world == null || pos == null ? null : world.getBlockEntity(pos);
        return blockEntity != null && isNevaLauncher(blockEntity.getBlockState()) ? 1 : 0;
    }

    /** Resolves a binding ref to a launcher block entity carrying the Neva missile. */
    public static BlockEntity resolveLauncher(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null) {
            blockEntity = ref.resolveRebuiltAirDefenseLauncher(level);
        }
        return blockEntity != null && blockEntity.getLevel() != null
                && isNevaLauncher(blockEntity.getBlockState()) ? blockEntity : null;
    }

    /**
     * Fires the placed Neva missile: the block itself lifts off as the
     * missile, so the block is removed once the round is in the air. Fire
     * control's air-defense guidance adopts it right after spawn.
     */
    public static boolean tryLaunch(LevelAccessor world, double x, double y, double z) {
        if (world.isClientSide() || !(world instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null || !isNevaLauncher(blockEntity.getBlockState())) {
            return false;
        }
        NevaAirDefenseMissileEntity missile = BeamMissileRegistry.NEVA_TANSHE.get().create(level);
        if (missile == null) {
            return false;
        }
        missile.markLaunchSource(pos);
        BeamMissileCompat.markLaunchSubLevel(missile, level, pos);
        missile.setPos(x + 0.5, y + 1.0, z + 0.5);
        missile.shoot(0.0, 2.0, 0.0, 2.0F, 0.0F);
        missile.setBaseDamage(30.0F);
        missile.setSilent(true);
        MissileGuidanceData.copyFromLauncher(world, pos, missile);
        boolean added = level.addFreshEntity(missile);
        // A round the pack captured in mathematical mode never enters the level, so the level
        // answers false for a launch that did happen; the manager holding it is the real receipt.
        added = com.qkdream.firecontrolcompat.missile.MathGuidedMissiles.spawned(missile, added);
        if (added) {
            level.removeBlock(pos, false);
        }
        BeamMissileCompat.playLaunchSound(world, pos, 12.0F, 0.8F);
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] neva missile fired added={} pos={} delta={} removed={}",
                added, missile.position(), missile.getDeltaMovement(), missile.isRemoved());
        return added;
    }
}
