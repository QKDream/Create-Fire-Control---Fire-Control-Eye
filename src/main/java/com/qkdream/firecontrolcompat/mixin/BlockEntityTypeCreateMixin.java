package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaBigCannonBlockEntity;
import com.qkdream.firecontrolcompat.compat.yuushya.YuushyaBigCannonCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands out the cannon block entity of the modelling barrel.
 *
 * <p>Every block entity that comes back from a saved world, from a cannon
 * contraption or from any other copy is built here, so this is where the barrel
 * gets its block entity with the cannon interface on it. Placing the block is
 * the only path that does not come through here, and that one is covered by the
 * block itself.</p>
 */
@Mixin(BlockEntityType.class)
public abstract class BlockEntityTypeCreateMixin {

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$modellingBarrelBlockEntity(BlockPos pos, BlockState state,
            CallbackInfoReturnable<BlockEntity> cir) {
        if (YuushyaBigCannonCompat.isBigCannonBarrel(state)) {
            cir.setReturnValue(new YuushyaBigCannonBlockEntity(pos, state));
        }
    }
}