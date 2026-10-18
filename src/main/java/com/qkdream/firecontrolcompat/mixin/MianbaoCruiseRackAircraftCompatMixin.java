package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat;
import com.qkdream.firecontrolcompat.CruiseMissileRackCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets fire control take over 面包学's cruise missile on the aircraft weapon rack.
 *
 * <p>The computer knows aircraft weapons only as its own set of types, and a
 * cruise missile on a rail is not one of them: the rail is answered as the
 * air-to-ground type, so it binds, counts and is selected exactly like an
 * air-to-ground rail.</p>
 *
 * <p>The round itself is left unanswered on purpose. Fire control flies every
 * missile it can name, and an aircraft round is flown as the computer's own
 * air-to-ground one: no gravity from the first tick, a held aircraft speed and
 * the computer's own run-in and warhead. A cruise missile keeps its own route
 * instead - the boost, the climb, the cruise and the long run - so the rail is
 * fired through 面包学's own launch and the round is left to 面包学's own
 * flight, with the computer's target written on the rail before it leaves - see
 * {@link CruiseMissileRackCompat#fire}. Its speed, its trail, its lifetime and
 * its own aim stay the mod's, and the computer still sees and tracks it, since
 * its radar files cruise missiles by their own entry in the mod's table.</p>
 *
 * <p>Optional: without it the rail simply cannot be bound, as before.</p>
 */
@Mixin(MianbaoAircraftCompat.class)
public abstract class MianbaoCruiseRackAircraftCompatMixin {

    /** A rail carrying a cruise missile is a rail like any other. */
    @Inject(method = "isRack", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$cruiseRackIsRack(
            net.minecraft.world.level.block.state.BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && CruiseMissileRackCompat.isCruiseRack(state)) {
            cir.setReturnValue(true);
        }
    }

    /** ...and one that stays the same rail while the round on it comes and goes. */
    @Inject(method = "isSameRackVariant", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$cruiseRackStaysARack(
            ResourceLocation first, ResourceLocation second, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
                && CruiseMissileRackCompat.isRackId(first)
                && CruiseMissileRackCompat.isRackId(second)) {
            cir.setReturnValue(true);
        }
    }

    /** The cruise rail answers as the air-to-ground rack, which is how it is flown. */
    @Inject(method = "weaponAt", at = @At("RETURN"), cancellable = true, require = 0)
    private static void firecontrolcompat$cruiseRackWeapon(
            Level level, BindingRef ref, CallbackInfoReturnable<MianbaoAircraftCompat.WeaponType> cir) {
        if (cir.getReturnValue() == null && CruiseMissileRackCompat.isCruiseRack(level, ref)) {
            cir.setReturnValue(MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT);
        }
    }

    /**
     * The shot is left to the pack.
     *
     * <p>This used to call this mod's own launch for a cruise rail, which also meant this
     * mod aimed the round itself. The pack fires a rail of its own make perfectly well and
     * aims it the way it aims its air-to-ground missiles - the ones that lock and fly
     * correctly - so the rail is simply let through now and only kept alive afterwards
     * (chunks held, speed kept).</p>
     */
    /**
     * The shot itself is this mod's own launch, so the round is picked up and kept alive.
     *
     * <p>This interception was removed for a while, and that is what made a cruise missile
     * vanish the instant it left its rail: the pack then launched it on its own, this mod never
     * saw the round, and 面包学's own rule - discard as soon as the round counts as stuck in the
     * ground - ended it before anyone could see it. With the launch here again, the round is
     * claimed as it appears and lifted clear if it came out touching the ground.</p>
     */
    @Inject(method = "fireOne", at = @At("HEAD"), cancellable = true, require = 0)
    private static void firecontrolcompat$fireCruiseMissile(
            Level level, BindingRef ref, MianbaoAircraftCompat.WeaponType expected,
            CallbackInfoReturnable<Boolean> cir) {
        if (ref == null || !CruiseMissileRackCompat.isCruiseRack(level, ref)) {
            return;
        }
        cir.setReturnValue(CruiseMissileRackCompat.fire(level, ref.pos()));
    }
}
