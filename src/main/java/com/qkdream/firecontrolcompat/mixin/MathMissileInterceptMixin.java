package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.missile.MissileManager;
import com.qkdream.firecontrolcompat.missile.MathIntercept;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The <em>virtual record</em> branch of the interception: rounds the older
 * {@code MissileManager} flies as plain records.
 *
 * <p>This is kept for the case where records exist at all, but it is not the branch that matters on
 * 0.9.4: {@code MissileManager.virtualize} is never called by any class in the pack, so that table
 * is always empty. The rounds the pack actually flies in mathematical mode live in
 * {@code MathematicalMissileManager} as entity objects that never join the level, and those are
 * covered by {@code MathEntityInterceptMixin}.</p>
 */
@Mixin(MissileManager.class)
public abstract class MathMissileInterceptMixin {

    @Unique
    private ServerLevel firecontrolcompat$level;

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void firecontrolcompat$interceptMathMissiles(CallbackInfo ci) {
        try {
            MissileManager manager = (MissileManager) (Object) this;
            ServerLevel level = com.qkdream.firecontrolcompat.missile.MathIntercept.levelOf(manager);
            if (level == null) {
                return;
            }
            MathIntercept.interceptNearby(manager, level);
        } catch (Throwable ignored) {
            // Never let the interception take the missile tick down.
        }
    }
}
