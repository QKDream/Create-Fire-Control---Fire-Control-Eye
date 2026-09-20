package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.WeaponHudLinker;
import net.mcreator.myfirstmod.block.AntiairmissilelauncherblockBlock;
import net.mcreator.myfirstmod.block.AntiairmissilelauncherleftBlock;
import net.mcreator.myfirstmod.block.AntiairmissilelauncherrightBlock;
import net.mcreator.myfirstmod.block.AntitankemissilelauncherBlock;
import net.mcreator.myfirstmod.block.Antitankemissilelauncherh2Block;
import net.mcreator.myfirstmod.block.AntitankmissilelaunchererxingLEFTBlock;
import net.mcreator.myfirstmod.block.AntitankmissilelaunchererxingRIGHTBlock;
import net.mcreator.myfirstmod.block.AntitankmissilelaunchersanxingBlock;
import net.mcreator.myfirstmod.block.AntitankmissilelaunchersixingBlock;
import net.mcreator.myfirstmod.block.AGMmissilemixedRACKBlock;
import net.mcreator.myfirstmod.block.AgmMissile1rackBlock;
import net.mcreator.myfirstmod.block.AgmMissile2rackBlock;
import net.mcreator.myfirstmod.block.AgmMissile3rackBlock;
import net.mcreator.myfirstmod.block.AgmMissile4rackBlock;
import net.mcreator.myfirstmod.block.AntiradiationmissilerackBlock;
import net.mcreator.myfirstmod.block.CruisemissilerackBlock;
import net.mcreator.myfirstmod.block.EarthpenetratorbombrackBlock;
import net.mcreator.myfirstmod.block.FarMissile1rackBlock;
import net.mcreator.myfirstmod.block.FarMissile2rackBlock;
import net.mcreator.myfirstmod.block.GroundmissilelauncherheadBlock;
import net.mcreator.myfirstmod.block.HighexplosiveguidedtorpedorackBlock;
import net.mcreator.myfirstmod.block.Jdam2rackBlock;
import net.mcreator.myfirstmod.block.MediumAIMmissile2rackBlock;
import net.mcreator.myfirstmod.block.Missile2rackBlock;
import net.mcreator.myfirstmod.block.MissilerackBlock;
import net.mcreator.myfirstmod.block.NuclearcruisemissilerackBlock;
import net.mcreator.myfirstmod.block.OpticalguidedmissilerackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The TAOV HUD-linker binds through {@code Item#useOn}, but these MCreator
 * launchers and weapon racks consume every main-hand click in
 * {@code Block#useWithoutItem}
 * (which runs before the held item's useOn in 1.21.1), so the linker never
 * receives the click. While the player is holding the linker, let the click
 * pass through instead of running the reload logic.
 */
@Mixin({
        AntiairmissilelauncherblockBlock.class,
        AntiairmissilelauncherleftBlock.class,
        AntiairmissilelauncherrightBlock.class,
        AntitankemissilelauncherBlock.class,
        Antitankemissilelauncherh2Block.class,
        AntitankmissilelaunchererxingLEFTBlock.class,
        AntitankmissilelaunchererxingRIGHTBlock.class,
        AntitankmissilelaunchersanxingBlock.class,
        AntitankmissilelaunchersixingBlock.class,
        GroundmissilelauncherheadBlock.class,
        AgmMissile1rackBlock.class,
        AgmMissile2rackBlock.class,
        AgmMissile3rackBlock.class,
        AgmMissile4rackBlock.class,
        AGMmissilemixedRACKBlock.class,
        FarMissile1rackBlock.class,
        FarMissile2rackBlock.class,
        Missile2rackBlock.class,
        MediumAIMmissile2rackBlock.class,
        MissilerackBlock.class,
        OpticalguidedmissilerackBlock.class,
        AntiradiationmissilerackBlock.class,
        CruisemissilerackBlock.class,
        NuclearcruisemissilerackBlock.class,
        Jdam2rackBlock.class,
        EarthpenetratorbombrackBlock.class,
        HighexplosiveguidedtorpedorackBlock.class
})
public abstract class MianbaoLauncherLinkerUseMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void firecontrolcompat$allowLinkerBinding(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (WeaponHudLinker.isLinker(player.getMainHandItem())) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
