package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.underwater.VisualTorpedoAim;
import net.mcreator.myfirstmod.procedures.High_explosive_visual_guided_torpedo_tanshe_trailProcedure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Teaches 面包学's visually guided round to see Sable structures.
 *
 * <p>The round is steered at the point its gunner's sight line lands on, and
 * that walk is a plain block clip: a hull is not the world's blocks, so a round
 * aimed straight at a ship was really aimed at whatever lay beyond her, and it
 * swam past or buried itself in the sea bed. The walk is answered through the
 * mod's own hull-aware line instead, and the gunner whose eyes it belongs to is
 * taken on the way, so the round is not laid on the deck its own gunner stands
 * on.</p>
 *
 * <p>Optional: without it the round keeps the mod's own walk, hull-blind.</p>
 */
@Mixin(High_explosive_visual_guided_torpedo_tanshe_trailProcedure.class)
public abstract class MianbaoVisualTorpedoGuidanceMixin {

    @Redirect(method = "execute",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private static Vec3 firecontrolcompat$rememberGunner(Entity owner) {
        VisualTorpedoAim.remember(owner);
        return owner.getLookAngle();
    }

    @Redirect(method = "execute",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"),
            require = 0)
    private static BlockHitResult firecontrolcompat$aimAtHulls(Level level, ClipContext context) {
        return VisualTorpedoAim.aim(level, context);
    }
}
