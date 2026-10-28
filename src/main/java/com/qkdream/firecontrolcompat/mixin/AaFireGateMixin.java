package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.FireControlWeaponInputs;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.MianbaoAaFireCompat;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says why a bound anti-air launcher did or did not fire.
 *
 * <h2>The two silent gates between the fire button and {@code fireOne}</h2>
 *
 * <p>0.9.4 fires a bound air-defense launcher through
 * {@code StabilizerControllerBlockEntity.fireSelectedGroundWeapon} (:546), which only reaches
 * {@code fireNextAaMissile} (:647) when a ground weapon is selected and is not the anti-tank one.
 * That second method then walks the bound launchers and, for each one that has rounds, asks
 * {@code selectedGroundWeapon.acceptsAaRound(ordinary)} (:670). The rule is rigid:</p>
 * <pre>
 *   acceptsAaRound(ordinary) == (this == (ordinary ? AIR_DEFENSE : HIGH_SPEED_AIR_DEFENSE))
 * </pre>
 * <p>and {@code ordinary} is "slot zero holds fire control's own
 * {@code portableantiairmissile}". A 面包学 launcher loaded through 面包学's own reload procedure
 * holds {@code portableantiairmissile_2} instead, so it is <b>not</b> ordinary and only accepts
 * the {@code HIGH_SPEED_AIR_DEFENSE} selection. When the selection does not match, the loop simply
 * advances to the next launcher and, having exhausted the list, reports
 * {@code aa_missile.depleted} - the operator sees "out of missiles" on a launcher that is full -
 * and {@code MianbaoAirDefenseCompat.fireOne} is <b>never called</b>. A session log with no
 * {@code aa fire:} line at all is exactly this shape.</p>
 *
 * <h2>What this mixin does about it</h2>
 *
 * <p>It prints the gate state on every press ({@code aa-press:}) and on every walk
 * ({@code aa-gate:}), so "no line at all", "every launcher at zero rounds" and "selection does
 * not accept this round" stop looking identical in a log. It then repairs the one case the
 * operator cannot be expected to understand: both anti-air selections are in the same slot of the
 * same menu, the round in the rail is what decides which one is correct, and a stale selection
 * outliving a reload must not leave a loaded launcher unable to fire. When the selected weapon is
 * one of the two anti-air entries, no bound launcher accepts it, and at least one launcher does
 * have rounds, the selection is moved to the entry that matches those rounds and the change is
 * reported. The anti-tank entry is never touched: it has its own fire path, its own round, and
 * moving it would break a working weapon.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class AaFireGateMixin {

    /** The first this many presses print unconditionally, so a first shot always leaves a trace. */
    private static final int PRESS_PROBE_CALLS = 8;

    private static int firecontrolcompat$pressProbes;

    private static long firecontrolcompat$lastPress;

    private static int firecontrolcompat$gateProbes;

    private static long firecontrolcompat$lastGate;

    /** The pack's selection field, looked up once: it is private and has no setter. */
    private static Field firecontrolcompat$groundWeaponField;

    private static boolean firecontrolcompat$groundWeaponLooked;

    /**
     * The fire button reaching the ground-weapon dispatcher, before any of its early returns.
     *
     * <p>This is the outermost place a shot can still be swallowed: {@code :547} requires input
     * actions 3 or 4 to be configured at all, and {@code :549} silently adopts the first bound
     * weapon when the selection is null. A press with no following {@code aa-gate:} line is
     * therefore a different fault from a gate that refused.</p>
     */
    @Inject(method = "fireSelectedGroundWeapon", at = @At("HEAD"))
    private void firecontrolcompat$press(CallbackInfo ci) {
        try {
            firecontrolcompat$pressProbes++;
            if (firecontrolcompat$pressProbes > PRESS_PROBE_CALLS) {
                long now = System.currentTimeMillis();
                if (firecontrolcompat$lastPress != 0L && now - firecontrolcompat$lastPress < 1000L) {
                    return;
                }
                firecontrolcompat$lastPress = now;
            }
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            FireControlWeaponInputs.GroundWeapon selected = self.getSelectedGroundWeapon();
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-press: selected={} aaLaunchers={} level={} call={}",
                    selected == null ? "none" : selected.name(),
                    self.getAaMissileLaunchers().size(),
                    self.getLevel() == null ? "null" : "ok",
                    firecontrolcompat$pressProbes);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /**
     * The gate walk, plus the one repair it can make on the operator's behalf.
     *
     * <p>The field is written reflectively rather than through {@code @Shadow}: a shadowed member
     * that a later 0.9.x renames aborts the whole mixin at load, while a failed lookup here only
     * loses the repair. The lookup itself is cached, and every step is inside the usual
     * {@code try}/catch.</p>
     */
    @Inject(method = "fireNextAaMissile", at = @At("HEAD"))
    private void firecontrolcompat$gate(CallbackInfo ci) {
        try {
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            Level level = self.getLevel();
            if (level == null) {
                return;
            }
            List<BindingRef> launchers = self.getAaMissileLaunchers();
            FireControlWeaponInputs.GroundWeapon selected = self.getSelectedGroundWeapon();
            // The cadence, before anything else on this press. The pack's own loop is what releases
            // the high-speed and heavy rails, and it has no interval for them - a held trigger
            // emptied every tube. `observeAmmo` recognises a shot the pack released (the count
            // dropped since the previous press) and `ready` is asked about every rail that could
            // answer this press.
            boolean anyReady = false;
            boolean sawCandidate = false;
            com.qkdream.firecontrolcompat.AaFireCadence.Kind cadenceKind = null;
            net.minecraft.core.BlockPos cadencePos = null;
            for (BindingRef ref : launchers) {
                int ammo = MianbaoAirDefenseCompat.getAmmoCount(level, ref);
                boolean ordinary = MianbaoAirDefenseCompat.hasOrdinaryRound(level, ref);
                com.qkdream.firecontrolcompat.AaFireCadence.observeAmmo(level, ref.pos(), ammo);
                if (ammo <= 0) {
                    continue;
                }
                if (selected != null && !selected.acceptsAaRound(ordinary)) {
                    continue;
                }
                BlockEntity railEntity = null;
                try {
                    railEntity = level.getBlockEntity(ref.pos());
                } catch (Throwable ignored) {
                    // a rail whose block entity cannot be read still counts as a candidate
                }
                String roundName = railEntity == null ? null : MianbaoAaFireCompat.roundName(railEntity);
                com.qkdream.firecontrolcompat.AaFireCadence.Kind kind =
                    com.qkdream.firecontrolcompat.AaFireCadence.kindOf(ordinary, roundName);
                sawCandidate = true;
                if (cadencePos == null) {
                    cadenceKind = kind;
                    cadencePos = ref.pos();
                }
                if (com.qkdream.firecontrolcompat.AaFireCadence.ready(level, ref.pos(), kind)) {
                    anyReady = true;
                }
            }
            if (sawCandidate && !anyReady) {
                // Every rail that could answer is still inside its interval. Nothing is fired, and
                // the line says which rail was asked and how long it has left.
                com.qkdream.firecontrolcompat.AaFireCadence.log(level, cadencePos, cadenceKind, false);
                ci.cancel();
                return;
            }
            int loaded = 0;
            int accepted = 0;
            boolean ordinaryFirst = false;
            boolean ammoSeen = false;
            for (BindingRef ref : launchers) {
                int ammo = MianbaoAirDefenseCompat.getAmmoCount(level, ref);
                if (ammo <= 0) {
                    continue;
                }
                boolean ordinary = MianbaoAirDefenseCompat.hasOrdinaryRound(level, ref);
                if (!ammoSeen) {
                    ammoSeen = true;
                    ordinaryFirst = ordinary;
                }
                loaded++;
                if (selected != null && selected.acceptsAaRound(ordinary)) {
                    accepted++;
                }
            }
            FireControlWeaponInputs.GroundWeapon before = selected;
            FireControlWeaponInputs.GroundWeapon after = firecontrolcompat$realign(self, selected, loaded, accepted, ordinaryFirst);
            firecontrolcompat$gateProbes++;
            if (firecontrolcompat$gateProbes > PRESS_PROBE_CALLS) {
                long now = System.currentTimeMillis();
                if (firecontrolcompat$lastGate != 0L && now - firecontrolcompat$lastGate < 1000L) {
                    return;
                }
                firecontrolcompat$lastGate = now;
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-gate: launchers={} loaded={} accepted={} selected={} round={} radar={} lock={} guidance={} realign={} call={}",
                    launchers.size(), loaded, accepted,
                    before == null ? "none" : before.name(),
                    loaded == 0 ? "-" : (ordinaryFirst ? "ordinary" : "pack-native"),
                    self.isRadarActive(),
                    self.getRadarLockedSubLevel() != null,
                    self.isOrdinaryAaGuidanceReady(),
                    before == after ? "none"
                            : (before == null ? "none" : before.name()) + "->"
                                    + (after == null ? "none" : after.name()),
                    firecontrolcompat$gateProbes);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /**
     * Every operator message the pack raises, so a refused shot names its own reason.
     *
     * <p>{@code fireNextAaMissile} answers a launcher it will not release with one of
     * {@code aa_missile.depleted}, {@code aa_missile.beam_missing}, {@code aa_missile.radar_inactive},
     * {@code aa_missile.target_missing} or {@code aa_missile.fire_failed} - and the last of those is
     * the one the pack prints when {@code fireOne} itself answered {@code false}. Without this line
     * the only trace of a refused shot is that nothing happened, and the five reasons look
     * identical.</p>
     */
    @Inject(method = "notifyMissileOperator", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$operatorMessage(net.minecraft.network.chat.Component message,
            CallbackInfo ci) {
        try {
            String text = message == null ? "-" : message.getString();
            if (text.equals(firecontrolcompat$lastOperatorMessage)) {
                return;
            }
            firecontrolcompat$lastOperatorMessage = text;
            FireControlCompat.LOGGER.info("[firecontrolcompat] aa-loop: operator message={}", text);
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    private static String firecontrolcompat$lastOperatorMessage = "";

    /**
     * The end of the walk, with the cursor it left behind.
     *
     * <p>{@code aa-gate} prints what the walk <em>should</em> find; this prints where it stopped. A
     * press whose gate line is followed by neither this line nor an {@code aa-fireone:} line is a
     * press that never reached {@code fireNextAaMissile} at all.</p>
     */
    @Inject(method = "fireNextAaMissile", at = @At("RETURN"), require = 0)
    private void firecontrolcompat$walkDone(CallbackInfo ci) {
        try {
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-loop: walk done launchers={} cursor={} selected={}",
                    self.getAaMissileLaunchers().size(), firecontrolcompat$cursor(self),
                    self.getSelectedGroundWeapon() == null ? "none"
                            : self.getSelectedGroundWeapon().name());
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /** The pack's private fire cursor, or -1 when a later version renamed it. */
    private static int firecontrolcompat$cursor(StabilizerControllerBlockEntity self) {
        Field field = firecontrolcompat$cursorField();
        if (field == null) {
            return -1;
        }
        try {
            return field.getInt(self);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Field firecontrolcompat$cursorField() {
        if (!firecontrolcompat$cursorLooked) {
            firecontrolcompat$cursorLooked = true;
            try {
                Field field = StabilizerControllerBlockEntity.class.getDeclaredField("aaMissileFireCursor");
                field.setAccessible(true);
                firecontrolcompat$cursor = field;
            } catch (Throwable ignored) {
                firecontrolcompat$cursor = null;
            }
        }
        return firecontrolcompat$cursor;
    }

    private static Field firecontrolcompat$cursor;

    private static boolean firecontrolcompat$cursorLooked;

    /**
     * Moves a stale anti-air selection onto the entry that matches the loaded rounds.
     *
     * <p>Only ever between {@code AIR_DEFENSE} and {@code HIGH_SPEED_AIR_DEFENSE}, and only when
     * the current selection accepts none of the loaded launchers while at least one of them has
     * rounds. Returns the selection in force afterwards, which is the caller's "did it change".</p>
     */
    private static FireControlWeaponInputs.GroundWeapon firecontrolcompat$realign(
            StabilizerControllerBlockEntity self, FireControlWeaponInputs.GroundWeapon selected,
            int loaded, int accepted, boolean ordinary) {
        if (selected == null || loaded <= 0 || accepted > 0
                || selected == FireControlWeaponInputs.GroundWeapon.ANTI_TANK) {
            return selected;
        }
        FireControlWeaponInputs.GroundWeapon wanted = ordinary
                ? FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE
                : FireControlWeaponInputs.GroundWeapon.HIGH_SPEED_AIR_DEFENSE;
        if (wanted == selected) {
            return selected;
        }
        Field field = firecontrolcompat$groundWeaponField();
        if (field == null) {
            return selected;
        }
        try {
            field.set(self, wanted);
            self.setChanged();
            return wanted;
        } catch (Throwable ignored) {
            return selected;
        }
    }

    /** The pack's private selection field, or null when a later version renamed it. */
    private static Field firecontrolcompat$groundWeaponField() {
        if (!firecontrolcompat$groundWeaponLooked) {
            firecontrolcompat$groundWeaponLooked = true;
            try {
                Field field = StabilizerControllerBlockEntity.class
                        .getDeclaredField("selectedGroundWeapon");
                field.setAccessible(true);
                firecontrolcompat$groundWeaponField = field;
            } catch (Throwable ignored) {
                firecontrolcompat$groundWeaponField = null;
            }
        }
        return firecontrolcompat$groundWeaponField;
    }
}
