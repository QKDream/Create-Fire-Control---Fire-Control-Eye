package com.qkdream.firecontrolcompat;

import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Moves ammunition from a crate into a launcher.
 *
 * <p>面包学 launchers are loaded through their own click handler: a fake
 * player holds the round and the block's {@code useWithoutItem} /
 * {@code useItemOn} is run in its name, which is exactly the path a real
 * player's right click takes, so every launcher family keeps its own rules
 * about which round it takes, how many fit and what sound it plays. TAOV
 * launchers expose the same decision as a method
 * ({@code canAcceptOuterStack} / {@code insertOuterStack}) and are used
 * directly. When a launcher takes the round the stored counter moves, which is
 * what tells a full launcher from one that refuses the round.</p>
 */
public final class AmmoLoadingCompat {

    /** Outcome of one loading attempt. */
    public enum Result {
        /** The launcher took the round. */
        SUCCESS,
        /** The launcher is full. */
        FULL,
        /** The launcher does not take this kind of round. */
        WRONG_TYPE,
        /** Not a launcher we know how to load. */
        UNSUPPORTED
    }

    /** Counters 面包学 keeps its own rounds in. */
    private static final String[] COUNTER_KEYS = {
            "missileammo", "missile1ammo", "missile2ammo", "missile3ammo", "missile4ammo",
            "loadmissile", "loadmissile2", "loadmissile3", "loadmissile4", "AAmissile", "ammo"
    };

    /** 面包学 missiles whose item id does not carry the word "missile". */
    private static final Set<String> EXTRA_MIANBAO_MISSILES = Set.of("aim_120", "mica_em");

    /** Launcher id fragments that never count as a launcher. */
    private static final String[] NOT_A_LAUNCHER = {
            "launcher", "turret", "rack", "system", "director", "pod", "deployer", "displayer"
    };

    private AmmoLoadingCompat() {
    }

    /** True for the ammunition the crate stores: 面包学, TAOV and 火控之眼 missiles. */
    public static boolean isMissileAmmo(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // The rounds this mod builds are ammunition of the store as well.
        if (stack.getItem() instanceof FireControlMissileItem) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String namespace = id.getNamespace();
        String path = id.getPath();
        if ("taov_weapons".equals(namespace)) {
            return path.endsWith("_missile");
        }
        if (!"mianbaos_modernwarfare".equals(namespace)) {
            return false;
        }
        if (EXTRA_MIANBAO_MISSILES.contains(path)) {
            return true;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.contains("missile")) {
            return false;
        }
        for (String blocked : NOT_A_LAUNCHER) {
            if (lower.contains(blocked)) {
                return false;
            }
        }
        return true;
    }

    /** True when the block is a launcher / rack the loader can feed. */
    public static boolean isLauncher(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        BlockState state = SubLevelPositions.blockState(level, pos);
        if (state == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("launcher") || path.contains("rack") || path.contains("tanshe")
                || path.contains("silo") || path.contains("tube");
    }

    /** Rounds the launcher holds right now. */
    public static int ammoCount(Level level, BlockPos pos) {
        BlockEntity blockEntity = SubLevelPositions.blockEntity(level, pos);
        if (blockEntity == null) {
            return 0;
        }
        if (FireControlCompat.isModLoaded("taov_weapons")) {
            Integer taov = TaovAccess.count(blockEntity);
            if (taov != null) {
                return taov;
            }
        }
        double total = 0.0;
        for (String key : COUNTER_KEYS) {
            total += blockEntity.getPersistentData().getDouble(key);
        }
        return (int) Math.floor(total);
    }

    /** Attempts to load one round into the launcher at {@code pos}. */
    public static Result tryInsert(Level level, BlockPos pos, ItemStack round) {
        if (round == null || round.isEmpty()) {
            return Result.UNSUPPORTED;
        }
        if (level == null || pos == null || level.isClientSide()) {
            return Result.UNSUPPORTED;
        }
        if (FireControlCompat.isModLoaded("taov_weapons")) {
            Result taov = TaovAccess.insert(level, pos, round);
            if (taov != null) {
                return taov;
            }
        }
        return insertMianbao(level, pos, round);
    }

    /**
     * Whether the launcher would take the round. Only the TAOV launchers can
     * answer this without trying; 面包学 launchers are checked on the attempt.
     */
    public static Result canAccept(Level level, BlockPos pos, ItemStack round) {
        if (round == null || round.isEmpty()) {
            return Result.WRONG_TYPE;
        }
        if (level == null || pos == null || SubLevelPositions.blockEntity(level, pos) == null) {
            return Result.UNSUPPORTED;
        }
        if (FireControlCompat.isModLoaded("taov_weapons")) {
            Result taov = TaovAccess.canAccept(level, pos, round);
            if (taov != null) {
                return taov;
            }
        }
        if (!isLauncher(level, pos)) {
            return Result.UNSUPPORTED;
        }
        return ammoCount(level, pos) >= capacityGuess(level, pos) ? Result.FULL : Result.SUCCESS;
    }

    /** Round count the launcher can hold, guessed from its id for the message. */
    public static int capacityGuess(Level level, BlockPos pos) {
        BlockState state = SubLevelPositions.blockState(level, pos);
        ResourceLocation id = state == null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("rack")) {
            return 1;
        }
        if (path.contains("groundmissilelauncher")) {
            return 4;
        }
        if (path.contains("catapult")) {
            return 1;
        }
        return 4;
    }

