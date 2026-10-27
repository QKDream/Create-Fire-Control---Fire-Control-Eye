package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.ControllerSeatOrder;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns the single operator seat of a fire control computer into a chain of
 * command: seats bound after the first one are relief posts, and the order
 * they were bound in decides who is in charge.
 *
 * <p>The pack answers "who is the gunner" by asking whether the vehicle a
 * player rides matches the one seat it stored, and it stops at the first match
 * it finds. Every seat of the chain is now matched, and the gunner is looked
 * up in binding order instead: the first seat that has a man on it wins. A
 * player on a relief post therefore cannot take the mount away from the crew
 * sitting in front of him, and a post that is left or whose occupant dies
 * frees the mount for the next seat down the chain - all of it recomputed on
 * the spot, so nothing has to be cleaned up when someone leaves.</p>
 *
 * <p>Crew members that are not players (the fire control maids) are honoured
 * the same way: a post they hold blocks the seats behind it, and the pack's
 * own gunner lookup answers "nobody" while the front post is theirs.</p>
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class ControllerSeatOrderMixin implements ControllerSeatOrder {

    /** NBT tag the extra seats are stored under; the first seat keeps its own tag. */
    @Unique
    private static final String firecontrolcompat$SEAT_TAG = "FireControlCompatSeats";

    /** A chain longer than this is not worth keeping around. */
    @Unique
    private static final int firecontrolcompat$MAX_SEATS = 8;

    @Shadow
    @Final
    private Map<BindingSlot, BindingRef> bindings;

    /**
     * Level of the controller. Every block entity keeps it on BlockEntity
     * itself, so it is read through the getter instead of a shadow.
     */
    @Unique
    private Level firecontrolcompat$level() {
        return ((StabilizerControllerBlockEntity) (Object) this).getLevel();
    }

    @Unique
    private List<BindingRef> firecontrolcompat$seatList;

    @Unique
    private List<BindingRef> firecontrolcompat$seats() {
        if (this.firecontrolcompat$seatList == null) {
            this.firecontrolcompat$seatList = new ArrayList<>();
        }
        return this.firecontrolcompat$seatList;
    }

    @Override
    public List<BindingRef> firecontrolcompat$orderedSeats() {
        List<BindingRef> seats = this.firecontrolcompat$seats();
        BindingRef primary = this.bindings.get(BindingSlot.SEAT);
        if (primary != null && (seats.isEmpty() || !seats.get(0).equals(primary))) {
            seats.remove(primary);
            seats.add(0, primary);
        } else if (primary == null && !seats.isEmpty()) {
            // The seat slot was cleared somewhere else: the next seat of the
            // chain moves up rather than the whole chain being dropped.
            this.bindings.put(BindingSlot.SEAT, seats.get(0));
        }
        return seats;
    }

    @Override
    public int firecontrolcompat$seatIndex(Entity rider) {
        if (rider == null || this.firecontrolcompat$level() == null) {
            return -1;
        }
        Entity vehicle = rider.getVehicle();
        if (vehicle == null) {
            return -1;
        }
        List<BindingRef> seats = this.firecontrolcompat$orderedSeats();
        for (int index = 0; index < seats.size(); index++) {
            if (seats.get(index).matchesEntityPosition(this.firecontrolcompat$level(), vehicle)) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public List<Entity> firecontrolcompat$seatOccupants(int index) {
        List<BindingRef> seats = this.firecontrolcompat$orderedSeats();
        if (index < 0 || index >= seats.size()) {
            return List.of();
        }
        return this.firecontrolcompat$occupantsOf(seats.get(index));
    }

    @Override
    public boolean firecontrolcompat$seatManned(int index) {
        return !this.firecontrolcompat$seatOccupants(index).isEmpty();
    }

    @Unique
    private List<Entity> firecontrolcompat$occupantsOf(BindingRef seat) {
        Level controllerLevel = this.firecontrolcompat$level();
        Level seatLevel = this.firecontrolcompat$seatLevel(seat);
        if (controllerLevel == null || seatLevel == null || !seatLevel.hasChunkAt(seat.pos())) {
            return List.of();
        }
        AABB box = new AABB(seat.pos()).inflate(1.5);
        List<Entity> occupants = new ArrayList<>();
        for (Entity vehicle : seatLevel.getEntitiesOfClass(Entity.class, box)) {
            if (vehicle.isRemoved() || !seat.matchesEntityPosition(controllerLevel, vehicle)) {
                continue;
            }
            for (Entity passenger : vehicle.getPassengers()) {
                if (!passenger.isRemoved()) {
                    occupants.add(passenger);
                }
            }
        }
        return occupants;
    }

    @Unique
    private Level firecontrolcompat$seatLevel(BindingRef seat) {
        Level controllerLevel = this.firecontrolcompat$level();
        if (controllerLevel == null) {
            return null;
        }
        if (seat.subLevelId() == null) {
            return controllerLevel;
        }
        try {
            SubLevel subLevel = SubLevelContainer.getContainer(controllerLevel).getSubLevel(seat.subLevelId());
            if (subLevel != null && !subLevel.isRemoved()) {
                return subLevel.getLevel();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$appendSeat(BindingSlot slot, BindingRef target, CallbackInfo ci) {
        if (slot != BindingSlot.SEAT || target == null) {
            return;
        }
        List<BindingRef> seats = this.firecontrolcompat$orderedSeats();
        if (seats.contains(target)) {
            ci.cancel();
            return;
        }
        if (seats.size() >= firecontrolcompat$MAX_SEATS) {
            seats.remove(seats.size() - 1);
        }
        seats.add(target);
        this.bindings.put(BindingSlot.SEAT, seats.get(0));
        this.firecontrolcompat$markChanged();
        ci.cancel();
    }

    @Inject(method = "isSeat", at = @At("RETURN"), cancellable = true)
    private void firecontrolcompat$anyBoundSeat(Entity vehicle, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || vehicle == null || this.firecontrolcompat$level() == null) {
            return;
        }
        for (BindingRef seat : this.firecontrolcompat$orderedSeats()) {
            if (seat.matchesEntityPosition(this.firecontrolcompat$level(), vehicle)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Inject(method = "findSeatedGunner", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$seatPriorityGunner(CallbackInfoReturnable<ServerPlayer> cir) {
        if (this.firecontrolcompat$level() == null || this.firecontrolcompat$level().getServer() == null) {
            return;
        }
        List<BindingRef> seats = this.firecontrolcompat$orderedSeats();
        if (seats.size() <= 1) {
            // The single seat case stays on the pack's own fast path.
            return;
        }
        List<ServerPlayer> players = this.firecontrolcompat$level().getServer().getPlayerList().getPlayers();
        for (int index = 0; index < seats.size(); index++) {
            BindingRef seat = seats.get(index);
            for (ServerPlayer player : players) {
                if (seat.matchesEntityPosition(this.firecontrolcompat$level(), player.getVehicle())) {
                    cir.setReturnValue(player);
                    return;
                }
            }
            if (!this.firecontrolcompat$occupantsOf(seat).isEmpty()) {
                // A crew member holds this post, so the seats behind it stay
                // out of it: that is the whole point of the chain of command.
                cir.setReturnValue(null);
                return;
            }
        }
        cir.setReturnValue(null);
    }

    @Inject(method = "unbindDevice", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$unbindSeat(
            BindingSlot slot, int index, BlockPos expectedPos, CallbackInfoReturnable<Boolean> cir) {
        if (slot != BindingSlot.SEAT) {
            return;
        }
        List<BindingRef> seats = this.firecontrolcompat$seats();
        if (seats.isEmpty()) {
            return;
        }
        if (index < 0) {
            if (!seats.get(0).pos().equals(expectedPos)) {
                return;
            }
            seats.remove(0);
        } else {
            if (index >= seats.size() || !seats.get(index).pos().equals(expectedPos)) {
                cir.setReturnValue(false);
                return;
            }
            seats.remove(index);
        }
        if (seats.isEmpty()) {
            this.bindings.remove(BindingSlot.SEAT);
        } else {
            this.bindings.put(BindingSlot.SEAT, seats.get(0));
        }
        this.firecontrolcompat$markChanged();
        cir.setReturnValue(true);
    }

    @Inject(method = "isSeatedMissileOperator", at = @At("RETURN"), cancellable = true)
    private void firecontrolcompat$reliefPostCannotSteer(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
        if (player == null || !cir.getReturnValueZ()) {
            return;
        }
        int index = this.firecontrolcompat$seatIndex(player);
        if (index <= 0) {
            return;
        }
        for (int ahead = 0; ahead < index; ahead++) {
            if (this.firecontrolcompat$seatManned(ahead)) {
                // The post in front of him holds the mount, so a relief post
                // hands the missile view back while that crew is aboard.
                cir.setReturnValue(false);
                return;
            }
        }
    }

    @Inject(method = "getBoundDevices", at = @At("RETURN"), cancellable = true)
    private void firecontrolcompat$listEverySeat(CallbackInfoReturnable<List<StabilizerControllerBlockEntity.BoundDevice>> cir) {
        List<BindingRef> seats = this.firecontrolcompat$orderedSeats();
        if (seats.size() <= 1) {
            return;
        }
        List<StabilizerControllerBlockEntity.BoundDevice> devices = new ArrayList<>();
        boolean seatsListed = false;
        for (StabilizerControllerBlockEntity.BoundDevice device : cir.getReturnValue()) {
            if (device.slot() != BindingSlot.SEAT) {
                devices.add(device);
                continue;
            }
            if (seatsListed) {
                continue;
            }
            seatsListed = true;
            for (int index = 0; index < seats.size(); index++) {
                devices.add(new StabilizerControllerBlockEntity.BoundDevice(BindingSlot.SEAT, index, seats.get(index)));
            }
        }
        if (!seatsListed) {
            for (int index = 0; index < seats.size(); index++) {
                devices.add(new StabilizerControllerBlockEntity.BoundDevice(BindingSlot.SEAT, index, seats.get(index)));
            }
        }
        cir.setReturnValue(List.copyOf(devices));
    }

    @Inject(method = "removeInvalidBindings", at = @At("RETURN"))
    private void firecontrolcompat$dropDeadSeats(CallbackInfo ci) {
        if (this.firecontrolcompat$level() == null) {
            return;
        }
        this.firecontrolcompat$promoteSeats(this.firecontrolcompat$seats().removeIf(seat -> !seat.isSameTarget(this.firecontrolcompat$level())));
    }

    @Inject(method = "invalidateBindingsAt", at = @At("RETURN"))
    private void firecontrolcompat$invalidateSeats(Level targetLevel, BlockPos targetPos, CallbackInfo ci) {
        this.firecontrolcompat$promoteSeats(
                this.firecontrolcompat$seats().removeIf(seat -> seat.matches(targetLevel, targetPos)));
    }

    @Inject(method = "saveAdditional", at = @At("RETURN"))
    private void firecontrolcompat$saveSeats(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        List<BindingRef> seats = this.firecontrolcompat$seats();
        if (seats.size() <= 1) {
            tag.remove(firecontrolcompat$SEAT_TAG);
            return;
        }
        ListTag list = new ListTag();
        for (BindingRef seat : seats) {
            list.add(seat.save());
        }
        tag.put(firecontrolcompat$SEAT_TAG, list);
    }

    @Inject(method = "loadAdditional", at = @At("RETURN"))
    private void firecontrolcompat$loadSeats(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        List<BindingRef> seats = this.firecontrolcompat$seats();
        seats.clear();
        ListTag list = tag.getList(firecontrolcompat$SEAT_TAG, 10);
        for (int index = 0; index < list.size() && seats.size() < firecontrolcompat$MAX_SEATS; index++) {
            BindingRef seat = BindingRef.loadTag(list.getCompound(index));
            if (!seats.contains(seat)) {
                seats.add(seat);
            }
        }
        BindingRef primary = this.bindings.get(BindingSlot.SEAT);
        if (primary != null && !seats.contains(primary)) {
            seats.add(0, primary);
        }
        if (!seats.isEmpty()) {
            this.bindings.put(BindingSlot.SEAT, seats.get(0));
        }
    }

    @Unique
    private void firecontrolcompat$promoteSeats(boolean changed) {
        if (!changed) {
            return;
        }
        List<BindingRef> seats = this.firecontrolcompat$seats();
        if (seats.isEmpty()) {
            this.bindings.remove(BindingSlot.SEAT);
        } else {
            this.bindings.put(BindingSlot.SEAT, seats.get(0));
        }
    }

    @Unique
    private void firecontrolcompat$markChanged() {
        ((StabilizerControllerBlockEntity) (Object) this).setChanged();
        Level controllerLevel = this.firecontrolcompat$level();
        if (controllerLevel != null) {
            BlockPos pos = ((StabilizerControllerBlockEntity) (Object) this).getBlockPos();
            controllerLevel.sendBlockUpdated(pos, ((StabilizerControllerBlockEntity) (Object) this).getBlockState(), ((StabilizerControllerBlockEntity) (Object) this).getBlockState(), 3);
        }
    }
}
