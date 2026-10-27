package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.BeamMissileCompat;
import com.qkdream.firecontrolcompat.IrSeekerSession;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>NOT REGISTERED (2026-10-27): the anti-tank round is not an infrared homing missile.</b>
 *
 * <p>This hook used to give the anti-tank launcher family the same two step release the
 * air-defence family has: the first trigger press opened an infrared head, the head locked a heat
 * source, and the next press released the round onto that contact. The report was exactly right -
 * "the round is now an infrared lock missile, not the infrared anti-tank missile it used to be" -
 * because that release path bypasses what the round is for: a designated <b>point</b> (a pod paint,
 * an optical lock, a ground-target snapshot, a radar contact) that it climbs over and dives onto.</p>
 *
 * <p>So this class is no longer listed in {@code firecontrolcompat.mixins.json} and never applies.
 * The air-defence family keeps its two step release (that one is {@code ControllerFireAaMixin} plus
 * {@code BeamMissileCompat#tryFireAntiAir}, and the {@code ir-seeker: stage=…} lines belong to it);
 * the anti-tank family fires on one press through {@code BeamMissileCompat#fireIrAtgm}, which reads
 * only the fire control designation and never the seeker hand-over. Restoring the old behaviour is
 * putting this one line back into the mixin config.</p>
 *
 * <p>The body below is kept verbatim, so restoration is a one line change and the historical
 * behaviour is on the record.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class ControllerMissileSeekerMixin {

    @Shadow
    private int missileFireCursor;

    @Shadow
    private boolean hasInputConfigured(int action) {
        throw new AbstractMethodError();
    }

    @Shadow
    private void removeInvalidBindings() {
        throw new AbstractMethodError();
    }

    @Shadow
    private void notifyMissileOperator(Component message) {
        throw new AbstractMethodError();
    }

    @Inject(method = "fireNextMissile", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$releaseIrSeeker(CallbackInfo ci) {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!this.hasInputConfigured(3) || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        this.removeInvalidBindings();
        List<BindingRef> launchers = self.getMissileLaunchers();
        int size = launchers.size();
        if (size == 0) {
            return;
        }
        int start = Math.floorMod(this.missileFireCursor, size);
        for (int offset = 0; offset < size; offset++) {
            BindingRef launcher = launchers.get((start + offset) % size);
            if (MianbaoMissileCompat.getAmmoCount(level, launcher) <= 0) {
                continue;
            }
            if (!BeamMissileCompat.hasIrMissileLauncherAmmo(level, launcher)) {
                // Rounds that are not ours ride the launcher's own volley.
                return;
            }
            IrSeekerSession.Lock lock = IrSeekerSession.press(serverLevel, launcher.pos(), self.getBlockPos(),
                    BeamMissileCompat.missileLauncherMuzzle(level, launcher.pos()),
                    BeamMissileCompat.facing(level, launcher.pos()));
            if (lock == null) {
                this.notifyMissileOperator(Component.literal(IrSeekerSession.searchHint(serverLevel)));
                ci.cancel();
                return;
            }
            IrSeekerSession.handOver(serverLevel, launcher.pos(), lock);
            return;
        }
    }
}
