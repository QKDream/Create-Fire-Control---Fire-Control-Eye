package com.qkdream.firecontrolcompat.sonar;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The wireless half of the sonar pick: the receiver a fire control computer
 * answers to on the frequency pair the operator bound to it.
 *
 * <p>A redstone link pulse reaching the pair is the same order the key gives,
 * so a boat can pick a mark off her sonar picture from a switch on the bridge
 * instead of from the seat: the rising edge is what counts, so a signal left on
 * picks one mark and no more.</p>
 *
 * <p>It is built exactly the way the computer's own per-action receivers are -
 * the same interface, the same rising edge - so it joins the network the same
 * way and is bound and unbound with it.</p>
 */
public final class SonarSelectWireless implements IRedstoneLinkable {

    private final StabilizerControllerBlockEntity owner;
    private final Runnable pulse;
    private Frequency first = Frequency.EMPTY;
    private Frequency second = Frequency.EMPTY;
    private int receivedStrength;

    public SonarSelectWireless(StabilizerControllerBlockEntity owner, Runnable pulse) {
        this.owner = owner;
        this.pulse = pulse;
    }

    /** Takes one half of the pair the operator bound, so the pulse on it is heard. */
    public void setFrequency(boolean first, ItemStack stack) {
        ItemStack one = stack == null ? ItemStack.EMPTY : stack.copy();
        one.setCount(one.isEmpty() ? 0 : 1);
        if (first) {
            this.first = Frequency.of(one);
        } else {
            this.second = Frequency.of(one);
        }
        this.receivedStrength = 0;
    }

    /** Whether the pair is bound at all; a receiver listening to nothing is left off the network. */
    public boolean hasFrequency() {
        return !this.first.getStack().isEmpty() || !this.second.getStack().isEmpty();
    }

    @Override
    public int getTransmittedStrength() {
        return 0;
    }

    @Override
    public void setReceivedStrength(int strength) {
        if (!this.hasFrequency()) {
            this.receivedStrength = 0;
            return;
        }
        boolean rising = this.receivedStrength == 0 && strength > 0;
        this.receivedStrength = strength;
        if (rising) {
            this.pulse.run();
        }
    }

    @Override
    public boolean isListening() {
        return this.hasFrequency();
    }

    @Override
    public boolean isAlive() {
        Level level = this.owner.getLevel();
        return level != null
                && !this.owner.isRemoved()
                && level.isLoaded(this.owner.getBlockPos())
                && level.getBlockEntity(this.owner.getBlockPos()) == this.owner;
    }

    @Override
    public Couple<Frequency> getNetworkKey() {
        return Couple.create(this.first, this.second);
    }

    @Override
    public BlockPos getLocation() {
        return this.owner.getBlockPos();
    }
}
