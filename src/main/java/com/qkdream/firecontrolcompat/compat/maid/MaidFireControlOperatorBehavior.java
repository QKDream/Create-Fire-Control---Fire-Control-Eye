package com.qkdream.firecontrolcompat.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;

/**
 * Brain task that runs the fire control operator work. It is registered as a
 * core behavior of every maid and answers only while the maid is switched to
 * the fire control operator task, which is also how it keeps working while she
 * is sitting on the operator seat.
 */
final class MaidFireControlOperatorBehavior extends Behavior<EntityMaid> {

    MaidFireControlOperatorBehavior() {
        super(ImmutableMap.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return MaidFireControlOperator.isOperator(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return MaidFireControlOperator.isOperator(maid);
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        MaidFireControlOperator.tick(level, maid, gameTime);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        MaidFireControlOperator.release(level, maid);
    }
}