    private static Result insertMianbao(Level level, BlockPos pos, ItemStack round) {
        BlockEntity blockEntity = SubLevelPositions.blockEntity(level, pos);
        BlockState state = SubLevelPositions.blockState(level, pos);
        if (blockEntity == null || state == null || state.isAir()) {
            return Result.UNSUPPORTED;
        }
        // The click has to run in the level the launcher itself lives in, which on a
        // Sable structure is the plot level rather than the level of the caller.
        Level owner = blockEntity.getLevel() != null ? blockEntity.getLevel() : SubLevelPositions.level(level, pos);
        if (!(owner instanceof ServerLevel serverLevel)) {
            return Result.UNSUPPORTED;
        }
        FakePlayer holder = loaderFor(serverLevel);
        double before = counters(blockEntity);
        holder.getInventory().clearContent();
        // Both hands hold a copy: the reload handlers of the different launcher
        // families disagree about which hand they read, and a real player can hold
        // the round in either one.
        ItemStack held = round.copyWithCount(1);
        holder.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
        holder.setItemInHand(InteractionHand.OFF_HAND, held.copy());
        try {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            try {
                // The public click entry point of the server: it runs the block's
                // own item check and then its use-without-item handler, which is
                // where 面包学 reloads. Nothing else of the click is simulated, so
                // the launcher sees no more than it would from a player.
                holder.gameMode.useItemOn(holder, owner, held, InteractionHand.MAIN_HAND, hit);
            } catch (Throwable t) {
                FireControlCompat.LOGGER.debug("[firecontrolcompat] launcher refused a reload click at {}", pos, t);
            }
            boolean taken = counters(blockEntity) > before
                    || holder.getMainHandItem().isEmpty() && holder.getOffhandItem().isEmpty();
            if (taken) {
                return Result.SUCCESS;
            }
        } finally {
            holder.getInventory().clearContent();
        }
        return ammoCount(serverLevel, pos) >= capacityGuess(serverLevel, pos) ? Result.FULL : Result.WRONG_TYPE;
    }

    /**
     * The fake player the click is replayed in the name of. The factory hands out
     * one per level and keeps it for us, so no state has to be carried here.
     */
    private static FakePlayer loaderFor(ServerLevel level) {
        return FakePlayerFactory.getMinecraft(level);
    }

    private static double counters(BlockEntity blockEntity) {
        double total = 0.0;
        for (String key : COUNTER_KEYS) {
            total += blockEntity.getPersistentData().getDouble(key);
        }
        return total;
    }

    /** Message shown when a launcher cannot take the round. */
    public static void notify(Player player, Result result, ItemStack round) {
        if (player == null) {
            return;
        }
        String name = round == null || round.isEmpty() ? "弹药" : round.getHoverName().getString();
        Component message = switch (result) {
            case FULL -> Component.literal("发射器已满，暂时无法装填");
            case WRONG_TYPE -> Component.literal("发射器不接受 " + name);
            case UNSUPPORTED -> Component.literal("该方块不是可以装填的发射器");
            default -> Component.literal("已装填 " + name);
        };
        player.displayClientMessage(message, true);
    }

    /** TAOV launchers, only touched while taov_weapons is installed. */
    private static final class TaovAccess {

        private TaovAccess() {
        }

        static Result insert(Level level, BlockPos pos, ItemStack round) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof com.verr1.taov.weapons.content.launcher.AbstractSingleSlotLauncherBlockEntity launcher)) {
                return null;
            }
            if (!launcher.canAcceptOuterStack(round)) {
                return Result.WRONG_TYPE;
            }
            ItemStack remaining = launcher.insertOuterStack(round.copyWithCount(1), false);
            return remaining.isEmpty() ? Result.SUCCESS : Result.FULL;
        }

        static Result canAccept(Level level, BlockPos pos, ItemStack round) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof com.verr1.taov.weapons.content.launcher.AbstractSingleSlotLauncherBlockEntity launcher)) {
                return null;
            }
            return launcher.canAcceptOuterStack(round) ? Result.SUCCESS : Result.WRONG_TYPE;
        }

        static Integer count(BlockEntity blockEntity) {
            if (blockEntity instanceof com.verr1.taov.weapons.content.launcher.AbstractSingleSlotLauncherBlockEntity launcher) {
                return launcher.remainingAmmo();
            }
            return null;
        }
    }
}
