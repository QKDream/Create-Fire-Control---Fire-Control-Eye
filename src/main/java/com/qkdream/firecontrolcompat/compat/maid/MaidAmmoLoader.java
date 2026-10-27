package com.qkdream.firecontrolcompat.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.qkdream.firecontrolcompat.AmmoClipboardItem;
import com.qkdream.firecontrolcompat.AmmoLoadingCompat;
import com.qkdream.firecontrolcompat.AmmoRackCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.SubLevelPositions;
import com.qkdream.firecontrolcompat.crate.AmmoCrateBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Work of the "酒狐装弹" maid.
 *
 * <p>She walks to the ammunition store she was bound to - the crate the
 * loading table recorded, the closest crate, or a water-jacket ammunition rack
 * whose filter says which round it wants - takes one round, carries it to the
 * bound launchers and loads it the same way a player's right click would. The
 * order may hold several launchers of the same kind and she fills them one
 * after the other, carrying a round past a full one instead of walking back to
 * the store for nothing. One
 * round per minute, or one every thirty seconds once she is at the highest
 * favourability level. A round the launcher refuses is carried back, and the
 * player who ordered the wrong ammunition gets a punch for it.</p>
 */
public final class MaidAmmoLoader {

    /** How close she has to stand to a block before handling it. */
    private static final double STORE_REACH = 3.0 * 3.0;
    private static final double LAUNCHER_REACH = 3.5 * 3.5;

    /** Search radius around the launcher when no crate was recorded. */
    private static final double CRATE_SEARCH = 32.0;
    private static final double RACK_SEARCH = 16.0;

    /** Ticks between two rounds: one minute, half of it at the highest level. */
    private static final long INTERVAL_SLOW = 1200L;
    private static final long INTERVAL_FAST = 600L;

    /** Pauses used when the launcher cannot take the round right now. */
    private static final long RETRY_PAUSE = 120L;
    /** Short pause while stepping from a full launcher to the next one. */
    private static final long SKIP_PAUSE = 20L;
    private static final long REFUSAL_PAUSE = 200L;

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private MaidAmmoLoader() {
    }

    static boolean isLoader(EntityMaid maid) {
        return maid != null && TaskAmmoLoader.UID.equals(maid.getTask().getUid());
    }

