package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.FlakTerrainDamage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.autocannon.flak.FlakBurst;
import rbasamoyai.createbigcannons.munitions.big_cannon.shrapnel.ShrapnelBurst;
import rbasamoyai.ritchiesprojectilelib.projectile_burst.ProjectileBurst.SubProjectile;

/**
 * Lets the fragments of an anti air burst bite into the blocks they hit.
 *
 * <p>Shrapnel bursts already carry their share of block damage, but a fragment
 * of an autocannon flak round only ever scratched a block: its share was
 * capped by the block's own toughness and the hardness penalty ate most of
 * what was left, so a burst into a wall left the wall standing. Every fragment
 * now also adds a fixed share of wear, which is what turns a burst into
 * chipped, crumbling cover.</p>
 *
 * <p>The hook sits on the shrapnel burst, which is the class that declares the
 * fragment callback, and answers for the flak round only: a big cannon
 * shrapnel shell keeps the pack's own behaviour. The mixin does not extend the
 * class it is attached to - a mixin may only extend a supertype of its target,
 * never the target itself - so the level and the fragment mass are reached
 * through the entity the burst really is.</p>
 */
@Mixin(ShrapnelBurst.class)
public abstract class FlakBurstTerrainMixin {

    /** Wear one flak fragment adds to the block it hits. */
    private static final float FLAK_FRAGMENT_WEAR = 6.0F;

    /** Fragment mass of an anti air round, in the pack's own units. */
    private static final double FLAK_FRAGMENT_MASS = 1.0;

    @Inject(method = "onSubProjectileHitBlock", at = @At("HEAD"))
    private void firecontrolcompat$flakFragmentWearsBlocks(BlockHitResult result, SubProjectile subProjectile, CallbackInfo ci) {
        if (!FlakBurst.class.isInstance(this)) {
            return;
        }
        Level level = ((Entity) (Object) this).level();
        if (level == null || level.isClientSide) {
            return;
        }
        BlockPos pos = result.getBlockPos();
        BlockState state = level.getBlockState(pos);
        double[] velocity = subProjectile.velocity();
        double speed = new Vec3(velocity[0], velocity[1], velocity[2]).length();
        FlakTerrainDamage.apply(level, pos, state,
                FlakTerrainDamage.burstFragmentDamage(FLAK_FRAGMENT_MASS, speed, FLAK_FRAGMENT_WEAR));
    }
}