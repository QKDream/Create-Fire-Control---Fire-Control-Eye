package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.AtgmAirRack;
import com.qkdream.firecontrolcompat.FireControlCompat;
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
 * Releases the infrared anti-tank round off an aircraft through fire control's own weapon trigger.
 *
 * <h2>The trigger is not a new key</h2>
 *
 * <p>Everything happens inside {@code fireNextAircraftMissile} - the method the cockpit trigger, the
 * wireless fire pulse and the rocket-salvo branch all end up in - and inside the pack's own rack walk:
 * the same {@code aircraftMissileFireCursor}, the same order, the same "one press moves the cursor on
 * after the rail it fired" rule. No input action is added and no habit changes.</p>
 *
 * <h2>Where the press belongs</h2>
 *
 * <p>The walk stops at the first bound rail that answers the selected weapon type, because that is
 * the rail the pack would fire. If that rail is <b>not</b> one of this mod's air-release anti-tank
 * racks ({@code atgm_missilemixedlauncher/_2}, see {@link AtgmAirRack}), the press is the pack's and
 * this hook returns without cancelling - a native air-to-ground rack beside ours keeps firing its own
 * round, untouched. If it <b>is</b> ours, the press is answered here even when the release refuses
 * (no designation), so walking on to the next rail can never turn one press into a different shot.</p>
 *
 * <h2>What a release does</h2>
 *
 * <p>{@link AtgmAirRack#release} calls the rack's own 面包学 procedure - the same one its redstone
 * pulse calls - which plays the launch sound and particles, places the round at the pack's muzzle,
 * copies the guidance data and takes one round out of slot zero; the round that comes out is
 * {@code firecontrolcompat:ir_atgm_tanshe} with the top-attack profile and the designation the
 * release resolved. Success is graded the way 火控学 grades every launch: the rack ends up holding
 * one round less.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class AircraftAtgmRackFireMixin {

    @Shadow
    private int aircraftMissileFireCursor;

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

    @Inject(method = "fireNextAircraftMissile", at = @At("HEAD"), cancellable = true, require = 0)
    private void firecontrolcompat$releaseAtgm(CallbackInfo ci) {
        try {
            StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
            Level level = self.getLevel();
            if (!(level instanceof ServerLevel) || !this.hasInputConfigured(6)) {
                return;
            }
            // Only the air-to-ground slot: that is the slot the rail answers (see
            // MianbaoAtgmRackAircraftCompatMixin), and taking a far or medium shot over for a rail
            // that happens to hold this round was the mistake the anti-radiation release corrected.
            if (self.getSelectedAircraftWeapon() != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                return;
            }
            this.removeInvalidBindings();
            List<BindingRef> racks = self.getAircraftMissileRacks();
            int size = racks == null ? 0 : racks.size();
            if (size == 0) {
                return;
            }
            int start = Math.floorMod(this.aircraftMissileFireCursor, size);
            for (int offset = 0; offset < size; offset++) {
                int index = (start + offset) % size;
                BindingRef rack = racks.get(index);
                if (rack == null) {
                    continue;
                }
                // Racks of another kind are not candidates for this press at all: the pack's own walk
                // skips them the same way, and stopping on one would fire the wrong rail.
                if (MianbaoAircraftCompat.weaponAt(level, rack)
                        != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT) {
                    continue;
                }
                if (!AtgmAirRack.isAirAtgmRack(level, rack.pos())) {
                    FireControlCompat.LOGGER.info(
                            "ir-atgm air: press left to the pack reason=native-rack-ahead rack={} index={} of={}",
                            rack.pos(), index, size);
                    return;
                }
                this.aircraftMissileFireCursor = (index + 1) % size;
                // The rack may stand in a Sable sub-level: the release has to run in the level its
                // block and its round slot actually live in, exactly like the pack's own fireOne.
                Level rackLevel = level;
                net.minecraft.world.level.block.entity.BlockEntity blockEntity = rack.resolve(level);
                if (blockEntity != null && blockEntity.getLevel() != null) {
                    rackLevel = blockEntity.getLevel();
                }
                boolean fired = AtgmAirRack.release(rackLevel, rack.pos());
                self.setChanged();
                FireControlCompat.LOGGER.info(
                        "ir-atgm air: rack={} index={} of={} released={} route=air",
                        rack.pos(), index, size, fired);
                if (!fired) {
                    this.notifyMissileOperator(Component.literal("红外反坦克导弹发射失败"));
                }
                ci.cancel();
                return;
            }
            FireControlCompat.LOGGER.info(
                    "ir-atgm air: press left to the pack reason=no-atgm-rack racks={} cursor={}",
                    size, start);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("ir-atgm air: fire hook skipped: {}", failure.toString());
        }
    }
}
