package com.qkdream.firecontrolcompat.mixin;

import java.util.List;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.qkdream.firecontrolcompat.BombRackCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets fire control hold 面包学's bomb racks and their rounds.
 *
 * <p>A bomb rack answers as the air-to-ground type, so it binds, counts and is
 * selected exactly like an air-to-ground rail, and the release runs the rack's
 * own launch - see {@link BombRackCompat#fire} - so the drop, the fall and the
 * burst are all 面包学's.</p>
 *
 * <p>The rounds themselves answer as air-to-ground ordnance, which is what puts
 * them on the radar and on the automatic air-defense display: a computer holding
 * a bomb rack can be told to release at a place, and a ship's automatic defense
 * sees the falling bombs coming in.</p>
 */
@Mixin(MianbaoAircraftCompat.class)
public abstract class MianbaoBombRackAircraftCompatMixin {

    /** A block that is a bomb rack, loaded or empty, is a rack like any other. */
    @Inject(method = "isRack", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$bombRackIsRack(
            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && BombRackCompat.isBombRack(state)) {
            cir.setReturnValue(true);
        }
    }

    /** ...and one that stays the same rack while the rounds on it come and go. */
    @Inject(method = "isSameRackVariant", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$bombRackStaysARack(
            ResourceLocation first, ResourceLocation second, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && BombRackCompat.isBombRackId(first)
                && BombRackCompat.isBombRackId(second)) {
            cir.setReturnValue(true);
        }
    }

    /** A rack carrying a round answers as the air-to-ground rack, or as the laser one. */
    @Inject(method = "weaponAt", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$bombRackWeapon(
            Level level, BindingRef ref, CallbackInfoReturnable<MianbaoAircraftCompat.WeaponType> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        if (BombRackCompat.isLoadedBombRack(level, ref)) {
            cir.setReturnValue(MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT);
        } else if (BombRackCompat.isLoadedLaserRack(level, ref)) {
            // The 4-tube laser rack rides the laser line of the weapon selector,
            // which is where a crew looks for a laser round.
            cir.setReturnValue(MianbaoAircraftCompat.WeaponType.LASER_GUIDED);
        }
    }

    /** The release itself is the rack's own launch, not the air-to-ground missile. */
    @Inject(method = "fireOne", at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$releaseBombRack(
            Level level, BindingRef ref, MianbaoAircraftCompat.WeaponType expected,
            CallbackInfoReturnable<Boolean> cir) {
        if (ref == null
                || (!BombRackCompat.isLoadedBombRack(level, ref)
                && !BombRackCompat.isLoadedLaserRack(level, ref))) {
            return;
        }
        cir.setReturnValue(BombRackCompat.fire(level, ref));
    }

    /**
     * A bomb - and a cruise missile - is air-to-ground ordnance.
     *
     * <p>This is the pack's own entry point for guiding an air-to-ground round at the
     * computer's target: the bombs are answered here already, which is why a glide bomb
     * locks and steers, while a cruise round was not and was therefore flown as an ordinary
     * entity - it fell, slowed, and hit the ground. Answering for the cruise rounds as well
     * is what puts them on the same guidance the pack gives its own air-to-ground missiles,
     * which is the mechanism asked for.</p>
     */
    @Inject(method = "isAirToGroundMissile", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$bombIsGroundOrdnance(
            Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        if (BombRackCompat.isBombEntity(entity)) {
            // Bombs only. A cruise round is flown by this mod's own control so that it keeps
            // its speed and ballistic character, so it is deliberately not offered to the
            // pack for guidance.
            cir.setReturnValue(true);
        }
    }
}
