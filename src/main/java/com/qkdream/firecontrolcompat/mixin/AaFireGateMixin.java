package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.FireControlWeaponInputs;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.AaWeaponSlots;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.MianbaoAaFireCompat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
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
 * {@code portableantiairmissile}". A 闈㈠寘瀛?launcher loaded through 闈㈠寘瀛?s own reload procedure
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
            // The operator's kind, when one was selected: only that kind may pace this press.
            // Without this filter a rail of *another* kind still inside its fire interval would
            // cancel the press even though the selected kind is ready - the same "the other rack
            // decides for this one" fault the firing walk is filtered for.
            AaWeaponSlots.Kind wantedKind = AaWeaponSlots.selected(self);
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
                if (wantedKind != null) {
                    // Only the selected kind paces this press. A kind that no rail carries at all
                    // leaves sawCandidate false, so the press is not cancelled here either - the
                    // walk clears such a stale selection and hands the press back to the pack.
                    if (AaWeaponSlots.kindOf(level, ref) != wantedKind) {
                        continue;
                    }
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
            // With an explicit kind selection the kind decides the family, so the old "move the
            // selection onto the loaded rounds" repair must not run: it reads the *first* loaded
            // rail and would flip the family away from the kind the operator picked.
            FireControlWeaponInputs.GroundWeapon after = wantedKind != null
                    ? before
                    : firecontrolcompat$realign(self, selected, loaded, accepted, ordinaryFirst);
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
            // The kind-aware walk. It takes the press over whenever a kind was selected, and when
            // the family holds more than one kind of round (see firecontrolcompat$fireSelectedKind),
            // so a computer whose anti-air rails are all of one kind and that never used the kind
            // selection keeps the pack's own walk untouched.
            if (firecontrolcompat$fireSelectedKind(self, level, self.getSelectedGroundWeapon())) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // a diagnostic line is never worth a crash
        }
    }

    /**
     * Releases the rail the operator's <b>kind</b> selection names, and nothing else.
     *
     * <h2>The overlap this exists for</h2>
     *
     * <p>{@code GroundWeapon} has three entries and only two of them are anti-air, so every
     * non-ordinary anti-air round in the pack - 闈㈠寘瀛?s {@code portableantiairmissile_2}, this
     * mod's {@code infrared_missile}, the heavy 48N6 in the vertical silo and the placed 娑呯摝 block -
     * answers to the single {@code HIGH_SPEED_AIR_DEFENSE} slot. The pack's walk (
     * {@code fireNextAaMissile} :647-698) then releases the first rail of that family whose cursor
     * it reaches, which is why "high-speed air defence" fired the heavy silo and the heavy's own
     * key press could be swallowed by the high-speed racks. Only {@code ANTI_TANK} escaped, because
     * it is a slot of its own.</p>
     *
     * <h2>When it takes the press over, and when it does not</h2>
     *
     * <p>Only when <b>both</b> of these hold: this family has more than one kind of round loaded,
     * and every loaded rail of the family could be classified. A family holding a single kind cannot
     * overlap with anything, so the pack's own walk is left exactly as it was - the ordinary round
     * path, the pack's high-speed path and any rail this mod does not recognise all keep working
     * untouched. That is deliberate: the reported fault is an overlap, and a computer without an
     * overlap must not change behaviour because of a fix for it.</p>
     *
     * <p>The walk itself is the pack's own, reproduced step for step (cursor start, ammo read,
     * {@code acceptsAaRound}, the ordinary/radar/lock gates, {@code fireOne}, cursor advance,
     * operator message), with the kind filter added. Every gate is read through the pack's public
     * API; the only private member touched is the fire cursor, through the same cached reflective
     * field the probes above already use.</p>
     *
     * @return whether this call took the press over (the pack's body must then not run)
     */
    private static boolean firecontrolcompat$fireSelectedKind(StabilizerControllerBlockEntity self,
            Level level, FireControlWeaponInputs.GroundWeapon selected) {
        if (self == null || level == null || selected == null
                || selected == FireControlWeaponInputs.GroundWeapon.ANTI_TANK) {
            return false;
        }
        List<BindingRef> launchers = self.getAaMissileLaunchers();
        if (launchers == null || launchers.isEmpty()) {
            return false;
        }
        AaWeaponSlots.Kind kind = AaWeaponSlots.selected(self);
        if (kind == null) {
            // No choice was ever made on this computer: the pack's own walk is correct until the
            // family really holds more than one kind of round, which is the overlap this class
            // exists for. An unclassifiable rail with rounds also blocks it - the pack must keep
            // releasing what this mod cannot name.
            for (BindingRef ref : launchers) {
                if (AaWeaponSlots.kindOf(level, ref) == null
                        && MianbaoAirDefenseCompat.getAmmoCount(level, ref) > 0) {
                    return false;
                }
            }
            List<AaWeaponSlots.Kind> loadedKinds = AaWeaponSlots.loaded(level, launchers, selected);
            if (loadedKinds.size() < 2) {
                // Nothing to tell apart: the pack's own walk is correct for one kind.
                return false;
            }
            AaWeaponSlots.Kind chosenKind = AaWeaponSlots.first(selected, loadedKinds);
            if (chosenKind == null) {
                return false;
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] weapon cycle: from=none to={} family={} candidates={} reason=no-selection",
                    chosenKind.id(), selected.name(), firecontrolcompat$kindList(loadedKinds));
            AaWeaponSlots.select(self, chosenKind);
            kind = chosenKind;
        }
        // Every rail of the selected kind, loaded or not. A rail of that kind with no rounds left is
        // a refusal - the operator asked for *this* round - while a kind no rail carries at all is a
        // selection left over from an earlier load-out: it is retired onto the first kind that is
        // still there (once, not repeatedly) and the press goes on with that one.
        List<BindingRef> rails = new ArrayList<>();
        List<BindingRef> mine = new ArrayList<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            rails.clear();
            mine.clear();
            for (BindingRef ref : launchers) {
                if (AaWeaponSlots.kindOf(level, ref) != kind) {
                    continue;
                }
                rails.add(ref);
                if (MianbaoAirDefenseCompat.getAmmoCount(level, ref) > 0) {
                    mine.add(ref);
                }
            }
            if (!rails.isEmpty() || attempt > 0) {
                break;
            }
            List<AaWeaponSlots.Kind> present = AaWeaponSlots.loadedAntiAir(level, launchers);
            if (present.isEmpty()) {
                break;
            }
            AaWeaponSlots.Kind retired = AaWeaponSlots.first(selected, present);
            if (retired == null || retired == kind) {
                break;
            }
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ground-select: from={} to={} candidates={} ammo={}"
                            + " reason=selection-retired launchers={} family={}",
                    kind.id(), retired.id(), firecontrolcompat$kindList(present),
                    AaWeaponSlots.ammoSummary(level, launchers, present),
                    launchers.size(), retired.family().name());
            AaWeaponSlots.select(self, retired);
            kind = retired;
        }
        if (rails.isEmpty()) {
            // Not one rail of any kind this class can name: nothing to decide here, and the pack's
            // own walk (and its own "missing"/"depleted" message) stays in charge.
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ground-select: from={} to=none candidates={} ammo={}"
                            + " reason=stale-selection launchers={} family={}",
                    kind.id(), firecontrolcompat$kindList(AaWeaponSlots.loadedAntiAir(level, launchers)),
                    AaWeaponSlots.ammoSummary(level, launchers,
                            AaWeaponSlots.loadedAntiAir(level, launchers)),
                    launchers.size(), selected.name());
            return false;
        }
        if (mine.isEmpty()) {
            // The selected kind is empty while other kinds still have rounds. The press is taken
            // over (and refused) so the pack cannot quietly release a different kind - "one rack
            // still has rounds" must never decide for the kind the operator chose.
            firecontrolcompat$tellOperator(self, "message.create_fire_control.aa_missile.depleted");
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-kind: released=none kind={} type={} rails={} matches-selected=true"
                            + " fired=false reason=no-rounds-of-kind others={}",
                    kind.id(), kind.family().name(), rails.size(),
                    AaWeaponSlots.ammoSummary(level, launchers,
                            AaWeaponSlots.loadedAntiAir(level, launchers)));
            return true;
        }
        int size = launchers.size();
        // The kind decides the family, never the other way round. Reading it from the kind here also
        // covers a selection whose family write was lost (a renamed private field in a later 0.9.x):
        // the walk still releases the round the operator picked instead of refusing everything.
        FireControlWeaponInputs.GroundWeapon family = kind.family();
        if (selected != family) {
            firecontrolcompat$writeFamily(self, family);
        }
        int start = Math.floorMod(Math.max(0, firecontrolcompat$cursor(self)), size);
        BindingRef chosen = null;
        String unavailable = "message.create_fire_control.aa_missile.depleted";
        for (int offset = 0; offset < size; offset++) {
            BindingRef ref = launchers.get((start + offset) % size);
            if (!firecontrolcompat$sameRail(ref, mine)) {
                // Not the selected kind, or out of rounds: skipped even though it may be loaded.
                continue;
            }
            boolean ordinary = MianbaoAirDefenseCompat.hasOrdinaryRound(level, ref);
            if (ordinary != kind.ordinary()) {
                // A rail whose round contradicts the selection: the kind decides, never the rail.
                continue;
            }
            if (!family.acceptsAaRound(ordinary)) {
                continue;
            }
            if (ordinary) {
                if (self.isOrdinaryAaGuidanceReady()) {
                    chosen = ref;
                    break;
                }
                unavailable = "message.create_fire_control.aa_missile.beam_missing";
            } else if (!self.isRadarActive()) {
                unavailable = "message.create_fire_control.aa_missile.radar_inactive";
            } else if (self.getRadarLockedSubLevel() != null) {
                chosen = ref;
                break;
            } else {
                unavailable = "message.create_fire_control.aa_missile.target_missing";
            }
        }
        if (chosen == null) {
            firecontrolcompat$tellOperator(self, unavailable);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] aa-kind: released=none kind={} type={} rails={} reason={}"
                            + " matches-selected=true fired=false",
                    kind.id(), family.name(), mine.size(), unavailable);
            return true;
        }
        // The fire interval of this rail, on the same table the gate uses.
        com.qkdream.firecontrolcompat.AaFireCadence.Kind cadenceKind =
                com.qkdream.firecontrolcompat.AaFireCadence.kindOf(kind.ordinary(), kind.cadenceWord());
        if (!com.qkdream.firecontrolcompat.AaFireCadence.ready(level, chosen.pos(), cadenceKind)) {
            com.qkdream.firecontrolcompat.AaFireCadence.log(level, chosen.pos(), cadenceKind, false);
            return true;
        }
        int index = 0;
        for (int i = 0; i < launchers.size(); i++) {
            if (launchers.get(i) == chosen || firecontrolcompat$sameRail(launchers.get(i), List.of(chosen))) {
                index = i;
                break;
            }
        }
        firecontrolcompat$setCursor(self, (index + 1) % size);
        int before = MianbaoAirDefenseCompat.getAmmoCount(level, chosen);
        boolean fired = MianbaoAirDefenseCompat.fireOne(level, chosen, self);
        self.setChanged();
        if (fired) {
            com.qkdream.firecontrolcompat.AaFireCadence.noteFired(level, chosen.pos());
        } else {
            firecontrolcompat$tellOperator(self, "message.create_fire_control.aa_missile.fire_failed");
        }
        FireControlCompat.LOGGER.info(
                "[firecontrolcompat] aa-kind: released={} kind={} type={} ammo={} fired={} rails={}"
                        + " matches-selected=true walk-start={}",
                chosen.pos(), kind.id(), family.name(), before, fired, mine.size(), start);
        return true;
    }

    /**
     * Writes the pack's private selection field.
     *
     * <p>Used where the chosen round kind has to decide the family: the pack's own setter only runs
     * for the family whose key was pressed, so without this the ordinary key could never select the
     * heavy silo (and the high-speed key could never select the pack's own ordinary round). A null
     * return from the field lookup, or a refused write, only loses that alignment.</p>
     */
    private static boolean firecontrolcompat$writeFamily(StabilizerControllerBlockEntity self,
            FireControlWeaponInputs.GroundWeapon family) {
        if (self == null || family == null) {
            return false;
        }
        Field field = firecontrolcompat$groundWeaponField();
        if (field == null) {
            return false;
        }
        try {
            field.set(self, family);
            self.setChanged();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether the rail is one of the given set, compared by position (BindingRef is not a record). */
    private static boolean firecontrolcompat$sameRail(BindingRef ref, List<BindingRef> candidates) {
        if (ref == null || ref.pos() == null) {
            return false;
        }
        for (BindingRef candidate : candidates) {
            if (candidate == ref) {
                return true;
            }
            if (candidate != null && ref.pos().equals(candidate.pos())) {
                return true;
            }
        }
        return false;
    }

    private static String firecontrolcompat$kindList(List<AaWeaponSlots.Kind> kinds) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < kinds.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(kinds.get(i).id());
        }
        return text.append(']').toString();
    }

    /**
     * The pack's operator message, called reflectively.
     *
     * <p>{@code notifyMissileOperator(Component)} is private in 0.9.4 (javap on
     * {@code create-fire-control-0.9.4.jar}). A {@code @Shadow} would abort this whole mixin if a
     * later 0.9.x renamed it, and a missing operator message is worth far less than the gate logic
     * in the same class, so the lookup is cached and a failure only loses the message.</p>
     */
    private static void firecontrolcompat$tellOperator(StabilizerControllerBlockEntity self, String key) {
        Method method = firecontrolcompat$operatorMethod();
        if (method == null || self == null || key == null) {
            return;
        }
        try {
            method.invoke(self, Component.translatable(key));
        } catch (Throwable ignored) {
            // the log line above already carries the reason
        }
    }

    /**
     * The pack's operator message, with a component this class built.
     *
     * <p>Used after a key press has moved the selection onto the round kind's own family: the pack
     * has already announced the family whose key was pressed ("普通防空导弹"), which is no longer
     * what is selected. This second message names the round that actually is, so the chat line and
     * the {@code ground-select:} log agree.</p>
     */
    private static void firecontrolcompat$tellOperatorComponent(StabilizerControllerBlockEntity self,
            Component message) {
        Method method = firecontrolcompat$operatorMethod();
        if (method == null || self == null || message == null) {
            return;
        }
        try {
            method.invoke(self, message);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    private static Method firecontrolcompat$operatorMethod() {
        if (!firecontrolcompat$operatorLooked) {
            firecontrolcompat$operatorLooked = true;
            try {
                Method method = StabilizerControllerBlockEntity.class
                        .getDeclaredMethod("notifyMissileOperator", Component.class);
                method.setAccessible(true);
                firecontrolcompat$operator = method;
            } catch (Throwable ignored) {
                firecontrolcompat$operator = null;
            }
        }
        return firecontrolcompat$operator;
    }

    private static Method firecontrolcompat$operator;

    private static boolean firecontrolcompat$operatorLooked;

    /** Writes the pack's private fire cursor; a failure only loses the rotation. */
    private static void firecontrolcompat$setCursor(StabilizerControllerBlockEntity self, int value) {
        Field field = firecontrolcompat$cursorField();
        if (field == null) {
            return;
        }
        try {
            field.setInt(self, value);
        } catch (Throwable ignored) {
            // the walk already picked its rail; only the next press's start moves
        }
    }

    /**
     * The weapon-key press: which <b>kind</b> of anti-air round the slots now mean.
     *
     * <p><b>One ladder for both anti-air keys.</b> 0.9.4 puts the pack's ordinary round in
     * {@code AIR_DEFENSE} and everything else in {@code HIGH_SPEED_AIR_DEFENSE}, which is a folder,
     * not a choice: 闈㈠寘瀛?s {@code portableantiairmissile_2}, this mod's infrared round, the 48N6 in
     * the vertical silo and the placed 娑呯摝 block all share one slot, so "鏅€氶槻绌? and "閲嶅瀷闃茬┖"
     * could only displace each other. A ground key press therefore walks
     * {@link AaWeaponSlots.Kind#antiAirLadder()} over every kind that is actually loaded: 鏅€?鈫?     * 楂橀€?鈫?绾㈠ 鈫?閲嶅瀷 鈫?娑呯摝, in one key. {@code ANTI_TANK} is never touched: it has its own slot,
     * its own round and its own firing path.</p>
     *
     * <p>The pack's own body runs after this and would set the <b>pressed</b> family (or, when that
     * family is not bound at all, leave the selection alone), so the family that matches the chosen
     * kind is written at {@link #firecontrolcompat$selectKindSettled(int, CallbackInfo)} - after the
     * body, once. That is what lets the heavy silo be selected by the ordinary key and the pack's
     * ordinary round by the high-speed key.</p>
     */
    @Inject(method = "selectBoundWeapon(I)V", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$selectKind(int action, CallbackInfo ci) {
        firecontrolcompat$pendingKind = null;
        firecontrolcompat$pendingFamily = null;
        try {
            FireControlWeaponInputs.GroundWeapon pressed =
                    FireControlWeaponInputs.groundWeapon(action);
            if (pressed == null || pressed == FireControlWeaponInputs.GroundWeapon.ANTI_TANK) {
                return;
            }
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            Level level = self.getLevel();
            if (level == null) {
                return;
            }
            List<BindingRef> launchers = self.getAaMissileLaunchers();
            if (launchers == null || launchers.isEmpty()) {
                return;
            }
            List<AaWeaponSlots.Kind> loadedKinds = AaWeaponSlots.loadedAntiAir(level, launchers);
            if (loadedKinds.isEmpty()) {
                FireControlCompat.LOGGER.info(
                        "[firecontrolcompat] ground-select: from={} to=none candidates=[] ammo=-"
                                + " reason=no-aa-round action={} family={}",
                        firecontrolcompat$kindName(AaWeaponSlots.selected(self)), action, pressed.name());
                return;
            }
            AaWeaponSlots.Kind current = AaWeaponSlots.selected(self);
            AaWeaponSlots.Kind next;
            String reason;
            if (current != null && loadedKinds.contains(current)) {
                next = AaWeaponSlots.next(pressed, current, loadedKinds);
                reason = "cycle";
            } else {
                next = AaWeaponSlots.first(pressed, loadedKinds);
                reason = "first";
            }
            if (next == null) {
                return;
            }
            AaWeaponSlots.select(self, next);
            firecontrolcompat$pendingKind = next;
            firecontrolcompat$pendingFamily = next.family();
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ground-select: from={} to={} candidates={} ammo={}"
                            + " action={} family={}->{} reason={} changed={}",
                    firecontrolcompat$kindName(current), next.id(),
                    firecontrolcompat$kindList(loadedKinds),
                    AaWeaponSlots.ammoSummary(level, launchers, loadedKinds),
                    action, pressed.name(), next.family().name(), reason, next != current);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] weapon cycle: from={} to={} family={} candidates={} reason={}",
                    firecontrolcompat$kindName(current), next.id(), next.family().name(),
                    firecontrolcompat$kindList(loadedKinds), reason);
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] weapon slot: kind={} type={} ammo={} selected={} name={} action={}",
                    next.id(), next.family().name(),
                    AaWeaponSlots.ammoOfKind(level, launchers, next), next != current,
                    next.label(), action);
        } catch (Throwable ignored) {
            // a failed selection only loses the sub-kind; the pack's family still applies
        }
    }

    /**
     * The pack's body has run: make the selected family agree with the chosen kind.
     *
     * <p>{@code selectBoundWeapon} only assigns {@code selectedGroundWeapon} when the <b>pressed</b>
     * family is bound, so the heavy silo could not be selected with the ordinary key even though it
     * is loaded. Writing the private field here - after the pack, never before - is what restores
     * "press the anti-air key, get the next anti-air round". A failed write only loses that
     * alignment; the kind selection itself is stored on the same block entity and still applies to
     * whichever anti-air family is in force.</p>
     */
    @Inject(method = "selectBoundWeapon(I)V", at = @At("RETURN"), require = 0)
    private void firecontrolcompat$selectKindSettled(int action, CallbackInfo ci) {
        AaWeaponSlots.Kind kind = firecontrolcompat$pendingKind;
        FireControlWeaponInputs.GroundWeapon family = firecontrolcompat$pendingFamily;
        firecontrolcompat$pendingKind = null;
        firecontrolcompat$pendingFamily = null;
        if (kind == null || family == null) {
            return;
        }
        try {
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            Field field = firecontrolcompat$groundWeaponField();
            FireControlWeaponInputs.GroundWeapon before = self.getSelectedGroundWeapon();
            if (field == null) {
                return;
            }
            if (before == family) {
                // Already aligned: the pack's own message named the right slot, nothing to say.
                return;
            }
            field.set(self, family);
            self.setChanged();
            FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ground-select: family written kind={} selected={}->{}",
                    kind.id(), before == null ? "none" : before.name(), family.name());
            // The pack announced the family whose key was pressed; that is no longer the selection,
            // so say which round the slot now means (the same wording its own setter uses).
            firecontrolcompat$tellOperatorComponent(self, Component.translatable(
                    "message.create_fire_control.weapon.selected",
                    Component.literal(kind.label())));
        } catch (Throwable ignored) {
            // the kind itself is already stored; only the family alignment is lost
        }
    }

    /** The kind chosen by the press being handled, between the HEAD and RETURN hooks. */
    private static AaWeaponSlots.Kind firecontrolcompat$pendingKind;

    /** The family that kind belongs to, written once the pack's body has run. */
    private static FireControlWeaponInputs.GroundWeapon firecontrolcompat$pendingFamily;

    private static String firecontrolcompat$kindName(AaWeaponSlots.Kind kind) {
        return kind == null ? "none" : kind.id();
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
        return firecontrolcompat$writeFamily(self, wanted) ? wanted : selected;
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
