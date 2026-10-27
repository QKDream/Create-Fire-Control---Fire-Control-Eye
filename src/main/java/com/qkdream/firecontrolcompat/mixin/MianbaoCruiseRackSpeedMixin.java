package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.BombRackCompat;
import com.qkdream.firecontrolcompat.CruiseMissileRackCompat;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the cruise missile at the speed its own flight runs at.
 *
 * <p>It is also where the round a rail has just thrown is picked up, so a round
 * that appears a tick late is still marked as the computer's and still held to
 * her own speed, and where the once-a-second line about the rounds in the air is
 * written.</p>
 *
 * <p>The aircraft guidance flies everything it adopts up to a fixed aircraft
 * speed, which is right for a missile that lives five seconds and wrong for one
 * that cruises: the computer would make it three times as fast as it is. The
 * pass that writes that speed runs at the end of the server tick, so the length
 * of the velocity is corrected straight after it, and only for rounds this mod
 * launched from a cruise rail - every other round keeps the speed it was
 * given.</p>
 */
@Mixin(com.hooya.stabilizedturret.content.controller.MianbaoAircraftGuidanceHandler.class)
public abstract class MianbaoCruiseRackSpeedMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private static void firecontrolcompat$holdCruiseSpeed(ServerTickEvent.Post event, CallbackInfo ci) {
        CruiseMissileRackCompat.tick();
        BombRackCompat.tick();
    }
}
