package com.qkdream.firecontrolcompat.compat.yuushya;

import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;

/**
 * Block entity of the modelling block while it plays a nethersteel big cannon
 * barrel.
 *
 * <p>It is the modelling block entity with the cannon interface bolted on, so
 * the cannon block can be queried the way Create Big Cannons queries its own
 * cannon block entities. That is what makes the barrel click onto the breech
 * next to it and what lets it survive being assembled into a cannon.</p>
 *
 * <p>The cannon behaviour itself still lives in a host next to this block
 * entity: the modelling block entity is not a Create block entity, and the
 * behaviour needs one to belong to.</p>
 *
 * <p>Being a plain subclass and not a mixin is what makes this work at all:
 * the fire control mod adds its own cannon method to the modelling block
 * entity, and the two would fight over the same compiler generated bridge
 * method.</p>
 */
public class YuushyaBigCannonBlockEntity extends ShowBlockEntity implements IBigCannonBlockEntity {

    private YuushyaBigCannonBehaviorHost firecontrolcompat$cannonHost;

    public YuushyaBigCannonBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public BigCannonBehavior cannonBehavior() {
        if (this.firecontrolcompat$cannonHost == null) {
            this.firecontrolcompat$cannonHost = new YuushyaBigCannonBehaviorHost(this);
        }
        return this.firecontrolcompat$cannonHost.behavior();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, Provider registries) {
        super.saveAdditional(tag, registries);
        if (!YuushyaBigCannonCompat.isBigCannonBarrel(this.getBlockState())) {
            return;
        }
        CompoundTag cannonTag = new CompoundTag();
        this.cannonBehavior().write(cannonTag, registries, false);
        tag.put(YuushyaBigCannonCompat.CANNON_BEHAVIOR_TAG, cannonTag);
    }

    @Override
    public void loadAdditional(CompoundTag tag, Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(YuushyaBigCannonCompat.CANNON_BEHAVIOR_TAG)) {
            this.cannonBehavior().read(tag.getCompound(YuushyaBigCannonCompat.CANNON_BEHAVIOR_TAG), registries, false);
        }
    }
}