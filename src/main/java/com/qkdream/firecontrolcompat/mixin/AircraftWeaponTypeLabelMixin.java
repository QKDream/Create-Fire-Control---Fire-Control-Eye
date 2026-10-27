package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.qkdream.firecontrolcompat.AtgmAirRack;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.network.SeekerHudPayload;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Names the close-range aircraft slot after the round that is really on the rail.
 *
 * <h2>Why the enum and not one screen</h2>
 *
 * <p>{@code MianbaoAircraftCompat.WeaponType.displayName()} ({@code ()Lnet/minecraft/network/chat/Component;})
 * is the single source of every place the aircraft slot is printed; javap on 0.9.4 shows four callers
 * and only one of them was covered before:</p>
 *
 * <ul>
 *   <li>{@code RadarHudRenderer.aircraftWeaponHudLines} :375 - the flight HUD line, redirected
 *       separately by {@link AircraftWeaponHudNameMixin};</li>
 *   <li>{@code ClientBombSight} :116 - the bomb sight line, <b>not covered before</b>;</li>
 *   <li>{@code FireControlWeaponInputs.label(int)} :45 - the input page's weapon rows, which is
 *       what {@link WeaponSlotLabelMixin} patches and what a crew reads as "the weapon slot";</li>
 *   <li>{@code StabilizerControllerBlockEntity.cycleAircraftWeapon} :538 and :531 - the message
 *       printed when the slot is cycled, redirected separately by
 *       {@link AircraftWeaponNameDisplayMixin}.</li>
 * </ul>
 *
 * <p>Hooking the enum method covers all of them at once, so a path nobody redirected can no longer
 * print the pack's stock wording while different rounds are on the rail. Only the two slots that
 * this mod actually changes the round of are touched - {@code CLOSE} (the anti-radiation and
 * aircraft infrared rounds) and {@code AIR_TO_GROUND_DIRECT} (the cruise / glide rails) - and every
 * other slot keeps the pack's own component untouched.</p>
 *
 * <p>The rename is driven by {@code SeekerHudPayload}, the state the seated player's client is
 * already sent every tick, so there is one source of truth. The window is deliberately much longer
 * than the frame window: a crew that opens the input page is not seated any more and stops
 * receiving the payload, and the name must not snap back to "近程空对空导弹" while the
 * anti-radiation round is still on the rail.</p>
 */
@Mixin(targets = "com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat$WeaponType")
public abstract class AircraftWeaponTypeLabelMixin {

    /** The last name this hook produced, so the diagnostic prints once per change, not per frame. */
    private static String firecontrolcompat$lastLogged = "";

    @Inject(method = "displayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void firecontrolcompat$slotName(CallbackInfoReturnable<Component> cir) {
        try {
            Object self = (Object) this;
            boolean close = self == MianbaoAircraftCompat.WeaponType.CLOSE;
            boolean ground = self == MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT;
            if (!close && !ground) {
                return;
            }
            // The slot is named the way the pack names it in code (the enum constant), because that
            // is what a report has to quote: "ground" was ambiguous with the ground weapon slots.
            String slot = ((Enum<?>) self).name().toLowerCase(java.util.Locale.ROOT);
            // Everything the branch needs is read before the line is printed, so the line says
            // what the *stock* component was, not what this hook replaced it with.
            String stock = cir.getReturnValue() == null ? "-" : cir.getReturnValue().getString();
            if (close) {
                // RETIRED(ir-arm-rounds): the anti-radiation and airborne infrared branches stood
                // here. Both rounds are 面包学's own from its next version on, so the close-range
                // slot always keeps the pack's wording.
                firecontrolcompat$report(slot, stock, "native", stock);
                return;
            }
            // The infrared anti-tank round first: the rack itself is the authority (it is the block
            // the trigger releases from), and it is refreshed once a second by
            // AtgmAirRack.scanLoadedControllers() from every loaded computer, so a crew standing at
            // the computer - the reader of the input page, the "weapon selected" message and the
            // bomb sight - gets the name without being seated.
            String name = AtgmAirRack.recentLabel();
            String source = "ir-atgm";
            if (name == null || name.isBlank()) {
                // Second source: the state the seated player's client is already sent every tick.
                // The window is deliberately much longer than the frame window: a crew that opens the
                // input page is not seated any more and stops receiving the payload, and the name
                // must not snap back while the round is still on the rail.
                name = SeekerHudPayload.isNameFresh(System.currentTimeMillis())
                        ? SeekerHudPayload.groundSlotName() : null;
                source = "payload";
            }
            if (name != null && !name.isBlank()) {
                firecontrolcompat$report(slot, name, source, stock);
                cir.setReturnValue(Component.literal(name));
                return;
            }
            firecontrolcompat$report(slot, stock, "native", stock);
        } catch (Throwable ignored) {
            // Keep the pack's own wording.
        }
    }

    /**
     * One line per change of (slot, source, name).
     *
     * <p>It used to print only when something was renamed, which made the "no lines at all" report
     * impossible to read: the hook could have been running on every frame and finding nothing, or
     * not running at all, and both looked identical. Now the {@code native} case prints too.</p>
     */
    private static void firecontrolcompat$report(String slot, String name, String source, String stock) {
        String line = slot + "/" + source + "/" + name;
        if (line.equals(firecontrolcompat$lastLogged)) {
            return;
        }
        firecontrolcompat$lastLogged = line;
        FireControlCompat.LOGGER.info("[firecontrolcompat] weapon label: slot={} name={} src={} stock={}",
                slot, name, source, stock);
    }
}
