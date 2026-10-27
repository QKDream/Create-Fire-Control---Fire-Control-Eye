package com.qkdream.firecontrolcompat.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;

/**
 * Brain task that runs the ammunition loading work. It is registered as a core
 * behaviour of every maid and answers only while she is switched to the loading
 * task, which is what lets her walk between the crate and the launcher.
 */
final class MaidAmmoLoaderBehavior extends Behavior<EntityMaid> {

    MaidAmmoLoaderBehavior() {
        super(ImmutableMap.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return MaidAmmoLoader.isLoader(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return MaidAmmoLoader.isLoader(maid);
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        MaidAmmoLoader.tick(level, maid, gameTime);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        MaidAmmoLoader.release(level, maid);
    }
}
