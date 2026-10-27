package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts a launcher's real round count back into fire control's launcher ammo count, and says out
 * loud what the count was read from.
 *
 * <h2>0.9.4 counts the wrong place</h2>
 *
 * <p>Fire control 0.9.4 counts a launcher with {@code MianbaoLauncherAmmo.airDefense(BlockEntity)}
 * ({@code (Lnet/minecraft/world/level/block/entity/BlockEntity;)I}). When the launcher block
 * entity is a container - and every 面包学 launcher is, they all extend
 * {@code RandomizableContainerBlockEntity} - the method reads <em>slot zero</em> and nothing else:
 * a stack of {@code portableantiairmissile_2} or fire control's own
 * {@code portableantiairmissile} answers with its stack size, every other stack answers
 * <b>zero</b>. The {@code missileammo} persistent counter is only read in the other branch,
 * the one for a launcher that is not a container at all - which no 面包学 launcher is.</p>
 *
 * <h2>What 2.6.5 actually does with the rounds (javap + vineflower, this round)</h2>
 *
 * <p>{@code AntiairmissilelauncherreloadProcedure} (2.6.5) does <b>not</b> write a
 * {@code missileammo} counter any more - that was the older build kept in
 * {@code _analysis/decomp/mianbao}. It writes
 * {@code IItemHandlerModifiable.setStackInSlot(0, new ItemStack(PORTABLEANTIAIRMISSILE_2, amount + 1))}
 * through {@code LegacyInventoryAccess.getHandler(be)}, i.e. the launcher's own container, and
 * {@code AntiairmissilelaunchergoProcedure} takes one round back out of that same slot 0 with
 * {@code shrink(1)}. The ammo of a 2.6.5 launcher <em>is</em> container slot zero, and the
 * round item is {@code portableantiairmissile_2}.</p>
 *
 * <p>So this mixin reads slot zero itself as well instead of trusting the pack's answer: the
 * launcher the computer resolves and the launcher the pack's {@code resolve()} resolves are not
 * always the same block entity (fire control rebuilds a missing one through
 * {@code resolveRebuiltAirDefenseLauncher}), and a rebuilt block entity answers zero from a slot
 * that is really full. The container read here is the same {@code Container#getItem(0)} the pack
 * uses, so the two agree whenever the pack's answer is right, and the launcher stays loaded when
 * it is not.</p>
 *
 * <h2>The line this mixin is here to print</h2>
 *
 * <p>{@code aa-ammo: native=… slot=… ours=… total=…} is printed <b>unconditionally</b> for the
 * first {@link #PROBE_CALLS} reads and then rate limited: "the anti-air missile never fires" has
 * to be told apart from "the count was never asked for", and a conditional line (the old one only
 * printed when the count moved) cannot tell those two apart. {@code native} is the pack's own
 * answer, {@code slot} is slot zero read here, {@code ours} is this mod's own rounds,
 * {@code total} is what is handed back.</p>
 *
 * <h2>0.9.4 descriptor</h2>
 *
 * <p>{@code airDefense} is {@code (BlockEntity)I} in 0.9.4 - the {@code String blockPath} of
 * 0.8.x/0.9.0 is gone, {@code antiTank} still takes it. The handlers must match their target's
 * current descriptor exactly: {@code require = 0} only covers "no match", a match with the wrong
 * descriptor still throws {@code InvalidInjectionException}, and only on the lazy load of this
 * class.</p>
 */
@Mixin(targets = "com.hooya.stabilizedturret.content.controller.MianbaoLauncherAmmo")
public abstract class MianbaoLauncherAmmoMixin {

    /** How many reads are logged without any rate limit, so a first shot always leaves a trace. */
    private static final int PROBE_CALLS = 20;

    /** The pack's own round item for this launcher family, as the reload procedure names it. */
    private static final String PACK_AA_ROUND = "portableantiairmissile_2";

    /** 面包学's anti-tank rounds, the two the load procedure writes into slot zero. */
    private static final String PACK_AT_ROUND = "antitankmissile_1";

    private static final String PACK_AT_ROUND_SECOND = "antitankmissile_2";

    /** This mod's infrared anti-tank round, loaded into the same slot zero. */
    private static final String OUR_IR_ATGM_ROUND = "ir_atgm";

    /** This mod's infrared air-defense round, loaded into the same slot zero of an AA launcher. */
    private static final String OUR_INFRARED_AA_ROUND = "infrared_missile";

    /** The persistent counter 0.8.x/0.9.0 used, still read: an old world keeps its rounds. */
    private static final String LEGACY_COUNTER = "missileammo";

    /**
     * One-shot "the injection really applied" line. This class is only touched when a
     * launcher's ammo is counted, which is a lazy load, so a descriptor mismatch would
     * otherwise stay invisible until somebody fired.
     */
    private static boolean firecontrolcompat$applied;

    private static void firecontrolcompat$confirmApplied(String which) {
        if (firecontrolcompat$applied) {
            return;
        }
        firecontrolcompat$applied = true;
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] launcher ammo mixin applied: airDefense(BlockEntity) + antiTank(BlockEntity,String) first={}",
                which);
    }

    /**
     * Air-defense launcher: slot zero (2.6.5's own ammo), the old counter, plus this mod's
     * beam-riding, infrared and spruce rounds.
     */
    @Inject(method = "airDefense", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$aaRounds(
            BlockEntity launcher, CallbackInfoReturnable<Integer> cir) {
        firecontrolcompat$confirmApplied("airDefense");
        int nativeAmmo = cir.getReturnValueI();
        int slot = firecontrolcompat$slotZeroAmmo(launcher);
        CompoundTag data = firecontrolcompat$data(launcher);
        int ours = 0;
        int legacy = 0;
        if (data != null) {
            ours = firecontrolcompat$floor(data.getDouble(BeamMissileCompat.BEAM_AMMO_KEY))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.IR_AA_AMMO_KEY))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.SPRUCE_AMMO_KEY));
            legacy = firecontrolcompat$floor(data.getDouble(BeamMissileCompat.AA_AMMO_KEY))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.MIXED_AA_KEY))
                    + firecontrolcompat$floor(data.getDouble(LEGACY_COUNTER));
        }
        int total = firecontrolcompat$total(nativeAmmo, slot, ours, legacy);
        firecontrolcompat$probe("aa", launcher, nativeAmmo, slot, ours, legacy, total);
        if (total <= nativeAmmo) {
            // Nothing to add and nothing to repair: leave the pack's own answer alone.
            return;
        }
        cir.setReturnValue(total);
    }

    /**
     * Anti-tank launcher: the pack's own round in slot zero, plus this mod's rounds.
     *
     * <p>2.6.5 keeps an anti-tank launcher's ammunition in container slot zero
     * ({@code MianbaoLauncherAmmo.antiTank} :24-32 answers 0 for anything that is not
     * {@code antitankmissile_1}, or {@code _2} on every rig but the quadruple one). Our own infrared
     * anti-tank round is loaded into <em>that same slot</em>, which is why it is counted from the
     * stack below and not from a key of ours; otherwise a computer that can see the round would
     * still refuse to fire it, and a shot would never show up as a drop in ammunition.</p>
     */
    @Inject(method = "antiTank", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$atRounds(
            BlockEntity launcher, String blockPath, CallbackInfoReturnable<Integer> cir) {
        firecontrolcompat$confirmApplied("antiTank");
        int nativeAmmo = cir.getReturnValueI();
        int slot = firecontrolcompat$slotZeroAmmo(launcher, true);
        CompoundTag data = firecontrolcompat$data(launcher);
        int ours = 0;
        int legacy = 0;
        if (data != null) {
            ours = firecontrolcompat$floor(data.getDouble(BeamMissileCompat.BEAM_AMMO_KEY))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.IR_ATGM_AMMO_KEY))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.LOITER_ATGM_AMMO_KEY));
            legacy = firecontrolcompat$floor(data.getDouble(BeamMissileCompat.ATGM_AMMO_KEY_1))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.ATGM_AMMO_KEY_2))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.MIXED_AT_KEY_1))
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.MIXED_AT_KEY_2))
                    // Rounds loaded by an earlier build of this mod, which counted them here.
                    + firecontrolcompat$floor(data.getDouble(BeamMissileCompat.IR_ATGM_TOP_AMMO_KEY));
        }
        int total = firecontrolcompat$total(nativeAmmo, slot, ours, legacy);
        firecontrolcompat$probe("at", launcher, nativeAmmo, slot, ours, legacy, total);
        if (total <= nativeAmmo) {
            return;
        }
        cir.setReturnValue(total);
    }

    /**
     * The count to hand back.
     *
     * <p>The pack answered something: that is the live number, this mod's rounds go on top. The
     * pack answered zero: the launcher's slot zero, the launcher's old counter, or this mod's own
     * rounds - whichever is largest - is the real count. It is only ever <em>raised</em>, so a
     * launcher this mod knows nothing about keeps exactly the value the pack computed.</p>
     */
    private static int firecontrolcompat$total(int fromPack, int slot, int ours, int legacy) {
        if (fromPack > 0) {
            return fromPack + ours;
        }
        return Math.max(Math.max(slot, legacy), ours);
    }

    /**
     * Slot zero read here, the way 2.6.5 stores the rounds.
     *
     * <p>The pack's air-defence round and fire control's own {@code portableantiairmissile} count,
     * because the pack's counter accepts either ({@code OrdinaryAirDefenseLauncher.isRound}). With
     * {@code antiTank} the anti-tank rounds count as well, and so does this mod's
     * {@code ir_atgm}, which is loaded into that same slot. Only a stack whose item really is an
     * ammunition item is counted: a container slot holding something else (a wrench, a spare
     * barrel) is zero rounds, which is also what the pack answers.</p>
     */
    private static int firecontrolcompat$slotZeroAmmo(BlockEntity launcher) {
        return firecontrolcompat$slotZeroAmmo(launcher, false);
    }

    private static int firecontrolcompat$slotZeroAmmo(BlockEntity launcher, boolean antiTank) {
        try {
            if (!(launcher instanceof Container container) || container.getContainerSize() <= 0) {
                return 0;
            }
            ItemStack stack = container.getItem(0);
            if (stack == null || stack.isEmpty()) {
                return 0;
            }
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (PACK_AA_ROUND.equals(path) || "portableantiairmissile".equals(path)
                    // This mod's infrared round, loaded into the same slot zero by
                    // BeamMissileCompat#tryLoadAntiAir. Without this line the launcher really
                    // holds rounds while the computer reads zero, and the shot is never attempted:
                    // "the infrared round does not go into the air-defense launcher".
                    || OUR_INFRARED_AA_ROUND.equals(path)) {
                return Math.max(0, stack.getCount());
            }
            if (antiTank && (PACK_AT_ROUND.equals(path) || PACK_AT_ROUND_SECOND.equals(path)
                    || OUR_IR_ATGM_ROUND.equals(path))) {
                return Math.max(0, stack.getCount());
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** The item in slot zero, for the log line: "-" when the slot is empty or unreadable. */
    private static String firecontrolcompat$slotZeroItem(BlockEntity launcher) {
        try {
            if (!(launcher instanceof Container container) || container.getContainerSize() <= 0) {
                return "-";
            }
            ItemStack stack = container.getItem(0);
            if (stack == null || stack.isEmpty()) {
                return "-";
            }
            return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        } catch (Throwable ignored) {
            return "-";
        }
    }

    private static int firecontrolcompat$probes;

    private static long firecontrolcompat$lastProbe;

    /**
     * The unconditional line.
     *
     * <p>Unconditional for the first {@link #PROBE_CALLS} reads - that is what proves the handler
     * runs on <em>every</em> count, which is the question the missing {@code launcher ammo aa}
     * lines left open - and rate limited after that so a computer that asks every tick cannot bury
     * the log.</p>
     */
    private static void firecontrolcompat$probe(String family, BlockEntity launcher,
            int nativeAmmo, int slot, int ours, int legacy, int total) {
        firecontrolcompat$probes++;
        if (firecontrolcompat$probes > PROBE_CALLS) {
            long now = System.currentTimeMillis();
            if (firecontrolcompat$lastProbe != 0L && now - firecontrolcompat$lastProbe < 2000L) {
                return;
            }
            firecontrolcompat$lastProbe = now;
        }
        try {
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-ammo: family={} native={} slot={} item={} ours={} legacy={} total={} container={} at={} call={}",
                    family, nativeAmmo, slot, firecontrolcompat$slotZeroItem(launcher), ours, legacy, total,
                    launcher instanceof Container,
                    launcher == null ? "-" : launcher.getBlockPos().toShortString(),
                    firecontrolcompat$probes);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    private static int firecontrolcompat$floor(double value) {
        return value > 0.0 && Double.isFinite(value) ? (int) Math.floor(value) : 0;
    }

    private static CompoundTag firecontrolcompat$data(BlockEntity launcher) {
        if (launcher == null) {
            return null;
        }
        try {
            return launcher.getPersistentData();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
