package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BeamMissileCompat;
import net.mcreator.myfirstmod.procedures.AntitankmissilelauncherloadProcedure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Loads this mod's infrared anti-tank round into 面包学's anti-tank launchers.
 *
 * <h2>The only load entry point all four launchers share</h2>
 *
 * <p>{@code AntitankemissilelauncherBlock#useWithoutItem} (:115), the H2 variant, the two
 * {@code erxing} halves, {@code sanxing} (:114) and {@code sixing} all call
 * <b>this</b> procedure - the per-rig {@code ...reloadProcedure} classes have no caller at all in
 * 2.6.5 - so one hook here covers the whole family.</p>
 *
 * <h2>What the pack's own branch does (vineflower, 2.6.5)</h2>
 *
 * <p>Capacity is {@code 4} for the base/H2/mixed blocks and {@code 2} for the erxing/sanxing/sixing
 * rigs (:47-57). The round has to be held in the main hand
 * ({@code getMainHandItem().getItem() == ANTITANKMISSILE_1 | _2}, :60 and :139) and the launcher's
 * <b>container slot zero</b> must be empty or already hold that same round; the load then writes
 * {@code new ItemStack(round, current + 1)} through
 * {@code LegacyInventoryAccess.getHandler(be)} (a {@code new InvWrapper(container)}, :96-100) and
 * takes one item out of the player's inventory, except in creative. There is no
 * {@code missile1ammo}/{@code missile2ammo} counter any more and no block replacement - the loaded
 * state <em>is</em> that slot, which is also what the work procedures shrink and what 火控学 counts
 * ({@code MianbaoLauncherAmmo.antiTank}, :24-32).</p>
 *
 * <p>Our round therefore goes into that same slot, and only the item test is ours to answer - which
 * is what {@link com.qkdream.firecontrolcompat.BeamMissileCompat#tryLoadAtgm} does before this hook
 * cancels the pack's procedure. Nothing is invented beside the pack's storage, so the launcher
 * shows a loaded round through the pack's own "已装填/LOAD :N发" line, the fire control counts it,
 * and a spent round leaves the slot the way a native one does.</p>
 */
@Mixin(AntitankmissilelauncherloadProcedure.class)
public abstract class AtgmLauncherLoadMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private static void firecontrolcompat$loadBeamMissile(
            LevelAccessor world, double x, double y, double z, Entity entity, CallbackInfo ci) {
        if (BeamMissileCompat.tryLoadAtgm(world, x, y, z, entity, 4.0, 4.0)) {
            ci.cancel();
        }
    }
}
