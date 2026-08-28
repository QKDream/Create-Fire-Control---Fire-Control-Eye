package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Allows firing the air-defense missile against radar locks that point at
 * entity / Shaolib contacts instead of Sable sublevels.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class ControllerFireAaMixin {

    @Shadow
    private int aaMissileFireCursor;

    @Shadow
    private boolean hasInputConfigured(int action) {
        throw new AbstractMethodError();
    }

    @Shadow
    private void removeInvalidBindings() {
        throw new AbstractMethodError();
    }

    @Shadow
    private void notifyMissileOperator(Component message) {
        throw new AbstractMethodError();
    }

    /**
     * @reason Accept entity/Shaolib radar locks in addition to Sable sublevel locks.
     * @author firecontrolcompat
     */
    @Overwrite
    private void fireNextAaMissile() {
        StabilizerControllerBlockEntity self = (StabilizerControllerBlockEntity) (Object) this;
        if (this.hasInputConfigured(4) && self.getLevel() != null) {
            this.removeInvalidBindings();
            List<BindingRef> launchers = self.getAaMissileLaunchers();
            if (launchers.isEmpty()) {
                this.notifyMissileOperator(Component.literal("防空导弹发射器：未绑定"));
            } else if (!self.isRadarActive()) {
                this.notifyMissileOperator(Component.literal("防空导弹：雷达未工作"));
            } else if (!this.firecontrolcompat$hasRadarLock(self)) {
                this.notifyMissileOperator(Component.literal("防空导弹：未锁定雷达目标"));
            } else {
                int size = launchers.size();
                int start = Math.floorMod(this.aaMissileFireCursor, size);

                for (int offset = 0; offset < size; offset++) {
                    int index = (start + offset) % size;
                    BindingRef launcher = launchers.get(index);
                    if (MianbaoAirDefenseCompat.getAmmoCount(self.getLevel(), launcher) > 0) {
                        this.aaMissileFireCursor = (index + 1) % size;
                        boolean fired = MianbaoAirDefenseCompat.fireOne(self.getLevel(), launcher);
                        self.setChanged();
                        if (!fired) {
                            this.notifyMissileOperator(Component.literal("防空导弹发射失败"));
                        }
                        return;
                    }
                }

                this.notifyMissileOperator(Component.literal("防空导弹已用尽"));
            }
        }
    }

    @Unique
    private boolean firecontrolcompat$hasRadarLock(StabilizerControllerBlockEntity self) {
        if (self.getRadarLockedSubLevel() != null) {
            return true;
        }
        return self.getRadarLockedSubLevelId() != null
                && self.getRadarContact(self.getRadarLockedSubLevelId()) != null;
    }
}
