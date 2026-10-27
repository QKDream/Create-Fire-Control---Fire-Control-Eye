package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.AtgmAirRack;
import net.mcreator.myfirstmod.procedures.AgmmissilemixedrackgoProcedure;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases this mod's infrared anti-tank round off 面包学's air-release anti-tank rack.
 *
 * <h2>Which rack, and why the pack's own release</h2>
 *
 * <p>{@code atgm_missilemixedlauncher} and {@code atgm_missilemixedlauncher_2} carry the same display
 * name as the anti-tank launchers 火控学 fires (「反坦克导弹发射器」) but are built on
 * {@code ATGMmissilemixedlauncherBlock}, whose redstone trigger calls this procedure - the
 * air-to-ground release chain - rather than an anti-tank work procedure. They are the racks this mod
 * treats as aircraft weapons, see {@link AtgmAirRack}.</p>
 *
 * <p>The release itself stays the pack's: the procedure's body plays the launch sound, throws the
 * spark and poof particles, places the round at its own muzzle (+3.6 along the facing, +0.25 up),
 * copies the launcher's guidance data onto it and takes one round out of container slot zero. That
 * body is gated on the slot holding a native {@code antitankmissile_1/_2}, so this hook primes the
 * slot for the length of the one call and puts this mod's round back on the way out, with the native
 * round the pack spawned replaced by {@code ir_atgm_tanshe}. Nothing else is re-implemented.</p>
 *
 * <p>A rack holding a native round is untouched: {@link AtgmAirRack#begin} claims the call only when
 * slot zero carries <em>our</em> round, and both hooks then do nothing at all. A press with no
 * designation is answered by cancelling, so the round stays on the rack and no native round can ever
 * come out of a rack that is holding ours.</p>
 */
@Mixin(AgmmissilemixedrackgoProcedure.class)
public abstract class AtgmAirRackFireMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$claimAirRelease(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        try {
            if (AtgmAirRack.begin(world, x, y, z)) {
                ci.cancel();
            }
        } catch (Throwable failure) {
            // A hook that cannot decide must leave the press to the pack.
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ir-atgm air: release hook skipped: {}", failure.toString());
        }
    }

    @Inject(method = "execute", at = @At("RETURN"), require = 0)
    private static void firecontrolcompat$replaceReleasedRound(
            LevelAccessor world, double x, double y, double z, CallbackInfo ci) {
        try {
            AtgmAirRack.finish(world, x, y, z);
        } catch (Throwable failure) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] ir-atgm air: round swap failed: {}", failure.toString());
        }
    }
}
