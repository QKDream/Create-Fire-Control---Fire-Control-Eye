package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets freshly spawned air-defense missiles find the controller that fired
 * them when the radar lock is an entity/Shaolib contact rather than a Sable
 * sublevel, so {@code MianbaoAirDefenseGuidanceHandler} still attaches
 * guidance to the shot.
 */
@Mixin(ControllerTracker.class)
public abstract class ControllerTrackerAaMixin {

    @Shadow
    private static Set<StabilizerControllerBlockEntity> LOADED;

    /**
     * @reason Accept entity/Shaolib radar locks in addition to Sable sublevel locks.
     * @author firecontrolcompat
     */
    @Overwrite
    public static StabilizerControllerBlockEntity findForMianbaoAaMissile(Entity missile) {
        if (!MianbaoAirDefenseCompat.isMissile(missile)) {
            return null;
        } else {
            StabilizerControllerBlockEntity best = null;
            double bestDistanceSqr = 25.0;
            synchronized (LOADED) {
                for (StabilizerControllerBlockEntity controller : Set.copyOf(LOADED)) {
                    Level controllerLevel = controller.getLevel();
                    if (!controller.isRemoved()
                            && controllerLevel != null
                            && controllerLevel.getServer() == missile.level().getServer()
                            && controllerLevel.dimension().equals(missile.level().dimension())
                            && controller.isRadarActive()
                            && firecontrolcompat$hasRadarLock(controller)) {
                        for (BindingRef launcher : controller.getAaMissileLaunchers()) {
                            double distance = MianbaoAirDefenseCompat.getLauncherSpawnDistanceSqr(controllerLevel, launcher, missile);
                            if (distance <= bestDistanceSqr) {
                                bestDistanceSqr = distance;
                                best = controller;
                            }
                        }
                    }
                }

                return best;
            }
        }
    }

    private static boolean firecontrolcompat$hasRadarLock(StabilizerControllerBlockEntity controller) {
        if (controller.getRadarLockedSubLevel() != null) {
            return true;
        }
        return controller.getRadarLockedSubLevelId() != null
                && controller.getRadarContact(controller.getRadarLockedSubLevelId()) != null;
    }
}
