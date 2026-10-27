package com.qkdream.firecontrolcompat;

import com.qkdream.firecontrolcompat.entity.BeamRidingMissileEntity;
import com.qkdream.firecontrolcompat.laser.LaserSpots;
import com.qkdream.firecontrolcompat.entity.InfraredMissileEntity;
import com.qkdream.firecontrolcompat.entity.IrAtgmMissileEntity;
import com.qkdream.firecontrolcompat.entity.LoiteringMissileEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Map;
import java.util.UUID;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModBlocks;
import net.mcreator.myfirstmod.util.MissileGuidanceData;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
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
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.Level.ExplosionInteraction;
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

    /** Prefix of every diagnostic line this class prints. */
    private static final String PREFIX = "[firecontrolcompat] ";

    /** Last lock refusal printed per launcher, so a held trigger cannot flood the log. */
    private static final Map<Long, Long> LOCK_REFUSALS = new java.util.HashMap<>();

    public static final String BEAM_AMMO_KEY = "fcbeamammo";
    public static final String IR_AA_AMMO_KEY = "fcirraammo";
    public static final String IR_ATGM_AMMO_KEY = "fcirratgm";

    /** Ammunition count of the infrared anti-tank round (locked, top attack, HEAT). */
    public static final String IR_ATGM_TOP_AMMO_KEY = "fciratgmtop";
    public static final String IR_RACK_KEY = "fcirrack";

    /**
     * How far off its own axis a ground infrared round may leave the tube, in degrees.
     *
     * <p>The round leaves along the locked contact rather than along the launcher's facing, which is
     * what "off-boresight launch" means: a battery does not have to be pointed at what it shoots,
     * the seeker is. The limit exists because a launch tube is not a turret - past this angle the
     * round would have to turn harder than its motor can in the first ticks, so the launcher's own
     * line is used instead and the seeker has to work the contact on its own. Seventy-five degrees
     * is wide enough to cover the ±90° the hand-off asked for while still keeping the round pointing
     * forwards out of the tube (a shot straight backwards would set off at the rail).</p>
     */
    public static final double OFF_BORESIGHT_HALF_ANGLE_DEGREES = 75.0;

    /**
     * Where one round is pointed the moment it leaves the tube.
     *
     * @param direction            unit launch direction, in the same space as the muzzle
     * @param offBoresightDegrees  angle between the tube's own line and {@code direction}
     * @param target               the locked point in the launcher's own space, or null
     * @param offAxis              whether the shot is really off-axis (the limit was honoured)
     */
    public record Release(Vec3 direction, double offBoresightDegrees, Vec3 target, boolean offAxis) {
    }

    /**
     * The rack flag a semi-active round leaves behind, so the rack fires the right round.
     *
     * <p><b>TEMP-DISABLED(semi-active)</b>: the semi-active round is withdrawn for now (see
     * {@code BeamMissileRegistry}), so nothing writes this flag any more. The key is kept because
     * every code path that <em>clears</em> a rail still removes it - a rail loaded by an older
     * build must come back empty instead of answering for a round that can no longer fire.</p>
     */
    public static final String SA_RACK_KEY = "fcsarack";

    /**
     * The rack flag an <em>earlier</em> build of this mod left behind for an anti-radiation round.
     *
     * <p>That build kept the rack an empty {@code rack} and wrote this flag, because it cancelled the
     * pack's own load. The load is the pack's block replacement now - the loaded
     * {@code antiradiationmissilerack} block <em>is</em> the round - so nothing writes this flag any
     * more. It is still read, so a rail loaded by the old build keeps answering until its round is
     * fired or taken off, and the load hook clears it when the rail is reloaded the pack's way.</p>
     */
    public static final String ARM_RACK_KEY = "fcarmrack";

    /**
     * <b>RETIRED(ir-arm-rounds)</b>: this build has no anti-radiation rail of its own any more.
     *
     * <p>火控学 ships both of those rounds officially in its next version, so everything this mod
     * did around them is gone: {@code AntiRadiationRackCompat}, {@code AntiRadiationSeeker}, the
     * airborne infrared round's item and entity, and the four mixins that loaded, named or released
     * them ({@code AntiRadiationMissileMixin}, {@code AntiRadiationRackLoadMixin},
     * {@code AntiRadiationRackAircraftCompatMixin}, {@code AircraftIrFreeFireMixin}). A rail of that
     * kind is now only ever the pack's own block, handled by the pack's own code.</p>
     */
    public static boolean rackCarriesAntiRadiation(net.minecraft.world.level.Level level, BlockPos pos) {
        return false;
    }

    /** Whether the stack is 面包学's anti-radiation round. RETIRED: see above. */
    public static boolean isAntiRadiationMissile(ItemStack stack) {
        return false;
    }

    /**
     * Whether this rack currently carries a semi-active round.
     *
     * <p><b>TEMP-DISABLED(semi-active)</b>: the round is not registered, so no rail can carry it
     * any more and this answers {@code false} on purpose. The flag check below is kept commented
     * so restoring the round is a two-line change.</p>
     */
    public static boolean rackCarriesSemiActive(net.minecraft.world.level.Level level, BlockPos pos) {
        // TEMP-DISABLED(semi-active): return nbtFlag(level, pos, SA_RACK_KEY);
        return false;
    }

    /** Whether this rack still carries any round this mod puts on a rail. */
    public static boolean rackCarriesAMountedRound(net.minecraft.world.level.Level level, BlockPos pos) {
        // TEMP-DISABLED(semi-active): the SA term is gone with the round.
        // return nbtFlag(level, pos, SA_RACK_KEY) || nbtFlag(level, pos, IR_RACK_KEY)
        //         || nbtFlag(level, pos, ARM_RACK_KEY);
        // RETIRED(ir-arm-rounds): nothing of this mod's is ever mounted on a rail any more.
        return false;
    }

    /**
     * Whether a rack still wants the display round standing on it, erring on the side of keeping it.
     *
     * <p>A missing block entity is not evidence of an empty rack: the block can be readable through
     * one level while its block entity is not, and a round dropped for that reason leaves a rail
     * that looks unloaded for the rest of the flight. Only a rack block that is gone, or a readable
     * block entity with none of the round flags left, releases the round.</p>
     */
    public static boolean rackKeepsItsRound(net.minecraft.world.level.Level level, BlockPos pos) {
        try {
            Block block = level.getBlockState(pos).getBlock();
            if (block != MianbaosModernwarfareModBlocks.RACK.get()
                    && block != MianbaosModernwarfareModBlocks.ANTIRADIATIONMISSILERACK.get()) {
                return false;
            }
        } catch (Throwable ignored) {
            return true;
        }
        BlockEntity blockEntity = blockEntityAt(level, pos);
        if (blockEntity == null) {
            return true;
        }
        try {
            CompoundTag data = blockEntity.getPersistentData();
            // TEMP-DISABLED(semi-active): the SA flag is no longer written, only cleared.
            // return data.getBoolean(SA_RACK_KEY) || data.getBoolean(IR_RACK_KEY)
            //         || data.getBoolean(ARM_RACK_KEY);
            // RETIRED(ir-arm-rounds): the rail flags are only ever cleared now.
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }
    public static final String LOITER_ATGM_AMMO_KEY = "fcloiter";
    /** Easter egg: spruce saplings loaded into an anti-air launcher as rounds. */
    public static final String SPRUCE_AMMO_KEY = "fcspruceammo";
    /** Blast the spruce round leaves where the radar lock points. */
    public static final float SPRUCE_EXPLOSION_STRENGTH = 40.0F;

    public static final String AA_AMMO_KEY = "missileammo";
    public static final String ATGM_AMMO_KEY_1 = "missile1ammo";
    public static final String ATGM_AMMO_KEY_2 = "missile2ammo";
    public static final String MIXED_AA_KEY = "AAmissile";
    public static final String MIXED_AT_KEY_1 = "ATmissile1";
    public static final String MIXED_AT_KEY_2 = "ATmissile2";

    private BeamMissileCompat() {
    }

    /**
     * The beam-riding round is retired, so nothing counts as one any more.
     *
     * <p>Kept as a test that always answers false rather than deleted, because every launcher path
     * below still asks the question and the answer has to stay a constant "no".</p>
     */
    public static boolean isBeamMissile(ItemStack stack) {
        return false;
    }

    public static boolean isInfraredMissile(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.INFRARED_MISSILE.get();
    }

    /** The infrared anti-tank round: locked, top attack, HEAT warhead. See {@link IrAtgm}. */
    public static boolean isIrAtgm(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.IR_ATGM.get();
    }

    public static boolean isAircraftInfraredMissile(ItemStack stack) {
        // RETIRED(ir-arm-rounds): the airborne infrared round is not registered any more.
        return false;
    }

    /** Easter egg ammunition: the vanilla spruce sapling. */
    public static boolean isSpruceSapling(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == Items.SPRUCE_SAPLING;
    }

    public static boolean isLoiteringMunition(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == BeamMissileRegistry.LOITERING_MUNITION.get();
    }

    public static ItemStack heldItem(Entity entity) {
        return entity instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
    }

    public static double nbtDouble(LevelAccessor world, BlockPos pos, String key) {
        BlockEntity blockEntity = blockEntityAt(world, pos);
        return blockEntity != null ? blockEntity.getPersistentData().getDouble(key) : 0.0;
    }

    public static void nbtPutDouble(LevelAccessor world, BlockPos pos, String key, double value) {
        BlockEntity blockEntity = blockEntityAt(world, pos);
        if (blockEntity != null) {
            blockEntity.getPersistentData().putDouble(key, value);
            blockEntity.setChanged();
        }
    }

    public static boolean nbtFlag(LevelAccessor world, BlockPos pos, String key) {
        BlockEntity blockEntity = blockEntityAt(world, pos);
        return blockEntity != null && blockEntity.getPersistentData().getBoolean(key);
    }

    /**
     * The block entity at a position, from whichever level actually holds it.
     *
     * <p>A launcher or a rack can stand inside a Sable sub-level, whose blocks are stored in the
     * sub-level's plot region; the level a caller is handed is not always the level that can answer
     * for the position. Asking the level first and the sub-level lookup second keeps every ammo and
     * rack flag readable, and a missing block entity still answers "no flag" instead of throwing.</p>
     */
    private static BlockEntity blockEntityAt(LevelAccessor world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        try {
            BlockEntity found = world.getBlockEntity(pos);
            if (found != null) {
                return found;
            }
        } catch (Throwable ignored) {
            // fall through to the sub-level lookup
        }
        try {
            return world instanceof Level level ? SubLevelPositions.blockEntity(level, pos) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The axis a launcher block points along.
     *
     * <p>A launcher standing on a Sable structure is stored in the structure's plot region, and the
     * level a caller happens to hold is not always the one that can read that block - the direct read
     * then answers air and the axis falls back to {@link Direction#NORTH}. That silent north is
     * exactly what turns an off-boresight launch into a shot at the wrong point, so the block state is
     * read through {@link SubLevelPositions} when the direct read is empty.</p>
     */
    public static Direction facing(LevelAccessor world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if ((state == null || state.isAir()) && world instanceof Level level) {
            BlockState stored = SubLevelPositions.blockState(level, pos);
            if (stored != null && !stored.isAir()) {
                state = stored;
            }
        }
        if (state == null) {
            return Direction.NORTH;
        }
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

    /**
     * Whether this mod still fires its own air-defence and anti-tank rounds.
     *
     * <p>Off. Fire control 0.9.3 loads, recognises and fires the ordinary air-defence missiles of
     * 面包学 itself - beam riding against the computed aim direction, with the fuze range read from
     * the computer (0 to 10 blocks, 0 for contact) and saved per round at launch. This mod's own
     * beam family did the same job on the same launchers, so both of them tried to load the tube and
     * to take the shot: whichever answered first won, and the other one reported an empty launcher.
     * The launchers are therefore left entirely to the pack.</p>
     *
     * <p>The items are still registered, so nothing a player is carrying disappears; what is gone is
     * this mod's own flight and fuze for them. The infrared rack rounds and the anti-radiation round
     * are unaffected - the pack has no seeker of either kind.</p>
     */
    public static final boolean NATIVE_AIR_DEFENCE_TAKES_OVER = true;

    /** AA launcher family: muzzle = block center + facing * 2.6, speed 2.0. */
    public static boolean tryLoadAntiAir(LevelAccessor world, double x, double y, double z, Entity entity, double capacity) {
        /*
         * The pack still owns its own air-defence rounds - that part of the old rule stands, and
         * the branches below it (the beam-riding round, the sapling easter egg) stay retired.
         *
         * What changed: the infrared round was never actually loadable. This method opened with
         * `if (NATIVE_AIR_DEFENCE_TAKES_OVER) return false;`, and the pack's reload procedure only
         * accepts its own `portableantiairmissile_2`, so a player holding this mod's
         * `infrared_missile` and right-clicking any launcher got nothing at all. The round is now
         * loaded through the launcher's **own** storage (container slot zero, the same stack the
         * pack's procedure writes and its launch procedure takes from), so every launcher in the
         * family - the base mount, the left/right pair and the four third_h variants - accepts it
         * with the capacity that launcher really has.
         */
        ItemStack held = heldItem(entity);
        if (!isInfraredMissile(held)) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!isAirDefenceLauncher(world, pos)) {
            // Not one of the seven air-defense mounts: leave the block to whatever called the
            // procedure, so a container of another machine can never be written into.
            return false;
        }
        if (world.isClientSide()) {
            return true;
        }
        BlockEntity blockEntity = blockEntityAt(world, pos);
        if (!(blockEntity instanceof Container container) || container.getContainerSize() <= 0) {
            reportIrLoad(world, pos, held, "false", "none", -1, -1, capacity, "no-container");
            return true;
        }
        double limit = airDefenceCapacity(world, pos, capacity);
        int before = slotCount(container);
        ItemStack stack = container.getItem(0);
        boolean empty = stack == null || stack.isEmpty();
        boolean ours = empty || isInfraredMissile(stack);
        if (!ours) {
            reportIrLoad(world, pos, held, "false", "slot0", before, before, limit,
                    "slot0-occupied:" + itemIdOf(stack));
            message(entity, "发射器已装填其他弹药");
            return true;
        }
        if (before >= (int) limit) {
            reportIrLoad(world, pos, held, "false", "slot0", before, before, limit, "full");
            message(entity, "装填完成");
            return true;
        }
        boolean creative = entity instanceof Player player && player.getAbilities().instabuild;
        // Creative fills the tube the way the pack's own procedure does; survival adds one round,
        // and the round is taken out of the hand below.
        int after = creative ? (int) limit : before + 1;
        container.setItem(0, new ItemStack(BeamMissileRegistry.INFRARED_MISSILE.get(), after));
        blockEntity.setChanged();
        consumeHeld(entity);
        playLoadSound(world, pos);
        sendBlockUpdated(world, pos);
        reportIrLoad(world, pos, held, "true", "slot0", before, after, limit,
                creative ? "creative" : "consumed");
        message(entity, "已装填/LOAD :" + after + "发");
        return true;
    }

    /**
     * The capacity the launcher itself uses.
     *
     * <p>Read from the block the interaction hit rather than from the caller's constant: the family
     * is two rounds on the left/right pair and four everywhere else, and a launcher this mod has
     * never seen falls back to whatever the procedure that called us assumed.</p>
     */
    private static double airDefenceCapacity(LevelAccessor world, BlockPos pos, double fallback) {
        try {
            String path = BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).getPath();
            if (path.contains("antiairmissilelauncherleft") || path.contains("antiairmissilelauncherright")) {
                return 2.0;
            }
            if (path.contains("antiairmissilelauncher")) {
                return 4.0;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // a block that cannot name itself keeps the caller's capacity
        }
        return fallback > 0.0 ? fallback : 4.0;
    }

    /**
     * Whether the block at a position is one of 面包学's seven air-defense mounts.
     *
     * <p>The same family 火控学 lists in {@code MianbaoAirDefenseCompat.LAUNCHERS}: the base mount,
     * the left/right pair and the four {@code third_h} variants. Matched by registry path so a
     * launcher added by a pack update is accepted without a second list to keep in step.</p>
     */
    private static boolean isAirDefenceLauncher(LevelAccessor world, BlockPos pos) {
        try {
            String path = BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).getPath();
            return path.contains("antiairmissilelauncher");
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** The stack size in the launcher's own slot zero, or {@code -1} when there is no container. */
    private static int slotCount(Container container) {
        try {
            ItemStack stack = container == null || container.getContainerSize() <= 0 ? null : container.getItem(0);
            return stack == null || stack.isEmpty() ? 0 : stack.getCount();
        } catch (RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    /** The one line that says which launcher accepted which round, and where it went. */
    private static void reportIrLoad(LevelAccessor world, BlockPos pos, ItemStack held, String accepted,
            String stored, int before, int after, double capacity, String reason) {
        String block = "-";
        try {
            block = String.valueOf(BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()));
        } catch (RuntimeException | LinkageError ignored) {
        }
        FireControlCompat.LOGGER.info(
                "ir load: block={} accepted={} stored={} ammo={}->{} cap={} item={} reason={} pos={}",
                block, accepted, stored, before, after, (int) capacity, itemIdOf(held), reason, pos);
    }

    /** The single round count of one air-defence launcher, for the fire path and its log line. */
    private static int airDefenceRounds(LevelAccessor world, BlockPos pos) {
        try {
            BlockEntity blockEntity = blockEntityAt(world, pos);
            if (!(blockEntity instanceof Container container)) {
                return 0;
            }
            ItemStack stack = container.getContainerSize() <= 0 ? null : container.getItem(0);
            return isInfraredMissile(stack) ? stack.getCount() : 0;
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static boolean tryFireAntiAir(LevelAccessor world, double x, double y, double z, double muzzleDistance, float speed) {
        if (!(world instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockEntity blockEntity = blockEntityAt(world, pos);
        if (!(blockEntity instanceof Container container) || container.getContainerSize() <= 0) {
            return false;
        }
        ItemStack stack = container.getItem(0);
        if (!isInfraredMissile(stack)) {
            // The pack's own round in slot zero: its own launch procedure owns the shot, untouched.
            return false;
        }
        Direction direction = facing(world, pos);
        Vec3 muzzle = simpleMuzzle(pos, direction, muzzleDistance, 0.0D);
        // The trigger press parked the contact the launcher's seeker settled on; this call is the
        // one that spends the round, so it is also the one that has to fly the round at that
        // contact instead of down the tube's own line.
        IrSeekerSession.Lock seekerLock = IrSeekerSession.takeHandOver(level, pos);
        Release aim = releaseAim(world, pos, muzzle, direction, seekerLock == null ? null : seekerLock.position());
        int before = stack.getCount();
        // The round is released through the same path the anti-tank bay uses, so the seeker head,
        // the IFF mark and the guidance hand-over are the ones every other round of ours gets.
        BeamRidingMissileEntity missile = spawnBeamMissile(world, pos, muzzle, aim.direction(), speed,
                BeamMissileRegistry.INFRARED_TANSHE.get(), null);
        if (missile == null) {
            FireControlCompat.LOGGER.info(
                    "ir fire: block={} added=false rounds={}->{} reason=spawn-failed pos={}",
                    blockName(world, pos), before, before, pos);
            return true;
        }
        // The locked contact goes onto the round as well: the head that took it pre-launch hands it
        // over, so the round keeps riding the contact it was released against (and an image lock
        // makes it blind to countermeasures for the rest of the flight).
        presetSeekerTarget(world, missile, seekerLock);
        int after = Math.max(0, before - 1);
        if (after == 0) {
            container.setItem(0, ItemStack.EMPTY);
        } else {
            stack.setCount(after);
            container.setItem(0, stack);
        }
        blockEntity.setChanged();
        playLaunchSound(world, pos, 4.0F, 0.85F);
        sendBlockUpdated(world, pos);
        reportIrSeekerFire(world, pos, muzzle, aim);
        FireControlCompat.LOGGER.info(
                "ir fire: block={} added=true rounds={}->{} stored=slot0 entity={} dir={} offBoresight={} {} muzzle={} pos={}",
                blockName(world, pos), before, after, missile.getType(), direction,
                String.format(java.util.Locale.ROOT, "%.1f", aim.offBoresightDegrees()),
                aim.offAxis() ? "off-axis" : "boresight", muzzle, pos);
        return true;
    }

    private static String blockName(LevelAccessor world, BlockPos pos) {
        try {
            return String.valueOf(BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()));
        } catch (RuntimeException | LinkageError ignored) {
            return "-";
        }
    }

    /**
     * A launcher's own round capacity, exactly the {@code amount} table of 2.6.5's
     * {@code AntitankmissilelauncherloadProcedure} (:47-57): the four single/dual tube blocks take
     * four rounds, the triple and quadruple rigs take two.
     *
     * @return the capacity, or {@code 0} when the block is not one of 面包学's anti-tank launchers
     */
    public static int atgmLauncherCapacity(LevelAccessor world, BlockPos pos) {
        try {
            Block block = world.getBlockState(pos).getBlock();
            if (block == MianbaosModernwarfareModBlocks.ANTITANKEMISSILELAUNCHER.get()
                    || block == MianbaosModernwarfareModBlocks.ANTITANKEMISSILELAUNCHERH_2.get()
                    || block == MianbaosModernwarfareModBlocks.ATGM_MISSILEMIXEDLAUNCHER.get()
                    || block == MianbaosModernwarfareModBlocks.ATGM_MISSILEMIXEDLAUNCHER_2.get()) {
                return 4;
            }
            if (block == MianbaosModernwarfareModBlocks.ANTITANKMISSILELAUNCHERERXING_LEFT.get()
                    || block == MianbaosModernwarfareModBlocks.ANTITANKMISSILELAUNCHERERXING_RIGHT.get()
                    || block == MianbaosModernwarfareModBlocks.ANTITANKMISSILELAUNCHERSANXING.get()
                    || block == MianbaosModernwarfareModBlocks.ANTITANKMISSILELAUNCHERSIXING.get()) {
                return 2;
            }
        } catch (Throwable ignored) {
            // not one of ours, or the pack is missing: no capacity
        }
        return 0;
    }

    /**
     * The launcher's own round slot.
     *
     * <p>2.6.5 keeps a launcher's ammunition in <b>container slot zero</b> of the block entity, not
     * in a persistent counter: {@code AntitankmissilelauncherloadProcedure} writes
     * {@code setStackInSlot(0, new ItemStack(ANTITANKMISSILE_1, amount + 1))} through
     * {@code LegacyInventoryAccess.getHandler(be)} (a {@code new InvWrapper(container)}), the work
     * procedures shrink that same slot, and 火控学 counts it with
     * {@code MianbaoLauncherAmmo.antiTank(BlockEntity,String)} (:24-32, slot zero or nothing).
     * So this is the pack's storage, and our round goes in there too.</p>
     */
    private static Container atgmContainer(LevelAccessor world, BlockPos pos) {
        BlockEntity blockEntity = blockEntityAt(world, pos);
        if (blockEntity instanceof Container container && container.getContainerSize() > 0) {
            return container;
        }
        return null;
    }

    /** The stack in the launcher's round slot, or {@link ItemStack#EMPTY}. */
    public static ItemStack atgmSlotStack(LevelAccessor world, BlockPos pos) {
        Container container = atgmContainer(world, pos);
        if (container == null) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack stack = container.getItem(0);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    static boolean setAtgmSlot(LevelAccessor world, BlockPos pos, ItemStack stack) {
        Container container = atgmContainer(world, pos);
        if (container == null) {
            return false;
        }
        try {
            container.setItem(0, stack);
            container.setChanged();
            return true;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("{}ir-atgm: slot write failed: {}", PREFIX, failure.toString());
            return false;
        }
    }

    /**
     * Rounds of the infrared anti-tank round the launcher is holding.
     *
     * <p>Slot zero is the answer whenever it carries our round or a native one. It is only when the
     * slot is <em>empty</em> that the old {@code fciratgmtop} counter is read, so a launcher loaded
     * by an earlier build keeps its round and a launcher whose slot holds a native round can never
     * answer for ours.</p>
     */
    public static int irAtgmRounds(LevelAccessor world, BlockPos pos) {
        ItemStack stack = atgmSlotStack(world, pos);
        if (isIrAtgm(stack)) {
            return Math.max(0, stack.getCount());
        }
        if (!stack.isEmpty()) {
            return 0;
        }
        return (int) Math.max(0.0, Math.floor(nbtDouble(world, pos, IR_ATGM_TOP_AMMO_KEY)));
    }

    /** One round out of the launcher: slot zero first, the old counter second. */
    private static boolean spendIrAtgmRound(LevelAccessor world, BlockPos pos) {
        ItemStack stack = atgmSlotStack(world, pos);
        if (isIrAtgm(stack)) {
            ItemStack taken = stack.copy();
            taken.shrink(1);
            if (!setAtgmSlot(world, pos, taken.isEmpty() ? ItemStack.EMPTY : taken)) {
                return false;
            }
            // The old counter is a mirror, never the storage: clear it so it cannot double count.
            nbtPutDouble(world, pos, IR_ATGM_TOP_AMMO_KEY, 0.0);
            return true;
        }
        double legacy = nbtDouble(world, pos, IR_ATGM_TOP_AMMO_KEY);
        if (legacy > 0.0) {
            nbtPutDouble(world, pos, IR_ATGM_TOP_AMMO_KEY, Math.max(0.0, legacy - 1.0));
            return true;
        }
        return false;
    }

    static String blockIdAt(LevelAccessor world, BlockPos pos) {
        try {
            return String.valueOf(BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()));
        } catch (Throwable ignored) {
            return "-";
        }
    }

    private static String itemIdOf(ItemStack stack) {
        try {
            return stack == null || stack.isEmpty() ? "empty" : String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /**
     * One line per load attempt, every branch, in the shape the hand-off asks for.
     *
     * <p>{@code stored} says <em>where</em> the round went: {@code slot0} for the pack's own round
     * slot (2.6.5's storage), {@code none} when the attempt was refused.</p>
     */
    private static void reportIrAtgmLoad(LevelAccessor world, BlockPos pos, ItemStack held, String accepted,
            String stored, int before, int after, boolean consumed, int capacity, boolean creative,
            String reason) {
        try {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm load: called item={} block={} accepted={} stored={} before={} after={} consumed={} cap={} creative={} reason={} pos={}",
                    PREFIX, itemIdOf(held), blockIdAt(world, pos), accepted, stored, before, after, consumed,
                    capacity, creative, reason, pos.toShortString());
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /**
     * Loads our infrared anti-tank round into a 面包学 anti-tank launcher the way the pack loads its
     * own round: into the launcher's container slot zero, one round per right click, up to the
     * block's own capacity, and the held item is consumed in survival only.
     *
     * @return {@code true} when this call owned the interaction (the pack's procedure is cancelled)
     */
    private static boolean loadIrAtgm(LevelAccessor world, BlockPos pos, Entity entity) {
        ItemStack held = heldItem(entity);
        int capacity = atgmLauncherCapacity(world, pos);
        if (capacity <= 0) {
            reportIrAtgmLoad(world, pos, held, "false", "none", 0, 0, false, 0, false, "not-an-atgm-launcher");
            return false;
        }
        boolean creative = entity instanceof Player player && player.getAbilities().instabuild;
        ItemStack slot = atgmSlotStack(world, pos);
        int before = isIrAtgm(slot) ? Math.max(0, slot.getCount()) : 0;
        if (!slot.isEmpty() && !isIrAtgm(slot)) {
            // A native round is already in the tube: the pack's own rule is one round type per
            // launcher, so this is refused the same way the pack's "occupied" case is.
            message(entity, "发射器已占用，请先清空");
            reportIrAtgmLoad(world, pos, held, "false", "slot0", before, before, false, capacity, creative,
                    "slot0-occupied:" + itemIdOf(slot));
            return true;
        }
        if (before >= capacity) {
            message(entity, "装填完成");
            reportIrAtgmLoad(world, pos, held, "false", "slot0", before, before, false, capacity, creative, "full");
            return true;
        }
        int after = creative ? capacity : before + 1;
        if (!setAtgmSlot(world, pos, new ItemStack(BeamMissileRegistry.IR_ATGM.get(), after))) {
            reportIrAtgmLoad(world, pos, held, "false", "none", before, before, false, capacity, creative,
                    "no-round-slot");
            return false;
        }
        // Survival spends the round; creative keeps it, exactly like the pack's own load.
        if (!creative) {
            consumeHeld(entity);
        }
        // Older builds of this mod counted the round in a persistent key instead of the slot; the
        // key is cleared so it can never add rounds the launcher is not holding.
        nbtPutDouble(world, pos, IR_ATGM_TOP_AMMO_KEY, 0.0);
        nbtPutDouble(world, pos, ATGM_AMMO_KEY_1, 0.0);
        nbtPutDouble(world, pos, ATGM_AMMO_KEY_2, 0.0);
        playLoadSound(world, pos);
        sendBlockUpdated(world, pos);
        message(entity, "已装填/LOAD :" + after + "发（红外反坦克导弹）");
        reportIrAtgmLoad(world, pos, held, "true", "slot0", before, after, !creative, capacity, creative,
                "loaded");
        return true;
    }

    /** ATGM launcher family. Fills slot 1 then slot 2 up to the launcher's native capacity. */
    public static boolean tryLoadAtgm(LevelAccessor world, double x, double y, double z, Entity entity,
            double slot1Capacity, double slot2Capacity) {
        ItemStack held = heldItem(entity);
        boolean beam = isBeamMissile(held);
        boolean loitering = isLoiteringMunition(held);
        boolean infrared = isInfraredMissile(held);
        boolean topAttack = isIrAtgm(held);
        if (!beam && !loitering && !infrared && !topAttack) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (world.isClientSide()) {
            return true;
        }
        if (topAttack) {
            // The pack has no round of this kind, so the "the pack owns the launchers" switch
            // below must not swallow it. It goes through the pack's own slot, see loadIrAtgm.
            return loadIrAtgm(world, pos, entity);
        }
        if (NATIVE_AIR_DEFENCE_TAKES_OVER) {
            // the pack loads its own anti-tank round
            return false;
        }
        double slot1 = nbtDouble(world, pos, ATGM_AMMO_KEY_1);
        double slot2 = nbtDouble(world, pos, ATGM_AMMO_KEY_2);
        double beamAmmo = nbtDouble(world, pos, BEAM_AMMO_KEY);
        double loiterAmmo = nbtDouble(world, pos, LOITER_ATGM_AMMO_KEY);
        double irAmmo = nbtDouble(world, pos, IR_ATGM_AMMO_KEY);
        double topAmmo = nbtDouble(world, pos, IR_ATGM_TOP_AMMO_KEY);
        if (beamAmmo <= 0.0 && loiterAmmo <= 0.0 && irAmmo <= 0.0 && topAmmo <= 0.0
                && (slot1 > 0.0 || slot2 > 0.0)) {
            message(entity, "发射器已占用，请先清空");
            return true;
        }
        if (beam && (loiterAmmo > 0.0 || irAmmo > 0.0 || topAmmo > 0.0)
                || loitering && (beamAmmo > 0.0 || irAmmo > 0.0 || topAmmo > 0.0)
                || infrared && (beamAmmo > 0.0 || loiterAmmo > 0.0 || topAmmo > 0.0)
                || topAttack && (beamAmmo > 0.0 || loiterAmmo > 0.0 || irAmmo > 0.0)) {
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
        } else if (topAttack) {
            nbtPutDouble(world, pos, IR_ATGM_TOP_AMMO_KEY, topAmmo + 1.0);
            message(entity, "已装填红外反坦克导弹" + (int) (topAmmo + 1.0) + "发（需光电锁定）");
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ir-atgm: load pos={} ammo={} lockRequired=true",
                    pos, (int) (topAmmo + 1.0));
        } else if (infrared) {
            nbtPutDouble(world, pos, IR_ATGM_AMMO_KEY, irAmmo + 1.0);
            message(entity, "已装填红外锁定导弹" + (int) (irAmmo + 1.0) + "发");
        } else {
            nbtPutDouble(world, pos, BEAM_AMMO_KEY, total);
            message(entity, "已装填架束近炸导弹" + (int) total + "发");
        }
        sendBlockUpdated(world, pos);
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] atgm launcher loaded {} at {}: missile1={} missile2={} beam={} ir={} loiter={}",
                beam ? "beam" : infrared ? "infrared" : "loiter", pos,
                (int) nbtDouble(world, pos, ATGM_AMMO_KEY_1), (int) nbtDouble(world, pos, ATGM_AMMO_KEY_2),
                (int) nbtDouble(world, pos, BEAM_AMMO_KEY), (int) nbtDouble(world, pos, IR_ATGM_AMMO_KEY),
                (int) nbtDouble(world, pos, LOITER_ATGM_AMMO_KEY));
        return true;
    }

    /**
     * Where the designation for an infrared anti-tank round comes from.
     *
     * <p>Three kinds exist in this pack and they are not interchangeable, because the route a round
     * takes depends on which one is holding it:</p>
     *
     * <ul>
     *   <li>{@link #GROUND_EO} - a pod looking through the electro-optical head of a ground vehicle
     *       or of a launcher rig. This is the designation the round was designed around, and the
     *       launcher it is loaded in is the thing that fires it.</li>
     *   <li>{@link #AIR_POD} - the same kind of pod, on an aircraft. The round then belongs to the
     *       aircraft weapon chain: it is selected from the aircraft slots and released off the rail
     *       the trigger is on, not off a ground launcher.</li>
     *   <li>{@link #RADAR} - a radar contact locked by a fire control computer (or by a display
     *       bound to the launcher). A radar sees through the weather a pod cannot, so the pack's
     *       anti-tank path accepts it as a fallback designation, and the aircraft chain accepts it as
     *       the air-to-ground lock.</li>
     * </ul>
     *
     * <p>The classification is read from the pod's own computer, never from the platform being
     * fired: a computer with aircraft rails bound is a cockpit, one without is a ground station (see
     * {@link com.qkdream.firecontrolcompat.laser.LaserSpots#podIsAirborne}). That is what lets the
     * same round be told apart on the two routes without a second designation system.</p>
     */
    public enum LockSource {
        GROUND_EO,
        AIR_POD,
        RADAR,
        /**
         * The optical (pod camera) lock a fire control computer is holding.
         *
         * <p>This is the designation a crew actually sees: the lock box on the pod view, whether it
         * is a ground vehicle's electro-optical head or a cockpit's. It is <b>not</b> the same thing
         * as a painted {@link LaserSpots} spot - that table only records computers whose pod view is
         * on and which are painting a laser - so before this source existed a locked cockpit or
         * vehicle answered "no electro-optical lock" and refused the shot. It is read straight from
         * the computer bound to the launcher/rail ({@code hasOpticalLock} +
         * {@code getOpticalLockTarget}, already in the level's own coordinates).</p>
         */
        OPTICAL,

        /**
         * The contact the launcher's <b>own</b> pre-launch infrared seeker took.
         *
         * <p>This is the two step release's designation: the first trigger press opens
         * {@code IrSeekerSession}, the head scans its cone and settles on a contact, and the second
         * press parks that contact for the round. It is a real lock the crew watched happen, so it
         * outranks the computer's radar contact and the pod's painted spot - and, unlike a radar
         * contact, it is a heat source the round's own seeker can actually see.</p>
         */
        SEEKER
    }

    /** One designation: the point, where it came from, and the evidence that says so. */
    public record Lock(Vec3 point, LockSource source, String detail) {
    }

    /**
     * The point our infrared anti-tank round is released against, with its source.
     *
     * <p>A pod's painted spot is the designation this round was designed around
     * ({@link IrAtgm#paintedSpot}). A 火控学 computer that has a contact locked on the launcher is
     * the same kind of designation and is what the computer itself fires on, so it is the fallback
     * - without it a computer could count the round but never spend it.</p>
     */
    public static Vec3 irAtgmLockPoint(LevelAccessor world, BlockPos pos, Vec3 muzzle) {
        Lock lock = irAtgmLock(world, pos, muzzle);
        return lock == null ? null : lock.point();
    }

    /** {@link #irAtgmLockPoint} with the source of the designation attached. */
    public static Lock irAtgmLock(LevelAccessor world, BlockPos pos, Vec3 muzzle) {
        firecontrolcompat$probeControllers(world, pos);
        Lock lock = firecontrolcompat$resolveLock(world, pos, muzzle);
        firecontrolcompat$reportLock(world, pos, lock);
        return lock;
    }

    /**
     * Every designation this launcher can be fired on, in the order they are trusted.
     *
     * <ol>
     *   <li>a spot a pod is painting ({@code LaserSpots}: pod view on, a laser point on the ground),</li>
     *   <li>the optical lock the computer bound to this launcher/rail is holding - <b>this is the one
     *       a crew sees as "locked"</b>, and it was the missing source that made a locked launcher
     *       answer {@code refused reason=no-lock},</li>
     *   <li>a radar contact (or a display's selected contact) the computer has locked.</li>
     * </ol>
     *
     * <p>Every reading is normalised into the launcher's own space by
     * {@link #lockInLauncherSpace}, so a launcher standing on a Sable structure gets a point it can
     * actually fly at instead of one twenty million blocks away.</p>
     */
    private static Lock firecontrolcompat$resolveLock(LevelAccessor world, BlockPos pos, Vec3 muzzle) {
        Lock painted = paintedLockAnySpace(world, pos, muzzle);
        if (painted != null) {
            return new Lock(lockInLauncherSpace(world, pos, muzzle, painted.point()), painted.source(),
                    painted.detail());
        }
        Lock optical = opticalLock(world, pos);
        if (optical != null) {
            return new Lock(lockInLauncherSpace(world, pos, muzzle, optical.point()), optical.source(),
                    optical.detail());
        }
        Vec3 contact = null;
        try {
            contact = spruceTargetPoint(world, pos);
        } catch (Throwable ignored) {
            // no computer lock either: the round stays on the rail
        }
        return contact == null ? null
                : new Lock(lockInLauncherSpace(world, pos, muzzle, contact), LockSource.RADAR, "radar-contact");
    }

    /**
     * The optical lock the computer that fires this launcher (or releases off this rail) is holding.
     *
     * <p>{@code hasOpticalLock}/{@code getOpticalLockTarget} are the pack's own "locked" state - the
     * box the crew sees - and the answer is already in the level's own coordinates (an entity centre,
     * or the anchor of a locked structure through its pose). Nothing is guessed: a computer without a
     * lock answers {@code null} and is skipped, and a computer that is not bound to this block is not
     * consulted at all, so one cockpit's lock can never fire another rig's launcher.</p>
     */
    private static Lock opticalLock(LevelAccessor world, BlockPos pos) {
        Level level = world instanceof Level given ? given : null;
        if (level == null || pos == null) {
            return null;
        }
        for (StabilizerControllerBlockEntity controller : controllersBinding(level, pos)) {
            try {
                Lock found = designationOf(controller);
                if (found != null) {
                    return found;
                }
            } catch (Throwable ignored) {
                // a computer that cannot be read is not a designation
            }
        }
        return null;
    }

    /**
     * Every designation a computer holds, best first, in the level's own coordinates.
     *
     * <p>0.9.4 keeps <b>three</b> separate "the crew sees a locked box" states, and the optical
     * (entity / structure) lock is only the first of them:</p>
     *
     * <ol>
     *   <li>{@code hasOpticalLock()} / {@code getOpticalLockTarget()} - the lock box on an entity
     *       or a whole structure (an entity centre, or a structure anchor through its pose);</li>
     *   <li>{@code getAirToGroundTargetSnapshot()} - the <b>ground target</b> an air-to-ground pod
     *       has locked. This is what a cockpit's pod view shows as the designated target on the
     *       ground, and it is <em>not</em> an optical lock: a crew can be locked on a building
     *       without {@code hasOpticalLock()} ever answering true;</li>
     *   <li>{@code getPodGuidanceTarget()} - the pod's own stabilised aim point, which is the
     *       designation a laser-guided release flies to.</li>
     * </ol>
     *
     * <p>Only the first was read before, so a computer that was locked with its pod on the ground
     * still answered "no lock" and the round stayed on the rail. All three are ground points the
     * round can climb over, so all three are accepted; the detail says which one answered.</p>
     */
    private static Lock designationOf(StabilizerControllerBlockEntity controller) {
        if (controller == null) {
            return null;
        }
        try {
            if (controller.hasOpticalLock()) {
                Vec3 point = controller.getOpticalLockTarget();
                if (usable(point)) {
                    boolean obscured = false;
                    try {
                        obscured = controller.isOpticalLockObscured();
                    } catch (Throwable ignored) {
                        // an unreadable obscurity flag is treated as clear
                    }
                    return new Lock(point, LockSource.OPTICAL,
                            obscured ? "optical-lock(obscured)" : "optical-lock");
                }
            }
        } catch (Throwable ignored) {
            // no optical lock: try the pod's ground target
        }
        try {
            StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                    controller.getAirToGroundTargetSnapshot();
            if (snapshot != null && usable(snapshot.center())) {
                return new Lock(snapshot.center(), LockSource.OPTICAL, "atg-snapshot");
            }
        } catch (Throwable ignored) {
            // no ground snapshot: try the pod's own aim point
        }
        try {
            Vec3 pod = controller.getPodGuidanceTarget();
            if (usable(pod)) {
                return new Lock(pod, LockSource.OPTICAL, "pod-target");
            }
        } catch (Throwable ignored) {
            // nothing at all
        }
        return null;
    }

    /** Whether a point is a real designation rather than a missing or meaningless one. */
    private static boolean usable(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y)
                && Double.isFinite(point.z)
                && (Math.abs(point.x) > 1.0E-6 || Math.abs(point.y) > 1.0E-6 || Math.abs(point.z) > 1.0E-6);
    }

    /**
     * The computers that fire this launcher or release off this rail, ground and aircraft alike.
     *
     * <p>All three lists are walked, because "bound to this block" means a different list depending
     * on what the block is: an anti-tank launcher is a {@code missileLauncher}, an AA launcher an
     * {@code aaMissileLauncher}, and the air-release anti-tank rack an {@code aircraftMissileRack}
     * (that is the one this mod added to the pack's binding table). A dimension mismatch is only
     * treated as "not bound" when both levels can report one - a plot level and a root level are the
     * same world.</p>
     */
    private static java.util.List<StabilizerControllerBlockEntity> controllersBinding(Level level, BlockPos pos) {
        java.util.List<StabilizerControllerBlockEntity> bound = new java.util.ArrayList<>();
        if (level == null || pos == null) {
            return bound;
        }
        Level lookup = levelForLookup(level);
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            try {
                if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
                    continue;
                }
                Level controllerLevel = controller.getLevel();
                // Both sides are reduced to their root level before the dimensions are compared:
                // a computer standing on a structure can report the plot level instead of the world,
                // and a plot level's dimension is not the world's, so the old raw comparison threw
                // the cockpit's own computer away and a locked aircraft answered "no lock".
                Level controllerLookup = levelForLookup(controllerLevel);
                if (controllerLookup != null && lookup != null && controllerLookup.getServer() != null
                        && lookup.getServer() != null
                        && controllerLookup.dimension() != lookup.dimension()) {
                    continue;
                }
                if (firecontrolcompat$bindsLauncher(controller, pos, level, controllerLevel)
                        || firecontrolcompat$samePlatform(level, pos, controller)) {
                    bound.add(controller);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // an unreadable computer is simply not a candidate
            }
        }
        return bound;
    }

    /**
     * Whether the computer stands on the same Sable body as this launcher or rail.
     *
     * <p>A cockpit and the rack hanging under the same wing are one welded structure, and the pack
     * keeps a rack in a bound list only while that exact binding still resolves. A rack whose block
     * id changes as it is armed or emptied, or whose binding was captured in another space, can drop
     * out of that list while the crew is looking straight at the lock box - so the platform itself is
     * asked as well. Two blocks on one body always belong to the same fire control.</p>
     */
    private static boolean firecontrolcompat$samePlatform(Level level, BlockPos pos,
            StabilizerControllerBlockEntity controller) {
        try {
            Level controllerLevel = controller.getLevel();
            if (controllerLevel == null) {
                return false;
            }
            SubLevel mine = subLevelAt(levelForLookup(level), pos);
            if (mine == null) {
                mine = subLevelAt(level, pos);
            }
            if (mine == null) {
                return false;
            }
            BlockPos controllerPos = controller.getBlockPos();
            SubLevel theirs = subLevelAt(levelForLookup(controllerLevel), controllerPos);
            if (theirs == null) {
                theirs = subLevelAt(controllerLevel, controllerPos);
            }
            return theirs != null && mine.getUniqueId().equals(theirs.getUniqueId());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Last per-controller probe per launcher, so a held trigger reports once, not every tick. */
    private static final Map<Long, Long> LOCK_CONTROLLER_PROBES = new java.util.HashMap<>();

    /**
     * One line per attempt listing <b>every</b> fire control computer in the world and what it
     * offers this launcher.
     *
     * <p>This is the line that answers "the crew is locked and the round still says no lock". It is
     * printed for every attempt (rate limited to twice a second per launcher), not only for
     * successes, so a refusal carries the reason:</p>
     *
     * <ul>
     *   <li>{@code bound} - the computer has this block in one of its bound lists;</li>
     *   <li>{@code same} - the computer stands on the same Sable body as this block (a cockpit and
     *       its own wing rack), which is a binding the pack's lists do not always show;</li>
     *   <li>{@code optical} / {@code atg} / {@code pod} - which of the three designation states the
     *       computer is in, and {@code target=} the point the first of them holds.</li>
     * </ul>
     */
    private static void firecontrolcompat$probeControllers(LevelAccessor world, BlockPos pos) {
        if (pos == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long key = pos.asLong();
            Long last = LOCK_CONTROLLER_PROBES.get(key);
            if (last != null && now - last < 500L) {
                return;
            }
            LOCK_CONTROLLER_PROBES.put(key, now);
            if (LOCK_CONTROLLER_PROBES.size() > 64) {
                LOCK_CONTROLLER_PROBES.clear();
            }
            Level level = world instanceof Level given ? given : null;
            if (level == null) {
                return;
            }
            java.util.List<StabilizerControllerBlockEntity> loaded = ControllerTracker.loadedControllers();
            StringBuilder text = new StringBuilder();
            int candidates = 0;
            int index = 0;
            for (StabilizerControllerBlockEntity controller : loaded) {
                if (controller == null || controller.isRemoved() || controller.getLevel() == null) {
                    continue;
                }
                boolean bound = firecontrolcompat$bindsLauncher(controller, pos, level, controller.getLevel());
                boolean same = firecontrolcompat$samePlatform(level, pos, controller);
                if (bound || same) {
                    candidates++;
                }
                Lock designation = designationOf(controller);
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append("controller=").append(controller.getBlockPos().toShortString())
                        .append(" dim=").append(levelForLookup(controller.getLevel()) == levelForLookup(level)
                                ? "ok" : "other")
                        .append(" bound=").append(bound)
                        .append(" same=").append(same)
                        .append(" optical=").append(firecontrolcompat$state(controller, "optical"))
                        .append(" atg=").append(firecontrolcompat$state(controller, "atg"))
                        .append(" pod=").append(firecontrolcompat$state(controller, "pod"))
                        .append(" target=").append(designation == null ? "-" : designation.point())
                        .append(" via=").append(designation == null ? "-" : designation.detail());
                if (++index >= 8) {
                    break;
                }
            }
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm lock probe: launcher={} space={} controllers={} bound={} {}",
                    PREFIX, pos.toShortString(),
                    firecontrolcompat$subLevelAt(pos, level) ? "sublevel" : "world", loaded.size(),
                    candidates, text.length() == 0 ? "controllers=-" : text.toString());
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** Whether the launcher itself stands on a Sable body, for the probe's space column. */
    private static boolean firecontrolcompat$subLevelAt(BlockPos pos, Level level) {
        try {
            if (subLevelAt(levelForLookup(level), pos) != null) {
                return true;
            }
            return subLevelAt(level, pos) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** One of the three designation states of a computer as {@code true}/{@code false}. */
    private static boolean firecontrolcompat$state(StabilizerControllerBlockEntity controller, String which) {
        try {
            if ("optical".equals(which)) {
                return controller.hasOpticalLock();
            }
            if ("atg".equals(which)) {
                StabilizerControllerBlockEntity.GroundTargetSnapshot snapshot =
                        controller.getAirToGroundTargetSnapshot();
                return snapshot != null && usable(snapshot.center());
            }
            return usable(controller.getPodGuidanceTarget());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Last lock line per launcher, so a held trigger reports the designation once, not every call. */
    private static final Map<Long, Long> LOCK_REPORTS = new java.util.HashMap<>();

    /**
     * One line per resolved designation: which source it is, the point, and which space it is in.
     *
     * <p>{@code space=sublevel} means the launcher stands on a Sable structure and the point was
     * normalised back into that structure's own coordinates, {@code space=world} means it was already
     * in the level's coordinates. That is the one thing a refused or a drifting shot cannot be
     * diagnosed without, because the same designation reads twenty million blocks away in the wrong
     * space.</p>
     *
     * <p>Rate limited to once a second per launcher with a real timestamp difference - never
     * {@code now - Long.MIN_VALUE} - so the first designation after start-up always reports.</p>
     */
    private static void firecontrolcompat$reportLock(LevelAccessor world, BlockPos pos, Lock lock) {
        if (pos == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long key = pos.asLong();
            Long last = LOCK_REPORTS.get(key);
            if (last != null && now - last < 1000L) {
                return;
            }
            LOCK_REPORTS.put(key, now);
            if (LOCK_REPORTS.size() > 64) {
                LOCK_REPORTS.clear();
            }
            String space = "world";
            try {
                if (world instanceof Level level && subLevelAt(levelForLookup(level), pos) != null) {
                    space = "sublevel";
                }
            } catch (Throwable ignored) {
                // an unreadable structure is reported as world, which is what the point then is
            }
            if (lock == null) {
                // A refusal is the one thing this line has to be able to say: "no source answered"
                // is news, silence is not.
                FireControlCompat.LOGGER.info(
                        "{}ir-atgm lock: source=none target=- space={} detail=no-source pos={} spots={}",
                        PREFIX, space, pos.toShortString(), laserSpots());
                return;
            }
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm lock: source={} target={} space={} detail={} pos={}",
                    PREFIX, lockName(lock), lock.point(), space, lock.detail(), pos.toShortString());
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** Lock probe reports, so the resolution line is one attempt per press, not one per tick. */
    private static final Map<Long, Long> LOCK_PROBES = new java.util.HashMap<>();

    /**
     * A pod's painted spot, asked for twice.
     *
     * <p>{@code IrAtgm.paintedSpot} measures the spot against the <em>muzzle</em> it is handed, and
     * {@link LaserSpots} stores spots where the pod's own ray found them. A launcher standing on a
     * Sable structure keeps its block position in the structure's plot - the user's own launcher
     * reads {@code 20481032, 984, 20532231} - while the pod paints somewhere near
     * {@code -180, -41, -4} in world space, so measuring the raw muzzle against a world spot is
     * 20 million blocks of "no lock" and the shot is refused on a launcher that has a designation
     * painted straight at it. The second attempt therefore uses the muzzle projected out of the
     * sub-level, and whichever attempt answers is normalised back into the launcher's own space
     * afterwards by {@link #lockInLauncherSpace}.</p>
     */
    private static Lock paintedLockAnySpace(LevelAccessor world, BlockPos pos, Vec3 muzzle) {
        if (!(world instanceof Level given) || pos == null) {
            return null;
        }
        // The table itself is level-agnostic; the readings below are what needs the right space.
        Level level = levelForLookup(given);
        java.util.List<Vec3> froms = new java.util.ArrayList<>(4);
        addReading(froms, muzzle);
        addReading(froms, project(level, muzzle));
        if (level != given) {
            addReading(froms, project(given, muzzle));
        }
        // A second anchor: the rack's own centre. A muzzle offset along the facing can leave the
        // structure's box for a rail standing on its edge, and then only the centre projects.
        addReading(froms, project(level, Vec3.atCenterOf(pos)));
        LaserSpots.Spot painted = null;
        Vec3 used = null;
        for (Vec3 from : froms) {
            try {
                LaserSpots.Spot candidate = IrAtgm.paintedSpot(level, pos, from);
                if (candidate != null) {
                    painted = candidate;
                    used = from;
                    break;
                }
            } catch (Throwable ignored) {
                // try the next space
            }
        }
        firecontrolcompat$probePainted(given, level, pos, muzzle, froms, painted, used);
        return painted == null ? null : podLock(painted);
    }

    /** Adds a reading to the candidate list, skipping nulls and duplicates. */
    private static void addReading(java.util.List<Vec3> froms, Vec3 reading) {
        if (reading == null) {
            return;
        }
        for (Vec3 existing : froms) {
            if (existing.distanceToSqr(reading) < 1.0E-6D) {
                return;
            }
        }
        froms.add(reading);
    }

    /** A point projected out of whatever structure it is stored on, or null when it cannot be. */
    private static Vec3 project(Level level, Vec3 point) {
        if (level == null || point == null) {
            return null;
        }
        try {
            Vec3 projected = SubLevelPositions.worldPosition(level, point);
            return projected == null || projected.distanceToSqr(point) < 1.0E-6D ? null : projected;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * One line per lock resolution attempt, unconditional the first time and then at most twice a
     * second per launcher.
     *
     * <p>This is the line that answers "the crew says it is locked, the round says no lock": it
     * prints how many readings were tried, where they are, how many spots are painting, and how far
     * the nearest one is from the reading that was used. A distance of tens of millions means a
     * reading in the wrong space; {@code spots=0} means nothing is painting at all (pod view off,
     * not a laser pod, or the pod is a different platform); {@code blocked} means the pod's own line
     * of sight is cut.</p>
     */
    private static void firecontrolcompat$probePainted(Level given, Level level, BlockPos pos, Vec3 muzzle,
            java.util.List<Vec3> froms, LaserSpots.Spot painted, Vec3 used) {
        try {
            long now = System.currentTimeMillis();
            long key = pos.asLong();
            Long last = LOCK_PROBES.get(key);
            if (last != null && now - last < 500L) {
                return;
            }
            LOCK_PROBES.put(key, now);
            if (LOCK_PROBES.size() > 64) {
                LOCK_PROBES.clear();
            }
            int spots = 0;
            double nearest = -1.0D;
            try {
                spots = LaserSpots.current().size();
                for (LaserSpots.Spot spot : LaserSpots.current()) {
                    for (Vec3 from : froms) {
                        double distance = spot.position().distanceTo(from);
                        if (nearest < 0.0D || distance < nearest) {
                            nearest = distance;
                        }
                    }
                }
            } catch (Throwable ignored) {
                nearest = -2.0D;
            }
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm lock probe: given={} lookup={} server={} readings={} muzzle={} project={} spots={} nearest={} found={} used={}",
                    PREFIX, given == null ? "-" : given.getClass().getSimpleName(),
                    level == null ? "-" : level.getClass().getSimpleName(),
                    level instanceof ServerLevel, froms.size(), muzzle,
                    froms.size() > 1 ? froms.get(1) : "-", spots,
                    nearest < 0.0D ? "-" : String.format(java.util.Locale.ROOT, "%.1f", nearest),
                    painted == null ? "-" : painted.code() + "(" + (painted.airborne() ? "air" : "ground") + ")",
                    used == null ? "-" : used);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** The level a look-up must run in: a server level, or the root level behind a plot level. */
    private static Level levelForLookup(Level level) {
        if (level instanceof ServerLevel) {
            return level;
        }
        if (level == null) {
            return null;
        }
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            Level root = container == null ? null : container.getLevel();
            if (root != null) {
                return root;
            }
        } catch (Throwable ignored) {
            // a level with no container is its own root
        }
        return level;
    }

    /** The server level behind a level that may be a plot level, or null. */
    private static ServerLevel serverLevelOf(Level level) {
        Level root = levelForLookup(level);
        return root instanceof ServerLevel server ? server : null;
    }

    /** A painted spot as a designation, named after the platform that is painting it. */
    private static Lock podLock(LaserSpots.Spot spot) {
        boolean airborne = false;
        int code = 0;
        try {
            airborne = spot.airborne();
            code = spot.code();
        } catch (Throwable ignored) {
            // a spot that cannot be read is treated as a ground designation
        }
        return new Lock(spot.position(), airborne ? LockSource.AIR_POD : LockSource.GROUND_EO,
                "pod:" + code);
    }

    /**
     * The lock, in the space the round will be flying in.
     *
     * <p>The round is spawned at the launcher's own muzzle and armed with {@code IrAtgm.armAtgm},
     * and {@code IrAtgm.topAttackPoint} compares the lock against the round's own
     * {@code getX()/getY()/getZ()} - so a lock left in world space while the round flies in a
     * sub-level makes the climb/dive maths nonsense even when the lock itself is real. Both readings
     * are therefore offered and the one nearer the muzzle wins: a real same-space point stays put
     * (inverting its pose would throw it across the world), and a world point lands on the launcher
     * (leaving it alone would).</p>
     */
    private static Vec3 lockInLauncherSpace(LevelAccessor world, BlockPos pos, Vec3 muzzle, Vec3 point) {
        if (point == null || muzzle == null) {
            return point;
        }
        Vec3 local = toLauncherSpace(world, pos, point);
        if (local == null || local == point) {
            return point;
        }
        return local.distanceToSqr(muzzle) < point.distanceToSqr(muzzle) ? local : point;
    }

    /**
     * The direction one round leaves the tube on: at the locked contact when that is inside the
     * off-boresight limit, down the launcher's own line when it is not.
     *
     * <p>Everything here happens in <b>one</b> coordinate space. The muzzle is the launcher's own
     * (a block offset from its stored block position, so a launcher on a Sable structure hands out a
     * point in that structure's storage space), and {@code lockPoint} arrives in whatever space its
     * source speaks - a pod or an optical lock answers in world space, a radar contact the launcher
     * already normalised does not. {@link #lockInLauncherSpace} puts the lock into the muzzle's own
     * space by offering both readings and keeping the one nearer the muzzle, and the launch direction
     * is then the plain difference of two points in that same space. Angles are unaffected by the
     * choice: a sub-level pose is a rigid transform, so the off-boresight angle is the same number
     * whichever of the two spaces it is measured in.</p>
     *
     * <p>The limit is {@link #OFF_BORESIGHT_HALF_ANGLE_DEGREES}. Past it the shot falls back to the
     * tube's own line - the round is still released (the seeker was locked, the crew asked for it),
     * it simply has to find the contact on its own once it is out.</p>
     */
    private static Release releaseAim(LevelAccessor world, BlockPos pos, Vec3 muzzle, Direction facing,
            Vec3 lockPoint) {
        Vec3 tube = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        if (tube.lengthSqr() < 1.0E-8) {
            tube = new Vec3(0.0, 0.0, 1.0);
        }
        tube = tube.normalize();
        Vec3 target = lockPoint == null || muzzle == null ? null : lockInLauncherSpace(world, pos, muzzle, lockPoint);
        if (target == null) {
            return new Release(tube, 0.0, null, false);
        }
        Vec3 toTarget = target.subtract(muzzle);
        if (toTarget.lengthSqr() < 1.0E-6) {
            return new Release(tube, 0.0, target, false);
        }
        Vec3 unit = toTarget.normalize();
        double dot = Math.max(-1.0, Math.min(1.0, unit.dot(tube)));
        double degrees = Math.toDegrees(Math.acos(dot));
        if (degrees > OFF_BORESIGHT_HALF_ANGLE_DEGREES) {
            return new Release(tube, degrees, target, false);
        }
        return new Release(unit, degrees, target, true);
    }

    /**
     * The one line a two step ground infrared release prints when the round actually leaves.
     *
     * <p>Muzzle and target are printed in <b>world</b> coordinates (the muzzle projected out of the
     * structure it was stored in) so the two are directly comparable by eye, while the direction
     * that was actually handed to the round is the one in the launcher's own space - see
     * {@link #releaseAim}. {@code space} names the space the launcher's block position lives in.</p>
     */
    private static void reportIrSeekerFire(LevelAccessor world, BlockPos pos, Vec3 localMuzzle, Release aim) {
        Level level = world instanceof Level given ? given : null;
        Vec3 worldMuzzle = localMuzzle;
        Vec3 worldTarget = aim == null ? null : aim.target();
        if (level != null) {
            try {
                worldMuzzle = SubLevelPositions.worldPosition(level, localMuzzle);
                if (worldTarget != null) {
                    // Both readings are in the launcher's own space (see releaseAim), so both are
                    // projected the same way: the line then compares two world points, and a
                    // mismatch would be visible as a distance, not hidden inside the direction.
                    worldTarget = SubLevelPositions.worldPosition(level, worldTarget);
                }
            } catch (Throwable ignored) {
                worldMuzzle = localMuzzle;
                worldTarget = aim == null ? null : aim.target();
            }
        }
        IrSeekerSession.report("fire", level, pos,
                aim == null ? null : Double.valueOf(aim.offBoresightDegrees()),
                worldMuzzle, worldTarget);
    }

    /** A world point expressed in the space of the sub-level that holds this block. */
    private static Vec3 toLauncherSpace(LevelAccessor world, BlockPos pos, Vec3 point) {        if (!(world instanceof Level level) || point == null) {
            return point;
        }
        try {
            SubLevel subLevel = subLevelAt(levelForLookup(level), pos);
            if (subLevel == null) {
                subLevel = subLevelAt(level, pos);
            }
            if (subLevel == null) {
                return point;
            }
            Vector3d local = new Vector3d(point.x, point.y, point.z);
            subLevel.logicalPose().transformPositionInverse(local);
            if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)) {
                return point;
            }
            return new Vec3(local.x, local.y, local.z);
        } catch (Throwable ignored) {
            return point;
        }
    }

    /**
     * The sub-level that owns a block, asked of the level given and of the root level behind it.
     *
     * <p>The same two readings {@link SubLevelPositions} makes: a launcher on a structure is found
     * either through the level its block entity reports or through the root container, depending on
     * which side of the plot/world split the caller happens to hold. Neither reading requires a
     * {@link ServerLevel}: a plot level is a level, and refusing it here was one of the ways a
     * designation on a structure was silently discarded.</p>
     */
    private static SubLevel subLevelAt(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
            if (subLevel != null && !subLevel.isRemoved()) {
                return subLevel;
            }
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            Level root = container == null ? null : container.getLevel();
            if (root != null && root != level) {
                subLevel = Sable.HELPER.getContaining(root, pos);
                if (subLevel != null && !subLevel.isRemoved()) {
                    return subLevel;
                }
            }
        } catch (Throwable ignored) {
            // no sub-level: the launcher stands in the world and nothing has to be converted
        }
        return null;
    }

    /**
     * Releases one infrared anti-tank round out of the launcher's own round slot.
     *
     * <p>The lock is the trigger: without a designation nothing is spent and every player close to
     * the launcher is told why, because a round fired blind would climb over nothing.</p>
     */
    /**
     * Whether the "no electro-optical lock" refusal is news for this launcher.
     *
     * <p>At most once every two seconds per launcher. The comparison is a real timestamp
     * difference - never {@code now - Long.MIN_VALUE} - so the first refusal after start-up
     * always reports.</p>
     */
    static boolean lockRefusalIsNews(BlockPos pos) {
        long now = System.currentTimeMillis();
        long key = pos.asLong();
        Long last = LOCK_REFUSALS.get(key);
        if (last != null && now - last < 2000L) {
            return false;
        }
        LOCK_REFUSALS.put(key, now);
        if (LOCK_REFUSALS.size() > 64) {
            LOCK_REFUSALS.clear();
        }
        return true;
    }

    private static boolean fireIrAtgm(LevelAccessor world, BlockPos pos, boolean specialMuzzle) {
        if (!(world instanceof ServerLevel lockLevel)) {
            return false;
        }
        Direction direction = facing(world, pos);
        Vec3 muzzle = specialMuzzle ? atgmMainMuzzle(pos, direction) : simpleMuzzle(pos, direction, 2.6, 0.0);
        // <b>This round is an anti-tank round, not an infrared air-to-air round.</b> A previous round
        // of work gave it the two step seeker release the air-defence family uses - the trigger
        // opened an infrared head, the head locked a heat source, and the round was then released
        // onto that contact. That made it behave like a homing infrared missile and threw away what
        // it actually is: a round that is told a *point* by the gunner's sight and flies over the top
        // to it. So the designation here is only ever the one the fire control computer reports -
        // pod paint, optical lock, ground-target snapshot, radar contact - and the seeker hand-over
        // is deliberately not consulted. `presetSeekerTarget` is not called either: the round's own
        // head is left to find a heat source by itself, and {@code IrAtgm#aimFor} only blends that
        // in the TERMINAL leg. CLIMB, CRUISE and DIVE fly the designated point.
        Lock lock = irAtgmLock(world, pos, muzzle);
        Vec3 locked = lock == null ? null : lock.point();
        int held = irAtgmRounds(world, pos);
        String via = firePath();
        if (locked == null) {
            // A trigger held down, or a computer walking a launcher list, calls this many times a
            // tick: the refusal is real news once, not 20 times.
            if (lockRefusalIsNews(pos)) {
                FireControlCompat.LOGGER.info(
                        "{}ir-atgm fire: refused reason=no-lock spots={} route=ground via={} rounds={}"
                                + " seeker=none pos={}",
                        PREFIX, laserSpots(), via, held, pos.toShortString());
                for (net.minecraft.server.level.ServerPlayer player : lockLevel.players()) {
                    if (player.distanceToSqr(Vec3.atCenterOf(pos)) <= 256.0D) {
                        message(player, "需要光电锁定");
                    }
                }
            }
            return true;
        }
        IrAtgmMissileEntity round = BeamMissileRegistry.IR_ATGM_TANSHE.get().create(lockLevel);
        if (round == null) {
            return false;
        }
        String lockName = lockName(lock);
        if (!spendIrAtgmRound(world, pos)) {
            FireControlCompat.LOGGER.info(
                    "{}ir-atgm fire: refused reason=no-round route=ground via={} lock={} rounds={} pos={}",
                    PREFIX, via, lockName, held, pos.toShortString());
            return false;
        }
        Release aim = releaseAim(world, pos, muzzle, direction, locked);
        round.markLaunchSource(pos);
        markLaunchSubLevel(round, lockLevel, pos);
        round.setPos(muzzle.x, muzzle.y, muzzle.z);
        round.shoot(aim.direction().x, aim.direction().y + 0.15, aim.direction().z, 3.0F, 0.0F);
        round.setBaseDamage(20.0F);
        round.setSilent(true);
        MissileGuidanceData.copyFromLauncher(world, pos, round);
        IrAtgm.armAtgm(round, locked);
        boolean added = lockLevel.addFreshEntity(round);
        // The round is in the world now; the interception sweep may judge it from the next tick on
        // (its first twenty ticks are its birth grace window, see InFlightRounds#GUARD_TICKS).
        com.qkdream.firecontrolcompat.missile.InFlightRounds.track(round);
        IrAtgm.reportLaunch(round, locked, true);
        playLaunchSound(world, pos, 5.0F, 0.8F);
        sendBlockUpdated(world, pos);
        reportIrSeekerFire(world, pos, muzzle, aim);
        // The round the launcher's own procedure would have spawned is 面包学's
        // antitankmissile_1; ours is what actually leaves the tube, and the two round counts either
        // side of it are the "did the launcher spend a round" test fire control grades a shot with.
        //
        // `spawn` is the round's own UUID and `tick` its age at the moment it was let go, so a
        // launch can be matched against the interception / skip lines that name the same UUID
        // (see MathIntercept: `math-intercept(missile): missile=<uuid>` and
        // `math-intercept skip: missile=<uuid> reason=..`).
        //
        // `lock` is the coarse family the designation belongs to (OPTICAL covers a pod paint, an
        // optical lock and a ground-target snapshot; RADAR is a radar contact); `source` is the exact
        // answer with its evidence. `seeker=none` is the statement that this round is NOT released
        // onto an infrared contact - that release belongs to the air-defence round.
        // `top-attack=` says which profile was armed at the rail: the four leg pop-up, or the flat
        // direct leg a shot inside {@code IrAtgm.CLOSE_TARGET_RANGE} gets.
        String topAttack = IrAtgm.isCloseTarget(round)
                ? "flat-close(<=" + (int) IrAtgm.CLOSE_TARGET_RANGE + ")"
                : "climb->dive";
        FireControlCompat.LOGGER.info(
                "{}ir-atgm fire: via={} route=ground lock={} source={} seeker=none top-attack={}"
                        + " offBoresight={} {} spawn={} type={} tick={}"
                        + " rounds={}->{} added={} stored=slot0 target={} muzzle={} pos={}",
                PREFIX, via, lockFamily(lock), lock.detail(), topAttack,
                String.format(java.util.Locale.ROOT, "%.1f", aim.offBoresightDegrees()),
                aim.offAxis() ? "off-axis" : "boresight", spawnUuid(round), spawnedRoundId(round),
                spawnedTick(round), held, irAtgmRounds(world, pos), added, locked, muzzle,
                pos.toShortString());
        return added || com.qkdream.firecontrolcompat.missile.MathGuidedMissiles.spawned(round, false);
    }

    /** The lock source as it goes into a log line. */
    public static String lockName(Lock lock) {
        return lock == null || lock.source() == null ? "none" : lock.source().name();
    }

    /**
     * The coarse family a designation belongs to, for the launch line: {@code OPTICAL} for anything
     * the gunner aims (pod paint, optical lock, ground-target snapshot), {@code RADAR} for a radar
     * contact, and {@code none} when there is no designation at all.
     *
     * <p>Three of the four {@code LockSource} values are the same thing to a crew - something the
     * sight is pointed at - so the launch line names the family and leaves the exact answer to
     * {@code source=}.</p>
     */
    public static String lockFamily(Lock lock) {
        if (lock == null || lock.source() == null) {
            return "none";
        }
        return switch (lock.source()) {
            case GROUND_EO, AIR_POD, OPTICAL -> "OPTICAL";
            case RADAR -> "RADAR";
            case SEEKER -> "SEEKER";
        };
    }

    /** The spawned round's own UUID, so a launch can be matched to its interception lines. */
    static String spawnUuid(Entity round) {
        try {
            return round == null || round.getUUID() == null ? "-" : String.valueOf(round.getUUID());
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /** The spawned round's age in ticks at the moment it was released (always 0 for a new round). */
    static int spawnedTick(Entity round) {
        try {
            return round == null ? -1 : round.tickCount;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Registry id of the round that was spawned, for the launch line. Never throws. */
    private static String spawnedRoundId(Entity round) {
        try {
            EntityType<?> type = round == null ? null : round.getType();
            if (type == null) {
                return "firecontrolcompat:ir_atgm_tanshe";
            }
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            return id == null ? String.valueOf(type) : String.valueOf(id);
        } catch (Throwable ignored) {
            return "firecontrolcompat:ir_atgm_tanshe";
        }
    }

    /**
     * The spots a pod is painting right now, for the refusal line.
     *
     * <p>A refused shot says "no electro-optical lock", which is true whether no pod is painting at
     * all (pod view off, nothing in the way) or a pod is painting somewhere this launcher cannot
     * see it. {@code spots} tells those apart in one look.</p>
     */
    static String laserSpots() {
        try {
            String described = LaserSpots.describe();
            return described == null || described.isEmpty() ? "-" : described;
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /**
     * Who pulled the trigger: fire control's computer, or the launcher's own redstone pulse.
     *
     * <p>0.9.4 wraps the reflective launch it performs in
     * {@code FireControlMissileCommandGuard.enter()} (its {@code MianbaoMissileCompat.fireOne}
     * :85-87), and that guard is readable - {@code isLaunching()} - from inside the very procedure
     * call it encloses, which is where this mod's shot is taken. A redstone trigger on the launcher
     * block never enters the guard, so the two are told apart without a second mixin. The lookup is
     * cached and every failure answers "pulse", which is the older path and never a refusal.</p>
     */
    private static String firePath() {
        java.lang.reflect.Method launching = firecontrolcompat$guardMethod();
        if (launching == null) {
            return "pulse";
        }
        try {
            Object answer = launching.invoke(null);
            return answer instanceof Boolean active && active ? "controller" : "pulse";
        } catch (Throwable ignored) {
            return "pulse";
        }
    }

    private static java.lang.reflect.Method firecontrolcompat$guardMethod;

    private static boolean firecontrolcompat$guardLooked;

    private static java.lang.reflect.Method firecontrolcompat$guardMethod() {
        if (!firecontrolcompat$guardLooked) {
            firecontrolcompat$guardLooked = true;
            try {
                Class<?> guard = Class.forName(
                        "com.hooya.stabilizedturret.content.controller.FireControlMissileCommandGuard");
                firecontrolcompat$guardMethod = guard.getMethod("isLaunching");
            } catch (Throwable ignored) {
                firecontrolcompat$guardMethod = null;
            }
        }
        return firecontrolcompat$guardMethod;
    }

    public static boolean tryFireAtgm(LevelAccessor world, double x, double y, double z, boolean specialMuzzle) {
        if (world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        // The pack owns every round these launchers used to take except our own top attack one,
        // which the pack has no counterpart for: that one keeps its own path below.
        if (irAtgmRounds(world, pos) > 0) {
            return fireIrAtgm(world, pos, specialMuzzle);
        }
        if (NATIVE_AIR_DEFENCE_TAKES_OVER) {
            // the pack fires its own anti-tank round
            return false;
        }
        double slot1 = nbtDouble(world, pos, ATGM_AMMO_KEY_1);
        double slot2 = nbtDouble(world, pos, ATGM_AMMO_KEY_2);
        double beamAmmo = nbtDouble(world, pos, BEAM_AMMO_KEY);
        double loiterAmmo = nbtDouble(world, pos, LOITER_ATGM_AMMO_KEY);
        double irAmmo = nbtDouble(world, pos, IR_ATGM_AMMO_KEY);
        double topAmmo = nbtDouble(world, pos, IR_ATGM_TOP_AMMO_KEY);
        boolean beam = beamAmmo > 0.0;
        boolean loitering = loiterAmmo > 0.0;
        boolean infrared = irAmmo > 0.0;
        boolean topAttack = topAmmo > 0.0;
        if (!beam && !loitering && !infrared && !topAttack || slot1 <= 0.0 && slot2 <= 0.0) {
            return false;
        }
        Direction direction = facing(world, pos);
        Vec3 muzzle = specialMuzzle ? atgmMainMuzzle(pos, direction) : simpleMuzzle(pos, direction, 2.6, 0.0);
        if (topAttack) {
            // A launcher loaded by an earlier build: the round lives in the old counter rather
            // than in slot zero. That case no longer reaches this branch - irAtgmRounds answers
            // for the counter too and fireIrAtgm spends it - so this is only the statement of
            // what the counter means, kept for a build that ever turns the switch above off.
            if (!(world instanceof ServerLevel lockLevel)) {
                return false;
            }
            Vec3 locked = irAtgmLockPoint(world, pos, muzzle);
            if (locked == null) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] ir-atgm: locked=false target=none launch=refused reason=no-electro-optical-lock pos={}",
                        pos);
                for (net.minecraft.server.level.ServerPlayer player : lockLevel.players()) {
                    if (player.distanceToSqr(Vec3.atCenterOf(pos)) <= 256.0D) {
                        message(player, "需要光电锁定");
                    }
                }
                return true;
            }
            if (slot1 > 0.0) {
                slot1 -= 1.0;
                nbtPutDouble(world, pos, ATGM_AMMO_KEY_1, slot1);
            } else {
                slot2 -= 1.0;
                nbtPutDouble(world, pos, ATGM_AMMO_KEY_2, slot2);
            }
            nbtPutDouble(world, pos, IR_ATGM_TOP_AMMO_KEY, Math.max(0.0, topAmmo - 1.0));
            IrAtgmMissileEntity round = BeamMissileRegistry.IR_ATGM_TANSHE.get().create(lockLevel);
            if (round == null) {
                return false;
            }
            round.markLaunchSource(pos);
            markLaunchSubLevel(round, lockLevel, pos);
            round.setPos(muzzle.x, muzzle.y, muzzle.z);
            round.shoot(direction.getStepX(), direction.getStepY() + 0.15, direction.getStepZ(), 3.0F, 0.0F);
            round.setBaseDamage(20.0F);
            round.setSilent(true);
            MissileGuidanceData.copyFromLauncher(world, pos, round);
            IrAtgm.armAtgm(round, locked);
            boolean added = lockLevel.addFreshEntity(round);
            IrAtgm.reportLaunch(round, locked, true);
            playLaunchSound(world, pos, 5.0F, 0.8F);
            sendBlockUpdated(world, pos);
            return added || com.qkdream.firecontrolcompat.missile.MathGuidedMissiles.spawned(round, false);
        }
        if (infrared) {
            // Same two step release as the AA launcher: the trigger press parked
            // the contact, this call spends the round on it.
            if (!(world instanceof ServerLevel serverLevel)) {
                return false;
            }
            IrSeekerSession.Lock lock = IrSeekerSession.takeHandOver(serverLevel, pos);
            if (slot1 > 0.0) {
                slot1 -= 1.0;
                nbtPutDouble(world, pos, ATGM_AMMO_KEY_1, slot1);
            } else {
                slot2 -= 1.0;
                nbtPutDouble(world, pos, ATGM_AMMO_KEY_2, slot2);
            }
            nbtPutDouble(world, pos, IR_ATGM_AMMO_KEY, Math.max(0.0, irAmmo - 1.0));
            presetSeekerTarget(world, spawnBeamMissile(world, pos, muzzle, direction, 3.0F,
                    BeamMissileRegistry.INFRARED_TANSHE.get(), null), lock);
            playLaunchSound(world, pos, 5.0F, 0.8F);
            sendBlockUpdated(world, pos);
            return true;
        }
        if (slot1 > 0.0) {
            slot1 -= 1.0;
            nbtPutDouble(world, pos, ATGM_AMMO_KEY_1, slot1);
        } else {
            slot2 -= 1.0;
            nbtPutDouble(world, pos, ATGM_AMMO_KEY_2, slot2);
        }
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
            spawnBeamMissile(world, pos, muzzle, direction, 3.0F, BeamMissileRegistry.INFRARED_TANSHE.get(), null);
            nbtPutDouble(world, pos, IR_ATGM_AMMO_KEY, Math.max(0.0, nbtDouble(world, pos, IR_ATGM_AMMO_KEY) - 1.0));
        }
        playLaunchSound(world, pos, 5.0F, 0.8F);
        sendBlockUpdated(world, pos);
        return true;
    }

    /**
     * Mixed launcher: the AA bay of that rig used to hold the beam-riding round.
     *
     * <p>That round is retired, so the bay takes nothing of ours any more and the pack's own mixed
     * loading applies.</p>
     */
    public static boolean tryLoadMixed(LevelAccessor world, double x, double y, double z, Entity entity) {
        return false;
    }

    public static boolean tryFireMixed(LevelAccessor world, double x, double y, double z) {
        if (NATIVE_AIR_DEFENCE_TAKES_OVER) {
            // the pack fires its own mixed rounds
            return false;
        }
        if (world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (nbtDouble(world, pos, BEAM_AMMO_KEY) <= 0.0 || nbtDouble(world, pos, MIXED_AA_KEY) <= 0.0) {
            return false;
        }
        Direction direction = facing(world, pos);
        spawnBeamMissile(world, pos, simpleMuzzle(pos, direction, 2.6, 0.0), direction, 4.0F,
                BeamMissileRegistry.INFRARED_TANSHE.get(), null);
        nbtPutDouble(world, pos, MIXED_AA_KEY, Math.max(0.0, nbtDouble(world, pos, MIXED_AA_KEY) - 1.0));
        nbtPutDouble(world, pos, BEAM_AMMO_KEY, Math.max(0.0, nbtDouble(world, pos, BEAM_AMMO_KEY) - 1.0));
        playLaunchSound(world, pos, 3.0F, 0.8F);
        sendBlockUpdated(world, pos);
        return true;
    }

    /**
     * Whether the block is 面包学's loaded close-range air-to-air rack.
     *
     * <p>That block is the aircraft infrared round's loaded form: the pack's own close-range round
     * loads into the same block, which is why {@link #IR_RACK_KEY} - not the block - decides whose
     * round a rail carries. The block is taken from the pack's registry, never spelled out.</p>
     */
    public static boolean isCloseRangeRackBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        try {
            return state.getBlock() == MianbaosModernwarfareModBlocks.CLOSE_MISSILE_1RACK.get();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Writes exactly one per-round flag onto the rail: this mod's infrared round.
     *
     * <p>Reloading a rail used to set the new flag and keep the old ones, so a rail that had ever
     * carried an infrared round went on answering "infrared" after another round replaced it - the
     * seeker HUD then offered its search line on a shot that was not a seeker shot at all. The
     * anti-radiation flag goes too: that round has its own block now.</p>
     */
    private static void writeIrFlags(LevelAccessor world, BlockPos pos) {
        try {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity == null) {
                return;
            }
            CompoundTag data = blockEntity.getPersistentData();
            data.remove(SA_RACK_KEY);
            data.remove(ARM_RACK_KEY);
            data.remove(IR_RACK_KEY);
            // RETIRED(ir-arm-rounds): this only clears what earlier builds wrote. The airborne
            // infrared round is withdrawn, so a rail that still carries its flag comes back empty
            // instead of answering for a round that can no longer fire.
            blockEntity.setChanged();
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /**
     * Aircraft weapon rack load: <b>RETIRED(ir-arm-rounds)</b>.
     *
     * <p>This mod used to swap an empty rack for 面包学's loaded close-range rack (its own airborne
     * infrared round) and for the anti-radiation rack, writing a per-round flag to tell the two
     * apart. Both rounds are the pack's business from the next version on, so nothing here claims a
     * rail: the pack's own item does its own load, and the rail answers for the pack's round.</p>
     */
    public static boolean tryLoadRack(LevelAccessor world, double x, double y, double z, Entity entity) {
        ItemStack held = heldItem(entity);
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] rail load step: pos={} item={} ours=false (retired: the pack loads it)",
                BlockPos.containing(x, y, z), held == null || held.isEmpty() ? "-" : held.getItem());
        return false;
    }

    /**
     * Aircraft weapon rack release: <b>RETIRED(ir-arm-rounds)</b>.
     *
     * <p>The rail is the pack's block and the round is the pack's round, so the pack's own launch
     * procedure releases it. Nothing here unloads the rail, mounts a display round, moves a round out
     * of a sub-level or hands a seeker over - all of that belonged to the two rounds this build no
     * longer carries.</p>
     */
    public static boolean tryFireRack(LevelAccessor world, double x, double y, double z) {
        return false;
    }

    /**
     * Fires a semi-active round out of a rack, keeping it illuminated at the moment of release.
     *
     * <p>A semi-active round is blind on its own: it follows whatever the launcher keeps lit. The
     * release therefore has to light it, or the seeker would have nothing from the first tick and
     * the round would behave like an unguided rocket. The launch side is otherwise the infrared
     * rack's own - same muzzle, same carrier velocity, the same hand-over of a seeker target - so
     * only the round, the speed and the illumination differ.</p>
     *
     * <p><b>TEMP-DISABLED(semi-active)</b>: the round is not registered, so this answers
     * {@code false} and every caller falls through to nothing. The original body is kept verbatim
     * in the commented block, so restoring the round is "uncomment the block, drop the
     * {@code return false}".</p>
     */
    public static boolean tryFireSemiActiveRack(LevelAccessor world, double x, double y, double z) {
        // TEMP-DISABLED(semi-active): the whole launch is off while the round is withdrawn.
        return false;
        /*
        if (NATIVE_AIR_DEFENCE_TAKES_OVER) {
            // the pack fires the semi-active round on the rail
            return false;
        }
        if (world.isClientSide()) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        Direction direction = facing(world, pos);
        Vec3 localMuzzle = closeRackMuzzle(pos, direction);
        IrSeekerSession.Lock lock = world instanceof ServerLevel serverLevel
                ? IrSeekerSession.takeHandOver(serverLevel, pos) : null;
        Vec3 carrierVelocity = carrierVelocityAt(world, pos);
        if (world instanceof ServerLevel serverLevel) {
            RackMountedRounds.unmount(serverLevel, pos);
        }
        BeamRidingMissileEntity round = spawnBeamMissileAtWorldMuzzle(world, pos, localMuzzle,
                direction, 6.0F, BeamMissileRegistry.SEMI_ACTIVE_TANSHE.get(), carrierVelocity);
        presetSeekerTarget(world, round, lock);
        if (round instanceof com.qkdream.firecontrolcompat.entity.SemiActiveMissileEntity semiActive) {
            semiActive.refreshIllumination();
        }
        playLaunchSound(world, pos, 5.0F, 0.8F);
        replaceBlockPreservingData(world, pos, MianbaosModernwarfareModBlocks.RACK.get(), true);
        sendBlockUpdated(world, pos);
        return true;
        */
    }

    /** Anti-radiation release: <b>RETIRED(ir-arm-rounds)</b>, the pack fires its own round. */
    public static boolean tryFireAntiRadiationRack(LevelAccessor world, BlockPos pos) {
        return false;
    }

    /** Verbatim muzzle offsets of the mianbao close-missile rack go-procedure. */
    private static Vec3 closeRackMuzzle(BlockPos pos, Direction direction) {
        return new Vec3(
                pos.getX() + 0.5 + direction.getStepX() * 2.6,
                pos.getY() - 1.0 + direction.getStepY() * 2.6,
                pos.getZ() + 0.5 + direction.getStepZ() * 2.6
        );
    }

    /**
     * Whether the bound AA launcher carries infrared missiles.
     *
     * <p>2.6.5 keeps a launcher's ammunition in <b>container slot zero</b>, and so does this mod's
     * load path ({@code tryLoadAntiAir}); the old {@code fcirraammo} counter is no longer written by
     * anything. Reading only that counter - which is what this method did - therefore answered
     * {@code false} for every launcher a crew had actually loaded, which silently switched the two
     * step seeker off on the air-defence family: the fire press went straight to the pack's own
     * release and the head never opened. The slot is the answer; the counter is kept as a fallback
     * so a launcher loaded by an older build still counts.</p>
     */
    public static boolean hasIrAntiAirAmmo(Level level, BindingRef ref) {
        BlockPos pos = ref == null ? null : ref.pos();
        if (level == null || pos == null) {
            return false;
        }
        if (airDefenceRounds(level, pos) > 0) {
            return true;
        }
        return nbtDouble(level, pos, IR_AA_AMMO_KEY) > 0.0;
    }

    /**
     * Whether the bound missile launcher (ATGM family) carries ground infrared rounds.
     *
     * <p>The same slot-zero rule as the air-defence family, through
     * {@link #irAtgmRounds} - which already reads slot zero first and the retired
     * {@code fciratgmtop} counter second, so a relabelled launcher from an older build still
     * answers. The old body read the counter alone and so answered {@code false} for every launcher
     * this build actually loads, which is what took the two step seeker off the anti-tank family.</p>
     */
    public static boolean hasIrMissileLauncherAmmo(Level level, BindingRef ref) {
        BlockPos pos = ref == null ? null : ref.pos();
        return level != null && pos != null && irAtgmRounds(level, pos) > 0;
    }

    /** Whether the bound launcher (either family) carries our beam-riding round. */
    public static boolean hasBeamAmmo(Level level, BindingRef ref) {
        BlockEntity blockEntity = ref == null ? null : ref.resolve(level);
        return blockEntity != null && blockEntity.getPersistentData().getDouble(BEAM_AMMO_KEY) > 0.0;
    }

    /** Ground infrared rounds sitting in either launcher family. */
    public static boolean hasGroundIrAmmo(Level level, BindingRef ref) {
        return hasIrAntiAirAmmo(level, ref) || hasIrMissileLauncherAmmo(level, ref);
    }

    /** Whether the bound aircraft rack carries our infrared missile. <b>RETIRED(ir-arm-rounds)</b>. */
    public static boolean isIrRackLoaded(Level level, BindingRef ref) {
        return false;
    }

    /** Whether the bound aircraft rack carries the anti-radiation round. <b>RETIRED(ir-arm-rounds)</b>. */
    public static boolean isArmRackLoaded(Level level, BindingRef ref) {
        return false;
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

    /** Muzzle point of the ATGM family launcher at the given block position. */
    public static Vec3 missileLauncherMuzzle(LevelAccessor world, BlockPos pos) {
        return atgmMainMuzzle(pos, facing(world, pos));
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

    /** The point the fire control has locked, or null when nothing is locked. */
    private static Vec3 spruceTargetPoint(LevelAccessor world, BlockPos launcherPos) {
        Level level = world instanceof Level given ? given : null;
        ServerLevel serverLevel = level == null ? null : serverLevelOf(level);
        if (serverLevel == null) {
            return null;
        }
        Level lookup = levelForLookup(level);
        // Fire control computer: the launcher must be one of its bound launchers, ground or aircraft.
        for (StabilizerControllerBlockEntity controller : ControllerTracker.loadedControllers()) {
            if (controller == null || controller.isRemoved() || !controller.isRadarActive()) {
                continue;
            }
            Level controllerLevel = controller.getLevel();
            if (controllerLevel == null
                    || controllerLevel.dimension() != lookup.dimension()
                    || !firecontrolcompat$bindsLauncher(controller, launcherPos, lookup, controllerLevel)) {
                continue;
            }
            UUID locked = controller.getRadarLockedSubLevelId();
            if (locked == null) {
                continue;
            }
            StabilizerControllerBlockEntity.RadarContact contact = controller.getRadarContact(locked);
            if (contact != null) {
                return contact.center();
            }
        }
        // Connected display: the same hand over, from the contact its radar selected.
        for (ConnectedDisplayBlockEntity display : ConnectedDisplayBlockEntity.loadedDisplays()) {
            if (display == null || display.isRemoved()) {
                continue;
            }
            Level displayLevel = display.getLevel();
            if (displayLevel == null || displayLevel.dimension() != lookup.dimension()
                    || !display.isBound(BindingRef.capture(serverLevel, launcherPos))) {
                continue;
            }
            ConnectedDisplayBlockEntity.TargetKey key = display.getSelectedTarget();
            ConnectedDisplayBlockEntity.Contact contact = key == null ? null : display.getContact(key);
            if (contact != null) {
                return contact.center();
            }
        }
        return null;
    }

    /**
     * Whether this block is one of the blocks this computer fires or releases from.
     *
     * <p>All three binding lists are read: the anti-tank launchers ({@code missileLaunchers}), the AA
     * launchers, and the aircraft rails. A rail is only ever in the last one, so leaving it out meant
     * a rail bound to a cockpit never got a radar fallback. A raw block-position match is accepted as
     * well, because a binding captured in one space may not resolve in the other.</p>
     */
    private static boolean firecontrolcompat$bindsLauncher(StabilizerControllerBlockEntity controller,
            BlockPos launcherPos, Level... levels) {
        java.util.List<java.util.List<BindingRef>> lists = new java.util.ArrayList<>(3);
        try {
            lists.add(controller.getMissileLaunchers());
        } catch (RuntimeException | LinkageError ignored) {
            // a list that cannot be read is simply empty
        }
        try {
            lists.add(controller.getAaMissileLaunchers());
        } catch (RuntimeException | LinkageError ignored) {
            // ditto
        }
        try {
            lists.add(controller.getAircraftMissileRacks());
        } catch (RuntimeException | LinkageError ignored) {
            // ditto
        }
        for (java.util.List<BindingRef> list : lists) {
            if (list == null) {
                continue;
            }
            for (BindingRef launcher : list) {
                if (launcher == null) {
                    continue;
                }
                for (Level candidate : levels) {
                    if (candidate != null && launcher.matches(candidate, launcherPos)) {
                        return true;
                    }
                }
                try {
                    if (launcherPos.equals(launcher.pos())) {
                        return true;
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // an unresolvable binding is not a match
                }
            }
        }
        return false;
    }

    /** Easter egg: the crater the spruce round leaves at the point it was sent to. */
    private static void detonateSpruce(LevelAccessor world, Vec3 position) {
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos hit = BlockPos.containing(position);
        playSound(world, hit, "mianbaos_modernwarfare:missile_explode", SoundSource.NEUTRAL, 8.0F, 0.9F);
        playSound(world, hit, "mianbaos_modernwarfare:explode_far_sound3", SoundSource.NEUTRAL, 32.0F, 0.9F);
        serverLevel.explode(null, position.x, position.y, position.z,
                SPRUCE_EXPLOSION_STRENGTH, ExplosionInteraction.BLOCK);
    }

    private static BeamRidingMissileEntity spawnBeamMissile(LevelAccessor world, BlockPos launcherPos, Vec3 muzzle,
            Direction direction, float speed, EntityType<? extends BeamRidingMissileEntity> entityType, Vec3 carrierVelocity) {
        return spawnBeamMissile(world, launcherPos, muzzle,
                new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ()),
                speed, entityType, carrierVelocity);
    }

    /**
     * The same release with the launch direction given as a vector, which is what an off-boresight
     * shot needs: the round leaves along the locked contact, not along the launcher's facing. The
     * caller passes a unit vector in the same space as {@code muzzle}; everything else - the arc
     * bias, the carrier velocity, the ownership mark - is identical.
     */
    private static BeamRidingMissileEntity spawnBeamMissile(LevelAccessor world, BlockPos launcherPos, Vec3 muzzle,
            Vec3 launch, float speed, EntityType<? extends BeamRidingMissileEntity> entityType, Vec3 carrierVelocity) {
        if (!(world instanceof ServerLevel level)) {
            return null;
        }
        BeamRidingMissileEntity missile = entityType.create(level);
        if (missile == null) {
            return null;
        }
        Vec3 direction = launch == null || launch.lengthSqr() < 1.0E-8 ? new Vec3(0.0, 0.0, 1.0) : launch.normalize();
        missile.markLaunchSource(launcherPos);
        missile.setPos(muzzle.x, muzzle.y, muzzle.z);
        missile.shoot(direction.x, direction.y + 0.05, direction.z, speed, 0.0F);
        missile.setBaseDamage(20.0F);
        missile.setSilent(true);
        MissileGuidanceData.copyFromLauncher(world, launcherPos, missile);
        markLaunchSubLevel(missile, level, launcherPos);
        // A maid fire control release arms the interception point the round has
        // to fly; the mark has to be on the round before it joins the level.
        BeamLeadMark.applyTo(missile);
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
        return missile;
    }

    /**
     * Hands the locked contact to the round the launcher just released, together
     * with the head the launcher was running: a round released on an image lock
     * flies the rest of its life blind to countermeasures.
     */
    private static void presetSeekerTarget(LevelAccessor world, BeamRidingMissileEntity missile,
            IrSeekerSession.Lock lock) {
        if (lock != null && missile instanceof InfraredMissileEntity infrared) {
            infrared.presetSeekerTarget(lock.entity(), lock.subLevel(), lock.shaolib());
            if (world instanceof ServerLevel level) {
                infrared.setImageLock(IrSeekerSession.imageLockActive(level));
            }
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
    static BeamRidingMissileEntity spawnBeamMissileAtWorldMuzzle(LevelAccessor world, BlockPos launcherPos,
            Vec3 localMuzzle, Direction direction, float speed, EntityType<? extends BeamRidingMissileEntity> entityType,
            Vec3 carrierVelocity) {
        return spawnBeamMissileAtWorldMuzzle(world, launcherPos, localMuzzle, direction, speed, entityType,
                carrierVelocity, null);
    }

    /**
     * The same launch, with the top-attack point written onto the round <b>before</b> it joins.
     *
     * <p>Ordering is the whole reason for the extra parameter: fire control's mathematical flight
     * captures a round in its join event and cancels the join, so a point written after
     * {@code addFreshEntity} is written after the round has already been taken - and a captured
     * round that was not armed climbs over nothing. Arming first costs nothing on the entity route:
     * the point is simply there when the first tick asks for it.</p>
     */
    static BeamRidingMissileEntity spawnBeamMissileAtWorldMuzzle(LevelAccessor world, BlockPos launcherPos,
            Vec3 localMuzzle, Direction direction, float speed, EntityType<? extends BeamRidingMissileEntity> entityType,
            Vec3 carrierVelocity, Vec3 atgmTarget) {
        if (!(world instanceof ServerLevel level)) {
            return null;
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
            return null;
        }
        missile.markLaunchSource(BlockPos.containing(worldMuzzle));
        markLaunchSubLevel(missile, level, launcherPos);
        missile.setPos(worldMuzzle.x, worldMuzzle.y, worldMuzzle.z);
        missile.shoot(worldDirection.x, worldDirection.y + 0.05, worldDirection.z, speed, 0.0F);
        missile.setBaseDamage(20.0F);
        missile.setSilent(true);
        MissileGuidanceData.copyFromLauncher(world, launcherPos, missile);
        if (atgmTarget != null && missile instanceof IrAtgmMissileEntity atgm) {
            // Armed before the join: see the parameter's note.
            IrAtgm.armAtgm(atgm, atgmTarget);
        }
        boolean added = level.addFreshEntity(missile);
        // Registered here rather than only on the round's first tick: the interception sweep needs
        // the round's birth tick, and a round whose first tick the level skips would otherwise be
        // registered with an age it never had (see InFlightRounds).
        com.qkdream.firecontrolcompat.missile.InFlightRounds.track(missile);
        missile.moveTo(worldMuzzle.x, worldMuzzle.y, worldMuzzle.z);
        Vec3 velocity = missile.getDeltaMovement();        if (carrierVelocity != null && carrierVelocity.lengthSqr() > 1.0E-8) {
            velocity = velocity.add(carrierVelocity);
        }
        missile.setDeltaMovement(velocity);
        missile.setYRot((float) Math.toDegrees(Mth.atan2(velocity.x, velocity.z)));
        missile.setXRot((float) Math.toDegrees(Mth.atan2(velocity.y, velocity.horizontalDistance())));
        boolean entityTicking = level.isPositionEntityTicking(BlockPos.containing(worldMuzzle));
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] aircraft ir missile spawned added={} removed={} addedToLevel={} entityTicking={} local={} world={} dir={} carrier={}",
                added, missile.isRemoved(), missile.isAddedToLevel(), entityTicking, localMuzzle, worldMuzzle, worldDirection, carrierVelocity);
        return missile;
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
     *
     * <p>Public so the 面包学 rocket hook can hand a rocket the speed of the
     * structure it was fired from; the position is sublevel-local there too,
     * because an entity standing on a moving ship is ticked in the ship's own
     * space.</p>
     */
    public static Vec3 carrierVelocityAt(LevelAccessor world, BlockPos localPos) {
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

    /**
     * Velocity of the Sable structure the given entity is standing in, or zero
     * when it is not on one.
     *
     * <p>Left here for the launchers, which still read the carrier of a rack or a
     * tube. The rocket round used to read it through this too, so that a rocket
     * fired from a moving ship kept the ship's speed; fire control 0.9.3 does that
     * itself now ({@code MianbaoRocketPodCompat#carrierVelocityAtMuzzle}), and two
     * inheritances of the same velocity is exactly the doubled shot that release
     * fixed, so nothing on the rocket path calls this any more.</p>
     */
    public static Vec3 carrierVelocityOf(Entity entity) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        try {
            return carrierVelocityAt(entity.level(), entity.blockPosition());
        } catch (Throwable ignored) {
            return Vec3.ZERO;
        }
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
            // Every per-round flag goes, not just the two: a rail that keeps a stale flag keeps
            // answering for a round that is no longer on it.
            saved.remove(IR_RACK_KEY);
            saved.remove(ARM_RACK_KEY);
            saved.remove(SA_RACK_KEY);
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
