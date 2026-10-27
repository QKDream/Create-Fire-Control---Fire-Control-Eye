package com.qkdream.firecontrolcompat.compat.maid;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ExtraMaidBrainManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.datafixers.util.Pair;
import java.util.List;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;

/**
 * Touhou Little Maid bridge: adds the fire control operator task, the ammunition
 * loading task and the behaviors behind them.
 *
 * <p>The class is only loaded by the maid mod's own extension scan, so this mod
 * still starts when Touhou Little Maid is not installed: nothing here is
 * referenced before the maid mod looks for {@code ILittleMaid} implementations,
 * and the mod dependency is declared optional.</p>
 */
@LittleMaidExtension
public final class MaidFireControlExtension implements ILittleMaid {

    @Override
    public void addMaidTask(TaskManager manager) {
        // The scope that calls her onto a target is hooked from here: this is the
        // one entry point the maid mod itself calls, so nothing of this mod's maid
        // code is loaded before the maid mod is ready for it.
        MaidSpyglassLink.register();
        manager.add(new TaskFireControlOperator());
        manager.add(new TaskManualFireControlOperator());
        manager.add(new TaskAmmoLoader());
    }

    @Override
    public void addExtraMaidBrain(ExtraMaidBrainManager manager) {
        // Only the core list is used on purpose. The maid brain keeps Activity.CORE
        // active next to whatever the schedule picked, so a core behavior also runs
        // while she sits on the seat. Adding it to the ride list as well would run
        // the same work twice per tick, which makes the aim pass fight itself.
        manager.addExtraMaidBrain(new IExtraMaidBrain() {

            @Override
            public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> getCoreBehaviors() {
                return List.of(
                        Pair.of(100, new MaidFireControlOperatorBehavior()),
                        Pair.of(90, new MaidAmmoLoaderBehavior()));
            }
        });
    }
}
