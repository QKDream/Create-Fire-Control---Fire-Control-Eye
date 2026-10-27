package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.MaidFireControlAccess;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the fire control computer's radar lock and its aim pass to the maid
 * crew, so a maid acting as gunner can select the contact she picked and slew
 * the bound bearings exactly the way a seated player does.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public interface MaidFireControlAccessMixin extends MaidFireControlAccess {

    @Override
    @Accessor("radarLockedSubLevelId")
    void firecontrolcompat$setRadarLockedSubLevelId(UUID id);

    @Override
    @Invoker("applyAimDirection")
    void firecontrolcompat$applyAimDirection();
}
