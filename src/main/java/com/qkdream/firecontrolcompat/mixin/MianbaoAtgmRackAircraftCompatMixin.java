package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.qkdream.firecontrolcompat.AtgmAirRack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes 面包学's air-release anti-tank rack a weapon the fire control computer can bind and select.
 *
 * <h2>Why these two blocks and not the launchers beside them</h2>
 *
 * <p>面包学 2.6.5 registers six blocks named 「反坦克导弹发射器」 that 火控学 0.9.4 knows as ground
 * anti-tank launchers ({@code MianbaoMissileCompat.LAUNCHERS}, decompile lines 31-38) and two more
 * with the same name that it does not know at all - {@code atgm_missilemixedlauncher} and
 * {@code atgm_missilemixedlauncher_2}, both built on {@code ATGMmissilemixedlauncherBlock}. That block
 * fires through the <b>air-to-ground release chain</b>, not through an anti-tank work procedure, so
 * the two are the air racks of the family. See {@link AtgmAirRack} for the whole argument.</p>
 *
 * <h2>What each hook buys</h2>
 *
 * <ul>
 *   <li>{@code isRack} - the block can be bound to a computer as an aircraft missile rack at all
 *       ({@code StabilizerBindingHandler#detectBindingSlot} :253 asks exactly this).</li>
 *   <li>{@code isSameRackVariant} - the two registered ids are the same rail, so a binding made
 *       against one of them survives the block being placed as the other.</li>
 *   <li>{@code weaponAt} - the rack answers the aircraft air-to-ground slot <b>only while it holds
 *       this mod's round</b>. That is what puts it in the selectable weapon list
 *       ({@code boundAircraftWeapons} :498-516) and what makes the pack's own fire loop walk to it;
 *       a rack holding a native round keeps answering nothing, exactly as before, so no other
 *       weapon's behaviour changes.</li>
 * </ul>
 */
@Mixin(MianbaoAircraftCompat.class)
public abstract class MianbaoAtgmRackAircraftCompatMixin {

    /** An air-release anti-tank rack is a rack like any other. */
    @Inject(method = "isRack", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$atgmRackIsRack(
            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && AtgmAirRack.isAirAtgmRack(state)) {
            cir.setReturnValue(true);
        }
    }

    /** ...and its two registered ids are two states of the same rail. */
    @Inject(method = "isSameRackVariant", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$atgmRackStaysARack(
            ResourceLocation first, ResourceLocation second, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && AtgmAirRack.isAirAtgmRackId(first)
                && AtgmAirRack.isAirAtgmRackId(second)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * The rack answers the air-to-ground slot while it carries our round.
     *
     * <p>Answered at RETURN and only when the pack itself answered nothing: a block the pack already
     * files under a weapon type keeps that type, and the two ids here are not in the pack's table at
     * all, so the only case this fills is the one it was written for.</p>
     */
    @Inject(method = "weaponAt", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$atgmRackWeapon(
            Level level, BindingRef ref, CallbackInfoReturnable<MianbaoAircraftCompat.WeaponType> cir) {
        try {
            if (cir.getReturnValue() != null || level == null || ref == null) {
                return;
            }
            if (!AtgmAirRack.isAirAtgmRack(level, ref.pos())
                    || !AtgmAirRack.carriesIrAtgm(level, ref.pos())) {
                return;
            }
            cir.setReturnValue(MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT);
        } catch (Throwable ignored) {
            // a rack that cannot be read answers nothing, which is what it answered before
        }
    }
}
