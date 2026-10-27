package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.FireControlMunitions;
import java.util.Set;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a round fire control is flying in front of the player for the whole of
 * her path.
 *
 * <p>A round is only sent to a player while she stands inside the range her own
 * entity type asks for - a few dozen blocks for a missile - so she drops out of
 * the frame a second or two after the launch and is not seen again until she
 * goes off. Fire control lifts that range for the rounds carrying its marks;
 * this does the same for every round {@link FireControlMunitions} knows about,
 * the cruise missile flown off a bound rail among them, so the round and the
 * trail behind her run unbroken out to the horizon.</p>
 *
 * <p>The player's own view distance still decides what the client draws, as it
 * does for fire control's own rounds: this is what stops her being dropped long
 * before that.</p>
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity", priority = 1500)
public abstract class GuidedMunitionTrackingMixin {

    /** How far out a round is still sent to a player, in blocks. */
    private static final double KEEP_IN_SIGHT_SQR = 4.0E8;

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Shadow
    @Final
    private ServerEntity serverEntity;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void firecontrolcompat$keepGuidedMunitionInSight(ServerPlayer player, CallbackInfo ci) {
        if (com.qkdream.firecontrolcompat.NativeMissileMode.mathematical(this.entity)) {
            // Fire control 0.9.3 flies this round by its own flight record; adding our copy
            // would draw the same shot twice and let it drift out of step.
            return;
        }

        if (!FireControlMunitions.isTakenOver(this.entity)) {
            return;
        }
        ci.cancel();
        if (player == this.entity) {
            return;
        }
        Vec3 at = FireControlMunitions.worldPosition(this.entity);
        Vec3 delta = player.position().subtract(at);
        boolean keep = delta.x * delta.x + delta.z * delta.z <= KEEP_IN_SIGHT_SQR
                && this.entity.broadcastToPlayer(player);
        if (keep) {
            if (this.seenBy.add(player.connection)) {
                this.serverEntity.addPairing(player);
            }
        } else if (this.seenBy.remove(player.connection)) {
            this.serverEntity.removePairing(player);
        }
    }
}
