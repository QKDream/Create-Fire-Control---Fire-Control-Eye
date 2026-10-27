package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.VestalihyBridge;
import com.vestalihy.entity.TubusEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes vestalihy's tube launchers known to the fire control computer.
 *
 * <p>The tube stamps the bridge face, so the rest of this mod can find it, ask
 * whether it is loaded and fire it without naming a vestalihy class, and the
 * click with vestalihy's own optics connector hands the tube to the fire control
 * computer that connector was used on first.</p>
 */
@Mixin(TubusEntity.class)
public abstract class VestalihyLauncherMixin implements VestalihyBridge.Launcher {

    @Shadow
    public abstract boolean isEmpty();

    @Shadow
    public abstract boolean launch();

    @Shadow
    public abstract Direction getFacing();

    @Override
    public boolean fccIsEmpty() {
        return this.isEmpty();
    }

    @Override
    public boolean fccLaunch() {
        return this.launch();
    }

    @Override
    public Vec3 fccDirection() {
        Direction facing = this.getFacing();
        return facing == null ? null : Vec3.atLowerCornerOf(facing.getNormal());
    }

    @Inject(method = "interact", at = @At("HEAD"))
    private void firecontrolcompat$bindToFireControl(Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir) {
        VestalihyBridge.tryBindWithConnector(player, (TubusEntity) (Object) this);
    }
}
