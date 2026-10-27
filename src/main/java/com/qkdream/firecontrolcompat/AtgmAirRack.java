package com.qkdream.firecontrolcompat;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.entity.IrAtgmMissileEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModBlocks;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 面包学's air-release anti-tank rack - the "反坦克导弹发射器" that is <b>not</b> one of 火控学's
 * ground launchers.
 *
 * <h2>Which blocks these are, and why they are the aircraft ones</h2>
 *
 * <p>面包学 2.6.5 registers several blocks whose display name is exactly
 * 「反坦克导弹发射器」. {@code MianbaoMissileCompat.LAUNCHERS} of 火控学 0.9.4 (its
 * decompile, lines 31-38) knows six of them - {@code antitankemissilelauncher},
 * {@code antitankemissilelauncherh_2}, the two {@code antitankmissilelaunchererxing} halves,
 * {@code antitankmissilelaunchersanxing}, {@code antitankmissilelaunchersixing} - and every one of
 * those fires through an <em>anti-tank</em> work procedure (see
 * {@code MianbaoMissileCompat.findFireMethod}); a bound computer fires them from the ground weapon
 * list. The two the pack calls {@code atgm_missilemixedlauncher} and
 * {@code atgm_missilemixedlauncher_2} are the same display name, are <b>not</b> in that list, and are
 * built on {@code ATGMmissilemixedlauncherBlock}, whose redstone trigger calls
 * {@code AgmmissilemixedrackgoProcedure} - the <b>air-to-ground release chain</b>: muzzle half a
 * block across, a quarter up and 3.6 blocks along the facing, a 1.0 speed throw, the launcher's
 * guidance data copied onto the round, and one round taken out of container slot zero.</p>
 *
 * <p>So the split is not guessed from where the block stands, it is read off the pack: the six that
 * 火控学 fires are the ground ones (this mod keeps the ground route for them, see
 * {@code BeamMissileCompat#fireIrAtgm}), and these two are the air ones. They load exactly like the
 * ground family (the same {@code AntitankmissilelauncherloadProcedure} writes our round into slot
 * zero, see {@code AtgmLauncherLoadMixin}), so what was missing was only the other two halves:
 * being bindable as an aircraft rack and being released through the aircraft trigger.</p>
 *
 * <h2>The release is the pack's own</h2>
 *
 * <p>Nothing here re-implements a launch. The aircraft trigger calls this pack procedure
 * ({@link #release}), which is what a redstone pulse on the block calls too, and the procedure does
 * the whole release: sound, spark and poof particles, the round placed at the pack's own muzzle,
 * the guidance data copied, the round taken out of slot zero. This class only swaps
 * <em>which round</em> comes out: the procedure's body is gated on slot zero holding a native
 * {@code antitankmissile_1/_2}, so the round slot is primed with one for the length of that single
 * call ({@link #begin}), and on the way back the native round the pack spawned is replaced by
 * {@code firecontrolcompat:ir_atgm_tanshe} at the same place with the same heading, and slot zero
 * goes back to our round at one less ({@link #finish}). A rail holding a native round is touched by
 * none of this: {@link #begin} claims the call only when slot zero carries <em>our</em> round.</p>
 */
public final class AtgmAirRack {

    private static final String PREFIX = "[firecontrolcompat] ";

    /** The name this round is shown under on the aircraft ground-weapon slot. */
    public static final String LABEL = IrAtgm.DISPLAY_NAME;

    /** The pack's own round, used only to walk the pack's release body. */
    private static final float RELEASE_SPEED = 3.0F;

    private static final double HIT_BOX = 8.0D;

    private AtgmAirRack() {
    }

    /** The two registry ids that are the air-release rack, read from the pack's registry. */
    public static boolean isAirAtgmRack(BlockState state) {
        if (state == null) {
            return false;
        }
        try {
            Block block = state.getBlock();
            return block == MianbaosModernwarfareModBlocks.ATGM_MISSILEMIXEDLAUNCHER.get()
                    || block == MianbaosModernwarfareModBlocks.ATGM_MISSILEMIXEDLAUNCHER_2.get();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** ...and the same test by block id, which is how the binding compares variants. */
    public static boolean isAirAtgmRackId(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        try {
            return id.equals(BuiltInRegistries.BLOCK.getKey(
                    MianbaosModernwarfareModBlocks.ATGM_MISSILEMIXEDLAUNCHER.get()))
                    || id.equals(BuiltInRegistries.BLOCK.getKey(
                            MianbaosModernwarfareModBlocks.ATGM_MISSILEMIXEDLAUNCHER_2.get()));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Whether the block at this position is one of the two air-release racks.
     *
     * <p>Two readings, because the rack of an aircraft is stored in a Sable plot while the computer
     * that names the slot holds the root level: asking only the level in hand answers {@code false}
     * for a rack that is loaded and armed, and every name decision here is built on this test. That
     * costs the air slot its name - the HUD kept 面包学's "普通空对地导弹" while this mod's round was
     * the one the trigger would release. {@link SubLevelPositions#blockState} is the fallback the
     * rest of the mod already uses for blocks that live in a structure.</p>
     */
    public static boolean isAirAtgmRack(LevelAccessor world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        try {
            if (isAirAtgmRack(world.getBlockState(pos))) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // fall through to the sub-level reading
        }
        try {
            return world instanceof Level level && isAirAtgmRack(SubLevelPositions.blockState(level, pos));
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Whether this rack currently holds this mod's round, which is what makes it ours to fire. */
    public static boolean carriesIrAtgm(LevelAccessor world, BlockPos pos) {
        return BeamMissileCompat.irAtgmRounds(world, pos) > 0;
    }

    /**
     * The pack's own muzzle for this rack, in the space the block is stored in.
     *
     * <p>Copied from {@code AgmmissilemixedrackgoProcedure}: {@code x + 0.5 + facing*3.6},
     * {@code y + 0.25 + facing*3.6}, {@code z + 0.5 + facing*3.6}. It is only ever used to look for
     * the round the procedure spawned and to measure the lock the same way the release does.</p>
     */
    public static Vec3 packMuzzle(BlockPos pos, Direction direction) {
        return new Vec3(
                pos.getX() + 0.5D + direction.getStepX() * 3.6D,
                pos.getY() + 0.25D + direction.getStepY() * 3.6D,
                pos.getZ() + 0.5D + direction.getStepZ() * 3.6D);
    }

    /**
     * The aircraft ground-weapon slot's name, when the trigger is on one of these racks.
     *
     * <p>Fire control names that slot after the rack the trigger would release from, and the walk
     * here is the pack's own cursor order: the first bound rack that answers the selected weapon type
     * is the one a press would fire, so it is also the one the slot is named after. A rail holding a
     * native round answers {@code null} - it keeps the pack's wording, this mod does not rename it.</p>
     */
    public static String slotLabel(Level level, StabilizerControllerBlockEntity controller) {
        if (level == null || controller == null) {
            return null;
        }
        try {
            List<BindingRef> racks = controller.getAircraftMissileRacks();
            if (racks == null || racks.isEmpty()) {
                return null;
            }
            int size = racks.size();
            int start = Math.floorMod(fireCursor(controller), size);
            for (int offset = 0; offset < size; offset++) {
                BindingRef rack = racks.get((start + offset) % size);
                if (rack == null) {
                    continue;
                }
                // Only racks that answer the air-to-ground slot are candidates at all; the first one
                // is the one this press belongs to, so the walk stops there either way.
                if (MianbaoAircraftCompat.weaponAt(level, rack)
                        != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                    continue;
                }
                boolean ours = isAirAtgmRack(level, rack.pos());
                int rounds = ours ? BeamMissileCompat.irAtgmRounds(level, rack.pos()) : 0;
                remember(ours && rounds > 0 ? LABEL : null, rack.pos(), ours, rounds, size, start);
                return ours && rounds > 0 ? LABEL : null;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A computer that cannot be read simply keeps the pack's wording.
        }
        return null;
    }

    /**
     * The last slot name this class decided on, and when.
     *
     * <p>The enum label ({@code MianbaoAircraftCompat$WeaponType#displayName}) is reached from code
     * that never sees a controller - the input page, the bomb sight, the "weapon selected" message -
     * so it cannot ask this class anything. The name is therefore remembered here and read back there
     * inside a short window, which is the same trick the seeker HUD payload uses for the client side
     * of the same question. The window is {@link #LABEL_MEMORY_MILLIS}: long enough to cover a menu
     * visit, short enough that walking away from the aircraft restores 面包学's wording by itself.</p>
     */
    private static volatile String lastLabel;

    private static volatile long lastLabelAt;

    /** How long a remembered slot name stays valid, in milliseconds. */
    private static final long LABEL_MEMORY_MILLIS = 10000L;

    /** The last air-to-ground slot name, or {@code null} when it is not recent enough to use. */
    public static String recentLabel() {
        long at = lastLabelAt;
        if (at == 0L || System.currentTimeMillis() - at > LABEL_MEMORY_MILLIS) {
            return null;
        }
        return lastLabel;
    }

    /**
     * How often {@link #scanLoadedControllers()} runs, in server ticks.
     *
     * <p>One second is far more often than a crew can change what is on a rail and far rarer than
     * anything measurable: the scan walks the computers 火控学 already tracks
     * ({@code ControllerTracker.loadedControllers()}, a handful at most) and asks each of them for
     * its bound rails. It replaces the old design, in which the name was only ever computed from
     * inside the per-tick sync for a <b>seated</b> player - so every reader that runs while the crew
     * is standing at the computer (the input page's weapon rows, the "weapon selected" message, the
     * bomb sight) found nothing and printed 面包学's stock wording even with this mod's round on the
     * rail.</p>
     */
    private static final int SCAN_INTERVAL_TICKS = 20;

    private static int scanTicker;

    /** Last aggregate decision printed, so the line appears per change instead of per scan. */
    private static String firecontrolcompat$lastScanRow = "";

    /**
     * Refreshes the remembered air-slot name from every loaded fire control computer.
     *
     * <p>This is the server-side answer to "the air slot still says 普通空对地导弹": the name is
     * decided here, once a second, whether or not anybody is sitting in the aircraft, and it is
     * cleared the moment no bound air rack carries this mod's round any more. A rack holding 面包学's
     * own {@code antitankmissile_1/_2} answers nothing
     * ({@link MianbaoAtgmRackAircraftCompatMixin} only reports {@code AIR_TO_GROUND_DIRECT} for our
     * round), so a native round keeps the pack's own name.</p>
     */
    public static void scanLoadedControllers() {
        if (scanTicker++ % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        String label = null;
        BlockPos from = null;
        int controllers = 0;
        int withOurs = 0;
        try {
            for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
                try {
                    if (controller == null || controller.isRemoved()) {
                        continue;
                    }
                    Level level = controller.getLevel();
                    if (level == null || level.isClientSide) {
                        continue;
                    }
                    controllers++;
                    String decided = slotLabel(level, controller);
                    if (decided != null) {
                        label = decided;
                        withOurs++;
                        if (from == null) {
                            from = controller.getBlockPos();
                        }
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // a computer that cannot be read simply does not vote
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 火控学 absent: nothing is named
        }
        rememberOverall(label, from, controllers, withOurs);
    }

    /**
     * Makes the aggregate answer the current one, including the "nothing of ours is loaded" answer.
     *
     * <p>The stamp is refreshed on every scan, so a name never outlives the round it was computed
     * from: a rail that is emptied or reloaded with a native round drops out of the scan and the
     * slot goes back to 面包学's wording on the next one.</p>
     */
    private static void rememberOverall(String label, BlockPos from, int controllers, int withOurs) {
        lastLabel = label;
        lastLabelAt = System.currentTimeMillis();
        String row = label + "/" + controllers + "/" + withOurs;
        if (row.equals(firecontrolcompat$lastScanRow)) {
            return;
        }
        firecontrolcompat$lastScanRow = row;
        FireControlCompat.LOGGER.info(
                "{}ir-atgm name: slot=air label={} controllers={} ours={} rack={} owners={}",
                PREFIX, label == null ? "native" : label, controllers, withOurs,
                from == null ? "-" : from.toShortString(), withOurs);
    }

    /** Last naming decision printed, so the line appears per change instead of per frame. */
    private static String firecontrolcompat$lastNameRow = "";

    private static void remember(String label, BlockPos pos, boolean ours, int rounds, int racks, int cursor) {
        lastLabel = label;
        lastLabelAt = System.currentTimeMillis();
        String row = pos + "/" + label + "/" + ours + "/" + rounds;
        if (row.equals(firecontrolcompat$lastNameRow)) {
            return;
        }
        firecontrolcompat$lastNameRow = row;
        FireControlCompat.LOGGER.info(
                "{}ir-atgm name: slot=air rack={} racks={} cursor={} ours={} rounds={} label={}",
                PREFIX, pos, racks, cursor, ours, rounds, label == null ? "native" : label);
    }

    /**
     * Fire control's aircraft fire cursor, read reflectively.
     *
     * <p>{@code StabilizerControllerBlockEntity#aircraftMissileFireCursor} is private and has no
     * getter in 0.9.4 (javap), and this is the only place outside a mixin that needs it. The lookup
     * is cached and every failure answers {@code 0}, which is where the walk starts anyway.</p>
     */
    public static int fireCursor(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return 0;
        }
        try {
            if (FIRE_CURSOR == null) {
                FIRE_CURSOR = StabilizerControllerBlockEntity.class
                        .getDeclaredField("aircraftMissileFireCursor");
                FIRE_CURSOR.setAccessible(true);
            }
            Object value = FIRE_CURSOR.get(controller);
            return value instanceof Integer cursor ? cursor : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static java.lang.reflect.Field FIRE_CURSOR;

    /**
     * Releases one round off this rack through the pack's own release procedure.
     *
     * <p>This is the aircraft trigger's entry point. It calls exactly the procedure the block's
     * redstone trigger calls - so the muzzle, the sound, the particles, the guidance hand-off and
     * the round taken out of the slot are the pack's - and reports success the way 火控学 grades
     * every launch: did the rack end up holding one round less.</p>
     */
    public static boolean release(LevelAccessor world, BlockPos pos) {
        if (world == null || pos == null || world.isClientSide()) {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm air: release skipped reason={} pos={}", PREFIX,
                    world == null ? "no-level" : pos == null ? "no-pos" : "client", pos);
            return false;
        }
        int before = BeamMissileCompat.irAtgmRounds(world, pos);
        FireControlCompat.LOGGER.info(
                "{}ir-atgm air: release enter rack={} block={} level={} server={} rounds={} slot0={}",
                PREFIX, pos.toShortString(), BeamMissileCompat.blockIdAt(world, pos),
                world.getClass().getName(), world instanceof ServerLevel, before, slotText(world, pos));
        if (before <= 0) {
            FireControlCompat.LOGGER.info("{}ir-atgm air: release skipped reason=no-round pos={}", PREFIX,
                    pos.toShortString());
            return false;
        }
        try {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm air: exec procedure=AgmmissilemixedrackgoProcedure at={},{},{}", PREFIX,
                    pos.getX(), pos.getY(), pos.getZ());
            net.mcreator.myfirstmod.procedures.AgmmissilemixedrackgoProcedure.execute(
                    world, pos.getX(), pos.getY(), pos.getZ());
            FireControlCompat.LOGGER.info("{}ir-atgm air: exec done procedure=AgmmissilemixedrackgoProcedure",
                    PREFIX);
        } catch (Throwable failure) {
            abort(world, pos, before);
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm air: the pack's release refused at {}: {}", PREFIX, pos.toShortString(),
                    failure.toString());
            return false;
        }
        int after = BeamMissileCompat.irAtgmRounds(world, pos);
        FireControlCompat.LOGGER.info(
                "{}ir-atgm air: release result rack={} rounds={}->{} ok={} slot0={}", PREFIX,
                pos.toShortString(), before, after, after < before, slotText(world, pos));
        return after < before;
    }

    /**
     * Puts the rack back the way {@link #begin} found it after a release that never completed.
     *
     * <p>The slot was primed with a native round for the length of one call; a call that throws never
     * reaches {@link #finish}, so without this the rack would be left holding 面包学's round - a round
     * this mod never loaded and the crew cannot fire.</p>
     */
    public static void abort(LevelAccessor world, BlockPos pos, int rounds) {
        PENDING.remove();
        FireControlCompat.LOGGER.info("{}ir-atgm air: abort restore rack={} rounds={} slot0-before={}",
                PREFIX, pos == null ? "-" : pos.toShortString(), rounds,
                pos == null ? "-" : slotText(world, pos));
        try {
            BeamMissileCompat.setAtgmSlot(world, pos, rounds <= 0 ? ItemStack.EMPTY
                    : new ItemStack(BeamMissileRegistry.IR_ATGM.get(), rounds));
            FireControlCompat.LOGGER.info("{}ir-atgm air: abort restored slot0={}", PREFIX,
                    slotText(world, pos));
        } catch (RuntimeException | LinkageError ignored) {
            // nothing more can be done for a slot that will not take a write
        }
    }

    /**
     * What one call of the pack's release procedure is holding: the round it primed the slot with,
     * the designation it resolved, and the rounds standing on the rack before the call.
     */
    private record Pending(BlockPos pos, int rounds, BeamMissileCompat.Lock lock, Vec3 muzzle,
            Direction facing, Set<Integer> before) {
    }

    /**
     * The thread-local hand-off between the head and the return of one procedure call.
     *
     * <p>The pack's release is a single static call, so the two injections are two separate
     * callbacks with nothing between them but that call. A thread-local is the same arrangement the
     * anti-radiation rack used for its own before/after: the server ticks on one thread, the block
     * is only ever released from inside that call, and the value is taken (not read) so a failed call
     * cannot leave it behind for the next one.</p>
     */
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    /** The native round the pack spawns for a {@code antitankmissile_2} rack, primed for that call. */
    private static ItemStack prime(LevelAccessor world, BlockPos pos, int rounds) {
        try {
            ItemStack nativeRound = new ItemStack(MianbaosModernwarfareModItems.ANTITANKMISSILE_2.get(), rounds);
            return BeamMissileCompat.setAtgmSlot(world, pos, nativeRound) ? nativeRound : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Head of the pack's release: claims the call when the rack carries this mod's round.
     *
     * @return {@code true} when this call is ours - the caller must cancel the procedure. That is
     *         the refusal case (no designation) and the "cannot prime" case; when the call is handed
     *         to the pack the answer is {@code false} and the procedure runs with the primed slot
     */
    public static boolean begin(LevelAccessor world, double x, double y, double z) {
        if (world == null || world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!isAirAtgmRack(world, pos)) {
            return false;
        }
        int rounds = BeamMissileCompat.irAtgmRounds(world, pos);
        Direction facing = BeamMissileCompat.facing(world, pos);
        Vec3 muzzle = packMuzzle(pos, facing);
        FireControlCompat.LOGGER.info(
                "{}ir-atgm air: prime begin rack={} block={} level={} server={} rounds={} slot0={} facing={} muzzle={}",
                PREFIX, pos.toShortString(), BeamMissileCompat.blockIdAt(world, pos),
                world.getClass().getName(), world instanceof ServerLevel, rounds, slotText(world, pos),
                facing, muzzle);
        if (rounds <= 0) {
            FireControlCompat.LOGGER.info("{}ir-atgm air: prime refused reason=no-round slot0={} rack={}",
                    PREFIX, slotText(world, pos), pos.toShortString());
            return false;
        }
        BeamMissileCompat.Lock lock = BeamMissileCompat.irAtgmLock(world, pos, muzzle);
        if (lock == null) {
            // The pack's body would refuse this round anyway (it only knows its own), so the press is
            // answered here: the round stays on the rack and the crew is told what is missing. The
            // line is unconditional now - a refused press is the one thing that has to be visible in
            // the log, and the lock probe line right above it says which sources were asked.
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm fire: refused reason=no-lock route=air rack={} block={} rounds={} slot0={} pos={}",
                    PREFIX, pos.toShortString(), BeamMissileCompat.blockIdAt(world, pos), rounds,
                    slotText(world, pos), pos.toShortString());
            if (BeamMissileCompat.lockRefusalIsNews(pos)) {
                tellNearby(world, pos);
            }
            return true;
        }
        Set<Integer> before = idsNear(world, pos, muzzle);
        String slotBefore = slotText(world, pos);
        boolean primed = prime(world, pos, rounds) != null;
        String slotAfter = slotText(world, pos);
        FireControlCompat.LOGGER.info(
                "{}ir-atgm air: prime slot=0 item={} -> {} ok={} lock={} target={} detail={}",
                PREFIX, slotBefore, slotAfter, primed, BeamMissileCompat.lockName(lock),
                lock.point(), lock.detail());
        if (!primed) {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm air: no round slot at {} (rounds={})", PREFIX, pos.toShortString(), rounds);
            return true;
        }
        PENDING.set(new Pending(pos, rounds, lock, muzzle, facing, before));
        return false;
    }

    /**
     * Return of the pack's release: puts this mod's round where the native one came out.
     *
     * <p>Slot zero is restored first, because everything else here is diagnostics: the pack left the
     * slot one native round short, and it goes back to our round at the same count. The native round
     * the pack spawned is then taken off the level and replaced - at the very position and heading the
     * pack gave it - by {@code ir_atgm_tanshe}, armed with the designation resolved in
     * {@link #begin} so the round knows what to climb over.</p>
     */
    public static void finish(LevelAccessor world, double x, double y, double z) {
        Pending pending = PENDING.get();
        PENDING.remove();
        if (pending == null) {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm air: finish skipped reason=no-pending at={},{},{}", PREFIX, x, y, z);
            return;
        }
        BlockPos pos = pending.pos();
        int left = Math.max(0, pending.rounds() - 1);
        String slotBefore = slotText(world, pos);
        try {
            ItemStack restored = left > 0
                    ? new ItemStack(BeamMissileRegistry.IR_ATGM.get(), left) : ItemStack.EMPTY;
            boolean written = BeamMissileCompat.setAtgmSlot(world, pos, restored);
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm air: finish restored={} wrote={} slot0={} -> {} delta={}->{}",
                    PREFIX, left > 0 ? "ir_atgm x" + left : "empty", written, slotBefore,
                    slotText(world, pos), pending.rounds(), BeamMissileCompat.irAtgmRounds(world, pos));
        } catch (RuntimeException | LinkageError ignored) {
            // the round count is the pack's slot; a failed write is reported by the line below
        }
        java.util.List<Entity> found = newRounds(world, pending);
        Entity nativeRound = found.isEmpty() ? null : found.get(0);
        Vec3 at = nativeRound == null ? null : nativeRound.position();
        FireControlCompat.LOGGER.info(
                "{}ir-atgm air: spawn found={} entity={} at={} muzzle={} native={}",
                PREFIX, found.size(), nativeRound == null ? "-" : firecontrolcompat$id(nativeRound),
                at, pending.muzzle(), nativeRound != null);
        if (nativeRound != null) {
            nativeRound.discard();
        }
        IrAtgmMissileEntity round = spawn(world, pending, at);
        FireControlCompat.LOGGER.info(
                "{}ir-atgm air: spawn ours={} removedNative={} rounds={}->{} slot0={} entity={}",
                PREFIX, round != null, nativeRound != null, pending.rounds(),
                BeamMissileCompat.irAtgmRounds(world, pos), slotText(world, pos),
                round == null ? "-" : firecontrolcompat$id(round));
        FireControlCompat.LOGGER.info(
                "{}ir-atgm fire: via=aircraft route=air lock={} detail={} rack={} spawn={} type={} tick={}"
                        + " rounds={}->{} added={} native={} stored=slot0 target={} muzzle={} pos={}",
                PREFIX, BeamMissileCompat.lockName(pending.lock()), pending.lock().detail(),
                BeamMissileCompat.blockIdAt(world, pos),
                BeamMissileCompat.spawnUuid(round),
                round == null ? "-" : firecontrolcompat$id(round),
                BeamMissileCompat.spawnedTick(round),
                pending.rounds(), BeamMissileCompat.irAtgmRounds(world, pos), round != null,
                nativeRound != null, pending.lock().point(), pending.muzzle(), pos.toShortString());
    }

    /** The round that leaves the rack, placed where the pack put its own. */
    private static IrAtgmMissileEntity spawn(LevelAccessor world, Pending pending, Vec3 at) {
        ServerLevel level = serverLevelOf(world, pending.pos());
        if (level == null) {
            FireControlCompat.LOGGER.info("{}ir-atgm air: spawn skipped reason=no-server-level world={}",
                    PREFIX, world == null ? "-" : world.getClass().getName());
            return null;
        }
        // The round is placed at the rack's world muzzle, so the designation has to be handed over in
        // that same space: a lock left in the sub-level's stored coordinates would be twenty million
        // blocks from the round and the climb/dive maths would steer at nothing.
        Vec3 worldLock = worldPoint(level, pending.lock().point());
        IrAtgmMissileEntity round = (IrAtgmMissileEntity) BeamMissileCompat.spawnBeamMissileAtWorldMuzzle(
                level, pending.pos(), pending.muzzle(), pending.facing(), RELEASE_SPEED,
                BeamMissileRegistry.IR_ATGM_TANSHE.get(), BeamMissileCompat.carrierVelocityAt(level, pending.pos()),
                worldLock);
        if (round == null) {
            return null;
        }
        round.setBaseDamage(20.0F);
        // The point is already on the round (it has to be, or fire control's mathematical flight
        // would capture a round that does not know what to climb over); this only says so.
        FireControlCompat.LOGGER.info("{}ir-atgm air: spawn armed lock={}->world={} roundAt={}",
                PREFIX, pending.lock().point(), worldLock, round.position());
        if (at != null && at.distanceToSqr(round.position()) > 1.0E-4D) {
            // The pack's own spawn point, projected out of the sub-level: the round is drawn and flown
            // where the rack is seen, not in the plot region its blocks are stored in.
            Vec3 world1 = SubLevelPositions.worldPosition(level, at);
            if (world1 != null && world1.distanceToSqr(round.position()) < 64.0D) {
                round.setPos(world1.x, world1.y, world1.z);
                round.setOldPosAndRot();
            }
        }
        IrAtgm.reportLaunch(round, worldLock, true);
        return round;
    }

    /** A stored point of the level's own plot, expressed where the rack is actually seen. */
    private static Vec3 worldPoint(ServerLevel level, Vec3 point) {
        if (point == null) {
            return null;
        }
        try {
            Vec3 world = SubLevelPositions.worldPosition(level, point);
            return world == null ? point : world;
        } catch (RuntimeException | LinkageError ignored) {
            return point;
        }
    }

    /**
     * The level entities have to be created in and searched in.
     *
     * <p>A rack can be reported through a plot level instead of the server level that owns it, and
     * every entity call here needs the server level: creating a round in any other level answers
     * {@code null}, and searching for the pack's round in one finds nothing. Both readings are
     * offered.</p>
     */
    private static ServerLevel serverLevelOf(LevelAccessor world, BlockPos pos) {
        if (world instanceof ServerLevel server) {
            return server;
        }
        if (!(world instanceof Level level)) {
            return null;
        }
        try {
            Level plotOrRoot = SubLevelPositions.level(level, pos);
            if (plotOrRoot instanceof ServerLevel server) {
                return server;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no structure: nothing to convert
        }
        return null;
    }

    /** What slot zero of a rack holds right now, as {@code id xN} or {@code empty}. */
    static String slotText(LevelAccessor world, BlockPos pos) {
        try {
            ItemStack stack = BeamMissileCompat.atgmSlotStack(world, pos);
            if (stack == null || stack.isEmpty()) {
                return "empty";
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id + "x" + stack.getCount();
        } catch (RuntimeException | LinkageError ignored) {
            return "-";
        }
    }

    /** The native round the pack just spawned, recognised as the one that was not there before. */
    private static java.util.List<Entity> newRounds(LevelAccessor world, Pending pending) {
        java.util.List<Entity> found = new java.util.ArrayList<>();
        ServerLevel level = serverLevelOf(world, pending.pos());
        if (level == null) {
            return found;
        }
        Set<Integer> seen = new HashSet<>();
        try {
            for (AABB box : searchBoxes(level, pending)) {
                for (Entity candidate : level.getEntitiesOfClass(Entity.class, box)) {
                    if (candidate == null || candidate.isRemoved()
                            || pending.before().contains(candidate.getId())
                            || !seen.add(candidate.getId())) {
                        continue;
                    }
                    found.add(candidate);
                }
            }
            found.sort(java.util.Comparator.comparingDouble(
                    candidate -> candidate.position().distanceToSqr(pending.muzzle())));
        } catch (RuntimeException | LinkageError ignored) {
            // an empty list only means no native round could be identified
        }
        return found;
    }

    /**
     * Where the pack's round may have ended up: the muzzle in the rack's own space, and the same
     * muzzle projected out of the structure.
     *
     * <p>Sable can lift an entity added at a structure's stored position out into world space on
     * {@code addFreshEntity} (fire control's own rack path projects the muzzle for exactly that
     * reason). Searching both boxes keeps the native round identifiable in either case, so it is
     * always taken off the level instead of being left behind as a stray.</p>
     */
    private static java.util.List<AABB> searchBoxes(ServerLevel level, Pending pending) {
        java.util.List<AABB> boxes = new java.util.ArrayList<>(2);
        boxes.add(AABB.ofSize(pending.muzzle(), HIT_BOX, HIT_BOX, HIT_BOX));
        try {
            Vec3 world = SubLevelPositions.worldPosition(level, pending.muzzle());
            if (world != null && world.distanceToSqr(pending.muzzle()) > 1.0E-4D) {
                boxes.add(AABB.ofSize(world, HIT_BOX, HIT_BOX, HIT_BOX));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no projection: the stored-space box is the only reading
        }
        return boxes;
    }

    /** Entity ids standing near the muzzle before the pack runs, so its own round can be told out. */
    private static Set<Integer> idsNear(LevelAccessor world, BlockPos pos, Vec3 muzzle) {
        Set<Integer> ids = new HashSet<>();
        ServerLevel level = serverLevelOf(world, pos);
        if (level == null) {
            return ids;
        }
        try {
            Pending probe = new Pending(pos, 0, null, muzzle, null, Set.of());
            for (AABB box : searchBoxes(level, probe)) {
                for (Entity entity : level.getEntitiesOfClass(Entity.class, box)) {
                    if (entity != null) {
                        ids.add(entity.getId());
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // an empty set only widens the search in newRounds, it never loses the round
        }
        return ids;
    }

    /** The registry id of a round, for the launch line. */
    private static String firecontrolcompat$id(Entity round) {
        try {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(round.getType());
            return id == null ? String.valueOf(round.getType()) : String.valueOf(id);
        } catch (Throwable ignored) {
            return "firecontrolcompat:ir_atgm_tanshe";
        }
    }

    private static void tellNearby(LevelAccessor world, BlockPos pos) {
        ServerLevel level = serverLevelOf(world, pos);
        if (level == null) {
            return;
        }
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) <= 256.0D) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("需要光电锁定"), true);
            }
        }
    }
}
