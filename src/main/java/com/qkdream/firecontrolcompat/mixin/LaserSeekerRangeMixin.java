package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.LaserSeekerRange;
import net.mcreator.myfirstmod.procedures.Antitankemissile2trailProcedure;
import net.mcreator.myfirstmod.procedures.Antitankmissile1trailProcedure;
import net.mcreator.myfirstmod.procedures.FarrocketlaserguidedtrailProcedure;
import net.mcreator.myfirstmod.procedures.Jdam1trailProcedure;
import net.mcreator.myfirstmod.procedures.Jdam2trailProcedure;
import net.mcreator.myfirstmod.procedures.Missile3trailProcedure;
import net.mcreator.myfirstmod.procedures.Mortarlaserguidedtanshe_trailProcedure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Widens the search box 面包学's laser rounds look for a spot in.
 *
 * <p>Each of her laser flight procedures asks for the nearest spot inside a box
 * of 600 blocks, which is what decides how far out a laser round can be steered
 * at all: past that it flies blind no matter how well it is painted. The box is
 * widened here, and only when it is exactly that 600-block box, so the other
 * boxes the same procedures build - the ones used for damage - are left alone.</p>
 */
@Mixin({
        Missile3trailProcedure.class,
        Jdam1trailProcedure.class,
        Jdam2trailProcedure.class,
        Antitankmissile1trailProcedure.class,
        Antitankemissile2trailProcedure.class,
        FarrocketlaserguidedtrailProcedure.class,
        Mortarlaserguidedtanshe_trailProcedure.class
})
public abstract class LaserSeekerRangeMixin {

    @Redirect(
            method = "execute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;ofSize(Lnet/minecraft/world/phys/Vec3;DDD)Lnet/minecraft/world/phys/AABB;"),
            require = 0)
    private static AABB firecontrolcompat$widenSpotSearch(Vec3 centre, double sizeX, double sizeY, double sizeZ) {
        return LaserSeekerRange.widen(centre, sizeX, sizeY, sizeZ);
    }
}