    static void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (maid.isPassenger() || maid.isMaidInSittingPose()) {
            return;
        }
        CompoundTag data = maid.getPersistentData();
        List<BlockPos> launchers = AmmoClipboardItem.boundLaunchers(data, level);
        if (launchers.isEmpty()) {
            return;
        }
        State state = STATES.computeIfAbsent(maid.getUUID(), id -> new State());
        if (gameTime < state.readyTick) {
            return;
        }
        if (state.launcherIndex < 0 || state.launcherIndex >= launchers.size()) {
            state.launcherIndex = 0;
        }
        BlockPos launcher = launchers.get(state.launcherIndex);
        ItemStack held = maid.getMainHandItem();
        if (held.isEmpty()) {
            carryOne(level, maid, state, data, launchers, launcher, gameTime);
        } else {
            loadOne(level, maid, state, held, launchers, launcher, gameTime);
        }
    }

    static void release(ServerLevel level, EntityMaid maid) {
        STATES.remove(maid.getUUID());
    }

    private static void carryOne(ServerLevel level, EntityMaid maid, State state, CompoundTag data,
            List<BlockPos> launchers, BlockPos launcher, long gameTime) {
        BlockPos store = storeFor(level, maid, data, launcher);
        if (store == null) {
            say(maid, "没有找到弹药箱");
            state.readyTick = gameTime + REFUSAL_PAUSE;
            return;
        }
        if (!near(level, maid, store, STORE_REACH)) {
            walkTo(level, maid, store);
            return;
        }
        ItemStack round = take(level, store);
        if (round.isEmpty()) {
            say(maid, "弹药箱里没有弹药");
            state.readyTick = gameTime + REFUSAL_PAUSE;
            return;
        }
        maid.setItemInHand(InteractionHand.MAIN_HAND, round);
        state.store = store.immutable();
        state.fullChecks = 0;
        // Head for a launcher of the order that says it takes this round.
        state.launcherIndex = chooseLauncher(level, launchers, round, state.launcherIndex);
        say(maid, "取弹：" + round.getHoverName().getString());
    }

    private static void loadOne(ServerLevel level, EntityMaid maid, State state, ItemStack round,
            List<BlockPos> launchers, BlockPos launcher, long gameTime) {
        if (!near(level, maid, launcher, LAUNCHER_REACH)) {
            walkTo(level, maid, launcher);
            return;
        }
        AmmoLoadingCompat.Result result = AmmoLoadingCompat.tryInsert(level, launcher, round);
        switch (result) {
            case SUCCESS -> {
                maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                say(maid, "装填完成：" + round.getHoverName().getString() + which(state, launchers));
                state.readyTick = gameTime + interval(maid);
                state.fullChecks = 0;
                advance(state, launchers.size());
            }
            case FULL -> {
                // Keep the round in hand and try the next launcher of the order.
                if (launchers.size() > 1 && state.fullChecks + 1 < launchers.size()) {
                    state.fullChecks++;
                    advance(state, launchers.size());
                    state.readyTick = gameTime + SKIP_PAUSE;
                } else {
                    putBack(level, maid, state, round);
                    say(maid, "发射器已满");
                    state.fullChecks = 0;
                    state.readyTick = gameTime + RETRY_PAUSE;
                }
            }
            case WRONG_TYPE -> {
                putBack(level, maid, state, round);
                say(maid, "弹药不对，打你一下");
                punch(maid);
                state.fullChecks = 0;
                state.readyTick = gameTime + REFUSAL_PAUSE;
            }
            case UNSUPPORTED -> {
                // The recorded block is gone or is not something she can load.
                putBack(level, maid, state, round);
                say(maid, "这里没法装填");
                state.fullChecks = 0;
                advance(state, launchers.size());
                state.readyTick = gameTime + REFUSAL_PAUSE;
            }
            default -> {
                putBack(level, maid, state, round);
                say(maid, "这里没法装填");
                state.fullChecks = 0;
                state.readyTick = gameTime + REFUSAL_PAUSE;
            }
        }
    }

    /** Moves the order on to the next launcher of the list. */
    private static void advance(State state, int size) {
        if (size <= 0) {
            return;
        }
        state.launcherIndex = (state.launcherIndex + 1) % size;
    }

    /**
     * The launcher the round in her hands should go to: the next one of the order
     * that says it takes this round, starting where she left off. Launchers that
     * are full or gone are skipped, so a full one costs no trip.
     */
    private static int chooseLauncher(ServerLevel level, List<BlockPos> launchers, ItemStack round, int from) {
        if (launchers.size() <= 1) {
            return 0;
        }
        for (int step = 0; step < launchers.size(); step++) {
            int index = Math.floorMod(from + step, launchers.size());
            if (AmmoLoadingCompat.canAccept(level, launchers.get(index), round) == AmmoLoadingCompat.Result.SUCCESS) {
                return index;
            }
        }
        return Math.floorMod(from, launchers.size());
    }

    /** "（第 2/3 具）" while more than one launcher shares the order. */
    private static String which(State state, List<BlockPos> launchers) {
        return launchers.size() > 1 ? "（第 " + (state.launcherIndex + 1) + "/" + launchers.size() + " 具）" : "";
    }

    /** Where she takes rounds from: the recorded store first, then a nearby crate or rack. */
    private static BlockPos storeFor(ServerLevel level, EntityMaid maid, CompoundTag data, BlockPos launcher) {
        BlockPos recorded = AmmoClipboardItem.boundCrate(data, level);
        if (recorded != null && usable(level, recorded)) {
            return recorded;
        }
        Vec3 stored = Vec3.atCenterOf(launcher);
        Vec3 seen = SubLevelPositions.worldPosition(level, stored);
        boolean splitByPlot = seen.distanceToSqr(stored) > 1.0;
        BlockPos crate = nearestCrate(level, stored, CRATE_SEARCH);
        if (crate == null && splitByPlot) {
            // The launcher rides a structure while the store stands in the world.
            crate = nearestCrate(level, seen, CRATE_SEARCH);
        }
        if (crate != null) {
            return crate;
        }
        BlockPos rack = AmmoRackCompat.findNearest(level, stored, RACK_SEARCH);
        if (rack == null && splitByPlot) {
            rack = AmmoRackCompat.findNearest(level, seen, RACK_SEARCH);
        }
        return rack;
    }

    private static boolean usable(ServerLevel level, BlockPos pos) {
        return SubLevelPositions.blockEntity(level, pos) instanceof AmmoCrateBlockEntity
                || AmmoRackCompat.isRack(level, pos);
    }

    private static ItemStack take(ServerLevel level, BlockPos pos) {
        if (SubLevelPositions.blockEntity(level, pos) instanceof AmmoCrateBlockEntity crate) {
            return crate.extractOne();
        }
        return AmmoRackCompat.take(level, pos);
    }

    /** Carries a round the launcher would not take back to where it came from. */
    private static void putBack(ServerLevel level, EntityMaid maid, State state, ItemStack round) {
        BlockPos store = state.store;
        if (store != null && SubLevelPositions.blockEntity(level, store) instanceof AmmoCrateBlockEntity crate
                && crate.insert(round) > 0) {
            maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return;
        }
        if (store != null && AmmoRackCompat.store(level, store, round)) {
            maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            return;
        }
        BlockPos rack = AmmoRackCompat.findNearest(level, maid.position(), RACK_SEARCH);
        if (rack != null && AmmoRackCompat.store(level, rack, round)) {
            maid.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    private static BlockPos nearestCrate(ServerLevel level, Vec3 from, double radius) {
        BlockPos origin = BlockPos.containing(from);
        int span = (int) Math.ceil(radius);
        BlockPos best = null;
        double bestDistance = radius * radius;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-span, -span, -span), origin.offset(span, span, span))) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof AmmoCrateBlockEntity)) {
                continue;
            }
            double distance = Vec3.atCenterOf(pos).distanceToSqr(from);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    private static long interval(EntityMaid maid) {
        int level = maid.getFavorabilityManager().getLevel();
        return level >= 3 ? INTERVAL_FAST : INTERVAL_SLOW;
    }

    /**
     * Walks to the block in the space the maid herself lives in: a maid riding a
     * structure walks in plot space, a maid standing in the world walks to where the
     * structure is seen, and the spot she heads for is chosen to match.
     */
    private static void walkTo(ServerLevel level, EntityMaid maid, BlockPos pos) {
        Vec3 target = targetFor(level, maid, pos);
        maid.getNavigation().moveTo(target.x, target.y, target.z, 1.0);
        maid.getLookControl().setLookAt(target.x, target.y, target.z);
    }

    /** Whether the maid already stands within reach of the block. */
    private static boolean near(ServerLevel level, EntityMaid maid, BlockPos pos, double reachSqr) {
        return maid.position().distanceToSqr(targetFor(level, maid, pos)) <= reachSqr;
    }

    private static Vec3 targetFor(ServerLevel level, EntityMaid maid, BlockPos pos) {
        return SubLevelPositions.inPlotSpace(level, maid.position())
                ? Vec3.atCenterOf(pos)
                : SubLevelPositions.worldCenter(level, pos);
    }

    private static void punch(EntityMaid maid) {
        EntityMaid self = maid;
        self.setSwingingArms(true);
        LivingEntity owner = self.getOwner();
        if (owner instanceof Player player && player.distanceToSqr(self) <= 16.0
                && !player.isCreative() && !player.isSpectator()) {
            player.hurt(self.damageSources().mobAttack(self), 1.0F);
        }
    }

    private static void say(EntityMaid maid, String text) {
        FireControlCompat.LOGGER.debug("[firecontrolcompat] ammo maid {}: {}", maid.getUUID(), text);
        LivingEntity owner = maid.getOwner();
        if (owner instanceof Player player && player.distanceToSqr(maid) <= 400.0) {
            player.displayClientMessage(Component.literal("女仆：" + text), true);
        }
    }

    /** Per maid bookkeeping: the current round's trip and the load clock. */
    private static final class State {
        private BlockPos store;
        private long readyTick;
        /** Which launcher of the order she is feeding. */
        private int launcherIndex;
        /** Launchers found full in a row while one round is in her hands. */
        private int fullChecks;
    }
}
