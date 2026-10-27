package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.FlakTerrainDamage;
import com.verr1.shaolib.api.projectile.ProjectileServerContext;
import com.verr1.shaolib.api.raycast.ShaolibBlockHitResult;
import com.verr1.shaolib.munitions.config.properties.CbcLikeMunitionProperties;
import com.verr1.shaolib.munitions.projectile.impact.BlockImpactSupport;
import com.verr1.shaolib.munitions.projectile.impact.CbcLikePenetrationModel;
import com.verr1.shaolib.munitions.projectile.impact.MunitionImpactOutcome;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The same block wear for the pellets a TAOV flak round throws.
 *
 * <p>TAOV's autocannon fires through Shaolib, whose shrapnel pellets decide
 * what a block does by momentum: a pellet either shatters the block it hits or
 * vanishes against it, and a wall of any weight simply stops every pellet.
 * Each pellet now leaves its own scratch behind, so a burst fired into cover
 * works it loose instead of doing nothing at all.</p>
 */
@Mixin(CbcLikePenetrationModel.class)
public abstract class ShaolibPelletTerrainMixin {

    /** Shaolib projectile the fragmentation of a flak round is built from. */
    private static final ResourceLocation SHRAPNEL_PELLET =
            ResourceLocation.fromNamespaceAndPath("shaolib_munitions", "shrapnel_pellet");

    /** Wear one pellet adds to the block it hits. */
    private static final float PELLET_WEAR = 3.0F;

    @Inject(method = "resolve", at = @At("HEAD"))
    private static void firecontrolcompat$pelletWearsBlocks(
            ProjectileServerContext<?> context,
            ShaolibBlockHitResult hit,
            CbcLikeMunitionProperties properties,
            CallbackInfoReturnable<MunitionImpactOutcome> cir) {
        try {
            if (context == null || hit == null || properties == null) {
                return;
            }
            if (!SHRAPNEL_PELLET.equals(context.projectile().type().id())) {
                return;
            }
            if (hit.isBodyHit()) {
                // Structures live in their own physics world; their blocks are
                // left to the pack's own body handling.
                return;
            }
            Optional<BlockState> state = BlockImpactSupport.liveBlockState(context, hit);
            if (state.isEmpty() || state.get().isAir()) {
                return;
            }
            BlockPos pos = BlockImpactSupport.impactedBlockPos(hit);
            Vec3 velocity = context.projectile().velocity();
            double speed = velocity == null ? 0.0 : velocity.length();
            double mass = properties.ballistics().durabilityMass();
            FlakTerrainDamage.apply(context.level(), pos, state.get(), FlakTerrainDamage.burstFragmentDamage(mass, speed, PELLET_WEAR));
        } catch (Throwable ignored) {
            // A pellet that cannot be resolved keeps the pack's own behaviour.
        }
    }
}