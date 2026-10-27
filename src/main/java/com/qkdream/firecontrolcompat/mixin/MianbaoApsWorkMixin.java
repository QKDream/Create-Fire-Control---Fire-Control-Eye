package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.EmpEffect;
import net.mcreator.myfirstmod.procedures.APS1sandworkProcedure;
import net.mcreator.myfirstmod.procedures.APS1workProcedure;
import net.mcreator.myfirstmod.procedures.APS2sandworkProcedure;
import net.mcreator.myfirstmod.procedures.APS2workProcedure;
import net.mcreator.myfirstmod.procedures.APS3sandworkProcedure;
import net.mcreator.myfirstmod.procedures.APS3workProcedure;
import net.mcreator.myfirstmod.procedures.ApsfiresmokeProcedure;
import net.mcreator.myfirstmod.procedures.Apsworks1roundsProcedure;
import net.mcreator.myfirstmod.procedures.Apsworks1roundsshaseProcedure;
import net.mcreator.myfirstmod.procedures.Apsworks2roundsshaseProcedure;
import net.mcreator.myfirstmod.procedures.Apsworks_2roundsProcedure;
import net.mcreator.myfirstmod.procedures.PortableantiairsystemworkProcedure;
import net.mcreator.myfirstmod.procedures.Portableantimissileradarwork2Procedure;
import net.mcreator.myfirstmod.procedures.PortableantimissileradarworkProcedure;
import net.mcreator.myfirstmod.procedures.PortableantimissilesystemgoProcedure;
import net.mcreator.myfirstmod.procedures.Portableantimissilesystemwork2Procedure;
import net.mcreator.myfirstmod.procedures.PortableantimissilesystemworkProcedure;
import net.mcreator.myfirstmod.procedures.PortableapsworkProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps1workProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps1workshaseProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps2workProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps2workshaseProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps3workProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps3workshaseProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps4workProcedure;
import net.mcreator.myfirstmod.procedures.Secondaps4workshaseProcedure;
import net.mcreator.myfirstmod.procedures.ThirdAPS1roundsonworkProcedure;
import net.mcreator.myfirstmod.procedures.ThirdAPS1roundsonshaseworkProcedure;
import net.mcreator.myfirstmod.procedures.ThirdAPS2roundsonworkProcedure;
import net.mcreator.myfirstmod.procedures.ThirdAPS2roundsonshaseworkProcedure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EMP blackout for 面包学's active protection.
 *
 * <p>Every launcher runs the same shape of block tick: one generated "work"
 * procedure scans the air around the mount for incoming ordnance and swats
 * whatever it finds, and the portable mounts feed their interception missiles
 * from the same kind of procedure. They all take the launcher's level and block
 * coordinates, so head injectors on this family blind the whole network at
 * once - the basic, sand and three- and four-round launchers, the larger second
 * and third generation mounts, the portable launcher, the portable anti-air and
 * anti-missile systems with their radar, and the smoke burst they all fire
 * through - and cancelling the call leaves the mounts loaded, armed and simply
 * blind until the burst fades.</p>
 */
@Mixin({
        APS1workProcedure.class,
        APS1sandworkProcedure.class,
        APS2workProcedure.class,
        APS2sandworkProcedure.class,
        APS3workProcedure.class,
        APS3sandworkProcedure.class,
        Apsworks1roundsProcedure.class,
        Apsworks1roundsshaseProcedure.class,
        Apsworks2roundsshaseProcedure.class,
        Apsworks_2roundsProcedure.class,
        Secondaps1workProcedure.class,
        Secondaps1workshaseProcedure.class,
        Secondaps2workProcedure.class,
        Secondaps2workshaseProcedure.class,
        Secondaps3workProcedure.class,
        Secondaps3workshaseProcedure.class,
        Secondaps4workProcedure.class,
        Secondaps4workshaseProcedure.class,
        ThirdAPS1roundsonworkProcedure.class,
        ThirdAPS1roundsonshaseworkProcedure.class,
        ThirdAPS2roundsonworkProcedure.class,
        ThirdAPS2roundsonshaseworkProcedure.class,
        PortableapsworkProcedure.class,
        PortableantiairsystemworkProcedure.class,
        PortableantimissilesystemworkProcedure.class,
        Portableantimissilesystemwork2Procedure.class,
        PortableantimissilesystemgoProcedure.class,
        PortableantimissileradarworkProcedure.class,
        Portableantimissileradarwork2Procedure.class,
        ApsfiresmokeProcedure.class
})
public abstract class MianbaoApsWorkMixin {

    @Inject(
            method = "execute(Lnet/minecraft/world/level/LevelAccessor;DDD)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void firecontrolcompat$empBlackout(
            LevelAccessor world,
            double x,
            double y,
            double z,
            CallbackInfo ci
    ) {
        if (EmpEffect.isDown(world, BlockPos.containing(x, y, z))) {
            ci.cancel();
        }
    }

    @Inject(
            method = "execute(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void firecontrolcompat$empBlackoutFire(
            LevelAccessor world,
            double x,
            double y,
            double z,
            Entity target,
            CallbackInfo ci
    ) {
        if (EmpEffect.isDown(world, BlockPos.containing(x, y, z))) {
            ci.cancel();
        }
    }

    @Inject(
            method = "execute(Lnet/minecraft/world/level/LevelAccessor;DDDLnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void firecontrolcompat$empBlackoutRadar(
            LevelAccessor world,
            double x,
            double y,
            double z,
            BlockState state,
            CallbackInfo ci
    ) {
        if (EmpEffect.isDown(world, BlockPos.containing(x, y, z))) {
            ci.cancel();
        }
    }
}
