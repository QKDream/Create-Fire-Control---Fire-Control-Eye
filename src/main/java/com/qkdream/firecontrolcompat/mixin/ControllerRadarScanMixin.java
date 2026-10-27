package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.CompatScanner;
import com.qkdream.firecontrolcompat.EmpEffect;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IffRadar;
import com.qkdream.firecontrolcompat.RadarEmitters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds hostile ordnance entities (mianbaos / vestalihy / CBCMS) and Shaolib
 * Tau/Hellfire instances to the stabilizer controller radar scan, so they get
 * the same HUD contacts, lock boxes and weapon guidance as Sable structures.
 *
 * <p>The finished contact list is also passed through the radar gate: own side
 * platforms (sharing an IFF channel with this computer) never show up as
 * targets, and while the speed gate is armed anything slower than its threshold
 * is dropped as well. Both screens run on the appended list too, so compat
 * ordnance obeys the same rules.</p>
 *
 * <p>A computer caught by an EMP burst skips the sweep entirely for the length
 * of the blackout: it keeps no contacts to lock or guide on, and it is not
 * registered as an emitter either, so the radar stops painting hostile warning
 * receivers as well.</p>
 *
 * <h2>The anchor</h2>
 *
 * <p>0.9.4 builds the sweep in {@code updateRadarScan}, and the last statement of
 * that method's sweep branch is {@code this.radarContacts = List.copyOf(contacts)}.
 * That field is what every consumer reads - the radar HUD, the lock cycle, the
 * aircraft target picker - so this hook runs at the tail of that method and
 * writes the field back. It is the only server-side writer of the field and it
 * is reached once per tick from {@code serverTick}, which makes it the real
 * entry point for radar contacts.</p>
 *
 * <h2>Why the report line never appeared</h2>
 *
 * <p>The field log said "no {@code radar scan tail} line at all", which reads as
 * the injection never running. It was the log gate, not the hook: the stamp was
 * seeded with {@link Long#MIN_VALUE} and the gate was {@code now - stamp >= 100L}.
 * That subtraction overflows - {@code now + 2^63} wraps to a negative number for
 * every positive game time - so the comparison was false forever and the line
 * could not be printed however often the hook ran. The identical seed-and-subtract
 * pair sat in {@link CompatScanner}'s math-contact report and silenced that one as
 * well. Both now treat the seed as "never logged" explicitly instead of relying on
 * the subtraction.</p>
 *
 * <p>The append is committed to the field before the IFF and stealth gates run,
 * and every step reports its own failure. Previously one try/catch wrapped the
 * whole body and the field was written only at the very end, so a single throw
 * inside the gate discarded the append without a word.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class ControllerRadarScanMixin {

    @Shadow
    private List<StabilizerControllerBlockEntity.RadarContact> radarContacts;

    /** Tick of the last "radar inactive" report, or MIN_VALUE before the first one. */
    private static long firecontrolcompat$lastIdleLog = Long.MIN_VALUE;

    /** Nanotime the sweep started at, stamped by {@link #firecontrolcompat$scanStart}. */
    private long firecontrolcompat$scanStartedNanos;

    /**
     * Stamps the start of the pack's own sweep, so the tail can say what one radar scan costs.
     *
     * <p>The maid anti-air stutter had three candidates in it - this scan, the mathematical
     * interception sweep and the maid's own per-tick work - and no way to tell them apart from the
     * outside. The stamp plus the tail line turn "the radar is heavy" into a number.</p>
     */
    @Inject(method = "updateRadarScan", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$scanStart(CallbackInfo ci) {
        this.firecontrolcompat$scanStartedNanos = System.nanoTime();
    }

    @Inject(method = "updateRadarScan", at = @At("TAIL"), require = 0)
    private void firecontrolcompat$appendContacts(CallbackInfo ci) {
        try {
            StabilizerControllerBlockEntity controller = (StabilizerControllerBlockEntity) (Object) this;
            int nativeCount = this.radarContacts == null ? 0 : this.radarContacts.size();
            Level level = controller.getLevel();
            if (level == null || level.isClientSide) {
                FireControlCompat.LOGGER.info(
                        "radar scan hook: native={} appended=0 total={} reason=no-server-level",
                        nativeCount, nativeCount);
                return;
            }
            if (!controller.isRadarActive()) {
                // Not reported on every scan - a computer with its radar switched off is the
                // normal case. Once, and then every ten seconds, is enough to tell "the hook
                // never ran" apart from "the sweep itself was skipped".
                long now = level.getGameTime();
                if (firecontrolcompat$lastIdleLog == Long.MIN_VALUE
                        || now - firecontrolcompat$lastIdleLog >= 200L) {
                    firecontrolcompat$lastIdleLog = now;
                    FireControlCompat.LOGGER.info(
                            "radar scan hook: native={} appended=0 total={} reason=radar-inactive",
                            nativeCount, nativeCount);
                }
                return;
            }
            if (EmpEffect.isDown(level, controller.getBlockPos())) {
                this.radarContacts = List.of();
                FireControlCompat.LOGGER.info(
                        "radar scan hook: native={} appended=0 total=0 reason=emp-blackout", nativeCount);
                return;
            }
            List<StabilizerControllerBlockEntity.RadarContact> contacts =
                    new ArrayList<>(this.radarContacts == null ? List.of() : this.radarContacts);
            // Each append is on its own: a pack class that moved, or a Shaolib bridge that is not
            // installed, must not take the other source down with it.
            try {
                CompatScanner.appendRadarContacts(controller, contacts);
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info("radar scan hook: entity append failed: {}",
                        failure.toString());
            }
            try {
                CompatScanner.appendOverheadContacts(controller, contacts);
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info("radar scan hook: overhead append failed: {}",
                        failure.toString());
            }
            int appended = contacts.size() - nativeCount;
            // Committed before the gates below run, so a throw inside one of them can no longer
            // throw the appended contacts away along with it.
            this.radarContacts = List.copyOf(contacts);
            firecontrolcompat$reportScan(controller, level, nativeCount, appended, contacts.size());
            try {
                CompatScanner.stripStealthCruiseContactsRadar(level, contacts, controller.getRadarOrigin());
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info("radar scan hook: stealth strip failed: {}",
                        failure.toString());
            }
            try {
                RadarEmitters.remember(controller);
                RadarEmitters.rememberScan(controller, level.getGameTime());
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info("radar scan hook: emitter record failed: {}",
                        failure.toString());
            }
            try {
                Set<String> own = IffRadar.ownChannels(level, controller);
                if (!own.isEmpty()) {
                    contacts.removeIf(contact -> IffRadar.hiddenFromRadar(level, contact, own));
                }
            } catch (Throwable failure) {
                FireControlCompat.LOGGER.info("radar scan hook: iff gate failed: {}", failure.toString());
            }
            this.radarContacts = List.copyOf(contacts);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("radar scan hook failed: {}", failure.toString());
        }
    }

    /**
     * One scan line, and one cost line, instead of one line per scan per computer.
     *
     * <p>This used to print unconditionally on every sweep. A computer with its radar running
     * sweeps ten to twenty times a second, so a single manned mount filled the log with thousands
     * of identical lines - and the log write is not free on the server thread. The line is now
     * printed when the result changes, and otherwise once a second: still enough to prove the hook
     * runs, and it cannot drown the report it was added for.</p>
     */
    private void firecontrolcompat$reportScan(StabilizerControllerBlockEntity controller, Level level,
            int nativeCount, int appended, int total) {
        long now;
        try {
            now = level.getGameTime();
        } catch (Throwable ignored) {
            now = 0L;
        }
        double millis = this.firecontrolcompat$scanStartedNanos == 0L ? -1.0D
                : (System.nanoTime() - this.firecontrolcompat$scanStartedNanos) / 1.0E6D;
        boolean changed = nativeCount != firecontrolcompat$lastNative
                || appended != firecontrolcompat$lastAppended
                || total != firecontrolcompat$lastTotal;
        boolean due = firecontrolcompat$lastScanLog == 0L || now - firecontrolcompat$lastScanLog >= 20L;
        boolean slow = millis >= 4.0D && (firecontrolcompat$lastSlowLog == 0L
                || now - firecontrolcompat$lastSlowLog >= 20L);
        if (!changed && !due && !slow) {
            return;
        }
        firecontrolcompat$lastNative = nativeCount;
        firecontrolcompat$lastAppended = appended;
        firecontrolcompat$lastTotal = total;
        firecontrolcompat$lastScanLog = now;
        if (millis >= 4.0D) {
            firecontrolcompat$lastSlowLog = now;
        }
        FireControlCompat.LOGGER.info(
                "radar scan hook: native={} appended={} total={} origin={} range={} cost_ms={}",
                nativeCount, appended, total, controller.getRadarOrigin(), controller.getRadarRange(),
                millis < 0.0D ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", millis));
    }

    private static int firecontrolcompat$lastNative = -1;
    private static int firecontrolcompat$lastAppended = -1;
    private static int firecontrolcompat$lastTotal = -1;
    private static long firecontrolcompat$lastScanLog;
    private static long firecontrolcompat$lastSlowLog;
}
