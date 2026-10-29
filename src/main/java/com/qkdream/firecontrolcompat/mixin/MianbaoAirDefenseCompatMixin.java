package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseLauncher;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileRegistry;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.HeavyAirDefenseCompat;
import com.qkdream.firecontrolcompat.MianbaoAaFireCompat;
import com.qkdream.firecontrolcompat.NevaLaunchCompat;
import dev.ryanhcode.sable.Sable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches fire control's air-defense pipeline about the vertical silo and
 * the heavy air-defense missile: the silo counts as an AA launcher, the
 * missile is adopted by the AA guidance (controller or connected display),
 * can be seduced by countermeasure flares, and detonates with the ground
 * cruise missile warhead doubled.
 */
@Mixin(MianbaoAirDefenseCompat.class)
public abstract class MianbaoAirDefenseCompatMixin {

    @Inject(method = "isLauncher", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$siloLauncher(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && (HeavyAirDefenseCompat.isSiloHead(state) || NevaLaunchCompat.isNevaLauncher(state))) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isMissile", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyMissile(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && entity != null
                && (entity.getType() == BeamMissileRegistry.HEAVY_AA_TANSHE.get()
                || entity.getType() == BeamMissileRegistry.NEVA_TANSHE.get())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isMissileType", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyMissileType(EntityType<?> type, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && (type == BeamMissileRegistry.HEAVY_AA_TANSHE.get()
                || type == BeamMissileRegistry.NEVA_TANSHE.get())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isHighSpeedMissile", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyIsHighSpeed(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && entity != null
                && (entity.getType() == BeamMissileRegistry.HEAVY_AA_TANSHE.get()
                || entity.getType() == BeamMissileRegistry.NEVA_TANSHE.get())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "nativeExplosionStrength", at = @At("RETURN"), cancellable = true)
    private static void firecontrolcompat$heavyWarhead(Entity missile, CallbackInfoReturnable<Float> cir) {
        if (missile != null && missile.getType() == BeamMissileRegistry.HEAVY_AA_TANSHE.get()) {
            cir.setReturnValue(12.0F);
        }
        if (missile != null && missile.getType() == BeamMissileRegistry.NEVA_TANSHE.get()) {
            cir.setReturnValue(16.0F);
        }
    }

    @Inject(method = "getAmmoCount", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$siloAmmo(Level level, BindingRef ref, CallbackInfoReturnable<Integer> cir) {
        BlockEntity blockEntity = firecontrolcompat$launcher(level, ref);
        if (blockEntity != null) {
            if (NevaLaunchCompat.isNevaLauncher(blockEntity.getBlockState())) {
                cir.setReturnValue(1);
            } else {
                cir.setReturnValue(Math.max(0,
                        (int) Math.floor(blockEntity.getPersistentData()
                                .getDouble(HeavyAirDefenseCompat.HEAVY_AA_AMMO_KEY))));
            }
        }
    }

    /**
     * The number fire control finally believes, together with the block it was read from.
     *
     * <p>{@code MianbaoLauncherAmmoMixin} prints the pack's own arithmetic, but it only ever sees
     * a {@link BlockEntity}. When {@code BindingRef#resolve} answers null the pack falls back to
     * {@code resolveRebuiltAirDefenseLauncher}, which hands back a <em>fresh</em> block entity: a
     * real {@code Container} with an empty slot zero and no persistent data. That rebuild reads as
     * "zero rounds" however full the launcher in the world is, and the arithmetic line alone
     * cannot tell it apart from an honestly empty launcher. This line names the block and says
     * whether the entity had to be rebuilt, so the two cases are distinguishable.</p>
     */
    @Inject(method = "getAmmoCount", at = @At("RETURN"))
    private static void firecontrolcompat$reportAmmo(Level level, BindingRef ref,
            CallbackInfoReturnable<Integer> cir) {
        firecontrolcompat$ammoProbes++;
        if (firecontrolcompat$ammoProbes > AMMO_PROBE_CALLS) {
            long now = System.currentTimeMillis();
            if (firecontrolcompat$lastAmmoProbe != 0L
                    && now - firecontrolcompat$lastAmmoProbe < 2000L) {
                return;
            }
            firecontrolcompat$lastAmmoProbe = now;
        }
        try {
            BlockEntity direct = null;
            try {
                direct = ref == null || level == null ? null : ref.resolve(level);
            } catch (Throwable ignored) {
                direct = null;
            }
            boolean rebuilt = false;
            BlockEntity used = direct;
            if (used == null) {
                used = firecontrolcompat$launcher(level, ref);
                rebuilt = used != null;
            }
            String block = "-";
            String pos = "-";
            if (used != null) {
                try {
                    block = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(used.getBlockState().getBlock()));
                    pos = used.getBlockPos().toShortString();
                } catch (Throwable ignored) {
                    // diagnostics only
                }
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-ammo(ref): ammo={} resolved={} rebuilt={} block={} at={} ref={} call={}",
                    cir.getReturnValueI(), direct != null, rebuilt, block, pos,
                    ref == null ? "-" : ref.pos(), firecontrolcompat$ammoProbes);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** First this many {@code aa-ammo(ref)} reads print unconditionally. */
    private static final int AMMO_PROBE_CALLS = 20;

    private static int firecontrolcompat$ammoProbes;

    private static long firecontrolcompat$lastAmmoProbe;

    /**
     * The vertical silo and the Neva launcher fire themselves.
     *
     * <h2>Both overloads (0.9.4)</h2>
     *
     * <p>0.9.4 declares <b>two</b> {@code fireOne} methods - verified with {@code javap -s} on
     * {@code create-fire-control-0.9.4.jar}:</p>
     * <pre>
     *   fireOne(Level, BindingRef)                                    -> one-line delegate
     *   fireOne(Level, BindingRef, StabilizerControllerBlockEntity)   -> the implementation
     * </pre>
     * <p>{@code StabilizerControllerBlockEntity.fireNextAaMissile} (:693) calls the
     * <b>three-argument</b> one, so a probe on the two-argument delegate never runs: that is exactly
     * the shape of a log with a satisfied {@code aa-gate} line and no {@code aa fire:} line.</p>
     *
     * <p>Because the name is overloaded, every handler here names its target with the <b>full
     * descriptor</b> instead of the bare name. A bare name is resolved against whichever overload the
     * injector happens to pick, and a handler whose parameters do not match that one is either a
     * silent skip (two or more matches) or a fatal {@code InvalidInjectionException} (one match) -
     * the crash this class took earlier. The descriptor form leaves no choice to make.</p>
     */

    /** The delegate, which the pack's public API and several of its call sites use. */
    @Inject(method = "fireOne(Lnet/minecraft/world/level/Level;"
            + "Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$siloFireTwoArg(Level level, BindingRef ref,
            CallbackInfoReturnable<Boolean> cir) {
        firecontrolcompat$siloFire0(level, ref, cir);
    }

    /** The real one, the one the computer calls: the silo and the Neva launcher fire themselves. */
    @Inject(method = "fireOne(Lnet/minecraft/world/level/Level;"
            + "Lcom/hooya/stabilizedturret/content/controller/BindingRef;"
            + "Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$siloFireThreeArg(Level level, BindingRef ref,
            StabilizerControllerBlockEntity computer, CallbackInfoReturnable<Boolean> cir) {
        firecontrolcompat$siloFire0(level, ref, cir);
    }

    /**
     * The computer's own shot, printed before the pack can swallow it.
     *
     * <p>{@code aa-fireone: called} is the line that separates "the gate never let the launcher
     * through" from "the launcher was asked and refused": it is the first thing on the only path
     * {@code fireNextAaMissile} takes, and it names the round that is actually on the rail.</p>
     */
    @Inject(method = "fireOne(Lnet/minecraft/world/level/Level;"
            + "Lcom/hooya/stabilizedturret/content/controller/BindingRef;"
            + "Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity;)Z",
            at = @At("HEAD"), require = 0)
    private static void firecontrolcompat$fireThreeHead(Level level, BindingRef ref,
            StabilizerControllerBlockEntity computer, CallbackInfoReturnable<Boolean> cir) {
        firecontrolcompat$fireProbes++;
        if (!firecontrolcompat$fireProbeOpen() && firecontrolcompat$fireProbes > FIRE_PROBE_CALLS) {
            return;
        }
        try {
            int ammo = -1;
            boolean ordinary = false;
            boolean resolved = false;
            String round = "none";
            try {
                BlockEntity blockEntity = ref == null || level == null ? null : ref.resolve(level);
                resolved = blockEntity != null;
                ammo = MianbaoAirDefenseCompat.getAmmoCount(level, ref);
                ordinary = blockEntity != null && OrdinaryAirDefenseLauncher.hasRound(blockEntity);
                round = MianbaoAaFireCompat.roundName(blockEntity);
            } catch (Throwable ignored) {
                // the probe is never worth a crash
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-fireone: called ref={} computer={} ammo={} resolved={}"
                            + " ordinary={} round={} call={}",
                    ref == null ? "-" : ref.pos(),
                    computer == null ? "-" : computer.getBlockPos().toShortString(),
                    ammo, resolved, ordinary, round, firecontrolcompat$fireProbes);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /**
     * ...and what became of it. When the pack refuses the shot, this mod launches the round itself.
     *
     * <p>The refusal that matters is 面包学's ordinary-round launch resolving its spawn level with
     * {@code server.getLevel(level.dimension())}: inside a Sable sub-level that answers null, so a
     * fully loaded launcher on a moving craft answers {@code false} with nothing in the log.
     * {@link MianbaoAaFireCompat#tryFire} repeats the launch in the level the launcher really stands
     * in, and prints every step it took.</p>
     */
    @Inject(method = "fireOne(Lnet/minecraft/world/level/Level;"
            + "Lcom/hooya/stabilizedturret/content/controller/BindingRef;"
            + "Lcom/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity;)Z",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$fireThreeOutcome(Level level, BindingRef ref,
            StabilizerControllerBlockEntity computer, CallbackInfoReturnable<Boolean> cir) {
        boolean ok = cir.getReturnValueZ();
        if (!ok) {
            try {
                if (MianbaoAaFireCompat.tryFire(level, ref, computer)) {
                    cir.setReturnValue(true);
                    ok = true;
                }
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] aa-fireone: fallback threw ref={} reason={}",
                        ref == null ? "-" : ref.pos(), failure.toString());
            }
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] aa-fireone: ok={} ref={}",
                ok, ref == null ? "-" : ref.pos());
        firecontrolcompat$kindJudgement(level, ref, computer, ok);
    }

    /**
     * The one line that says whether the round that went out is the kind the operator selected.
     *
     * <p>Emitted for every {@code fireOne} call, whether this mod's filtered walk released it or
     * the pack's own walk did, so "the heavy fired while 普通防空 was selected" can no longer be a
     * matter of opinion: {@code matches-selected=false} with a non-{@code none} {@code selected} is
     * exactly that fault, and {@code selected=none} means the operator never chose a kind (the
     * pack's own behaviour is then in force and nothing was overridden).</p>
     */
    private static void firecontrolcompat$kindJudgement(Level level, BindingRef ref,
            StabilizerControllerBlockEntity computer, boolean fired) {
        try {
            com.qkdream.firecontrolcompat.AaWeaponSlots.Kind railKind =
                    com.qkdream.firecontrolcompat.AaWeaponSlots.kindOf(level, ref);
            com.qkdream.firecontrolcompat.AaWeaponSlots.Kind selected =
                    com.qkdream.firecontrolcompat.AaWeaponSlots.selected(computer);
            String matches = selected == null ? "unknown"
                    : (railKind == selected ? "true" : "false");
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-kind: released={} kind={} selected={} matches-selected={}"
                            + " fired={} round={}",
                    ref == null ? "-" : ref.pos(),
                    railKind == null ? "unknown" : railKind.id(),
                    selected == null ? "none" : selected.id(), matches, fired,
                    MianbaoAaFireCompat.roundName(ref == null || level == null ? null : ref.resolve(level)));
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** First this many {@code fireOne} calls print unconditionally, then one a second. */
    private static final int FIRE_PROBE_CALLS = 20;

    private static int firecontrolcompat$fireProbes;

    private static long firecontrolcompat$lastFireProbe;

    /** Whether this call is inside the printed window (the first {@value #FIRE_PROBE_CALLS}). */
    private static boolean firecontrolcompat$fireProbeOpen() {
        if (firecontrolcompat$fireProbes <= FIRE_PROBE_CALLS) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (firecontrolcompat$lastFireProbe != 0L && now - firecontrolcompat$lastFireProbe < 1000L) {
            return false;
        }
        firecontrolcompat$lastFireProbe = now;
        return true;
    }

    private static void firecontrolcompat$siloFire0(Level level, BindingRef ref,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            BlockEntity blockEntity = firecontrolcompat$launcher(level, ref);
            if (blockEntity == null) {
                return;
            }
            if (NevaLaunchCompat.isNevaLauncher(blockEntity.getBlockState())) {
                cir.setReturnValue(NevaLaunchCompat.tryLaunch(blockEntity.getLevel(),
                        blockEntity.getBlockPos().getX(), blockEntity.getBlockPos().getY(),
                        blockEntity.getBlockPos().getZ()));
            } else if (blockEntity.getPersistentData().getDouble(HeavyAirDefenseCompat.HEAVY_AA_AMMO_KEY) > 0.0) {
                cir.setReturnValue(HeavyAirDefenseCompat.tryLaunchSilo(blockEntity.getLevel(),
                        blockEntity.getBlockPos().getX(), blockEntity.getBlockPos().getY(),
                        blockEntity.getBlockPos().getZ()));
            }
        } catch (Throwable ignored) {
            // fall through to the pack's own launcher path
        }
    }

    /**
     * One rate-limited line whenever fire control asks a bound air-defense launcher for a
     * shot, so "the anti-air missile never fires" can be told apart from "it fires and
     * misses". Both gates that can swallow the shot - the ammo count reading zero, and the
     * pack's fire procedure having no entry point - show up here as {@code ammo=0} /
     * {@code ok=false}.
     *
     * <p>Hooked on the two-argument delegate with a full descriptor; the three-argument
     * implementation the computer calls has its own probe above.</p>
     */
    @Inject(method = "fireOne(Lnet/minecraft/world/level/Level;"
            + "Lcom/hooya/stabilizedturret/content/controller/BindingRef;)Z",
            at = @At("RETURN"), require = 0)
    private static void firecontrolcompat$reportFire(Level level, BindingRef ref,
            CallbackInfoReturnable<Boolean> cir) {
        long now = System.currentTimeMillis();
        if (firecontrolcompat$lastFireReport != 0L && now - firecontrolcompat$lastFireReport < 1000L) {
            // ...but the very first shot of a session always prints.
            if (firecontrolcompat$fireReported) {
                return;
            }
        }
        firecontrolcompat$lastFireReport = now;
        firecontrolcompat$fireReported = true;
        int ammo = -1;
        String block = "-";
        try {
            ammo = MianbaoAirDefenseCompat.getAmmoCount(level, ref);
        } catch (Throwable ignored) {
            // the readback is diagnostic only
        }
        try {
            BlockEntity resolved = ref == null ? null : ref.resolve(level);
            block = resolved == null ? "none"
                    : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(resolved.getBlockState().getBlock()));
        } catch (Throwable ignored) {
            block = "-";
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] aa fire: ok={} ammo={} ref={} block={}",
                cir.getReturnValueZ(), ammo, ref == null ? "-" : ref.pos(), block);
    }

    private static boolean firecontrolcompat$fireReported;

    private static long firecontrolcompat$lastFireReport;

    @Inject(method = "getLauncherMuzzlePosition", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$siloMuzzle(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        BlockEntity blockEntity = firecontrolcompat$launcher(level, ref);
        if (blockEntity != null) {
            Vec3 local = Vec3.atCenterOf(blockEntity.getBlockPos()).add(0.0, 2.0, 0.0);
            cir.setReturnValue(Sable.HELPER.projectOutOfSubLevel(blockEntity.getLevel(), local));
        }
    }

    @Inject(method = "getLauncherDirection", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$siloDirection(Level level, BindingRef ref, CallbackInfoReturnable<Vec3> cir) {
        BlockEntity blockEntity = firecontrolcompat$launcher(level, ref);
        if (blockEntity != null) {
            Vec3 localBase = Vec3.atCenterOf(blockEntity.getBlockPos());
            Vec3 localFront = localBase.add(0.0, 1.0, 0.0);
            Vec3 worldBase = Sable.HELPER.projectOutOfSubLevel(blockEntity.getLevel(), localBase);
            Vec3 worldFront = Sable.HELPER.projectOutOfSubLevel(blockEntity.getLevel(), localFront);
            Vec3 direction = worldFront.subtract(worldBase);
            if (direction.lengthSqr() >= 1.0E-8) {
                cir.setReturnValue(direction.normalize());
            }
        }
    }

    private static BlockEntity firecontrolcompat$launcher(Level level, BindingRef ref) {
        if (level == null || ref == null) {
            return null;
        }
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null) {
            blockEntity = ref.resolveRebuiltAirDefenseLauncher(level);
        }
        return blockEntity != null && blockEntity.getLevel() != null
                && (HeavyAirDefenseCompat.isSiloHead(blockEntity.getBlockState())
                || NevaLaunchCompat.isNevaLauncher(blockEntity.getBlockState())) ? blockEntity : null;
    }
}
