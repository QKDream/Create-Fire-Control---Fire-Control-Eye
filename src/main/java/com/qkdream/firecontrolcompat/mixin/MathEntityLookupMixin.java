package com.qkdream.firecontrolcompat.mixin;

import com.qkdream.firecontrolcompat.missile.MathLocks;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a mathematically flown round answer {@code ServerLevel#getEntity(UUID)}.
 *
 * <h2>Why this one method</h2>
 *
 * <p>Every lock, track and intercept path in fire control 0.9.4 resolves a target the same way:
 * it keeps a {@link UUID} and asks the level for the entity behind it. Grep the four that matter -
 * {@code StabilizerControllerBlockEntity#opticalLockTarget}, {@code #getOpticalLockBounds},
 * {@code #scanRadarMissilesInLevel} and the anti-air / automatic-defence guidance loops - and they
 * all end in {@code serverLevel.getEntity(id)} or in an entity sweep of the level.</p>
 *
 * <p>A round in mathematical mode is never in the level: {@code MathematicalMissileManager.capture}
 * records it and <b>cancels the join event</b>. So the id is right, the round is flying, and the
 * lookup returns {@code null} for ever - the lock is taken and dropped on the next tick, and a
 * contact on the radar list cannot be handed to a missile. That is the whole of "the mathematical
 * round cannot be locked".</p>
 *
 * <p>Filling in <b>this</b> one method fixes all of them at once, and it does it with the round's
 * own entity object - the very object the pack stepped this tick - so positions, velocity and
 * bounding box are the live ones and no second copy of the state exists to drift.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>The injection runs on every failed lookup, so it is gated twice: the level answer is only
 * overridden when it was {@code null}, and the mathematical table is only consulted at all while
 * something is actually flying mathematically ({@link MathLocks#anyActive()}). With an empty sky -
 * the normal case for the whole game - this is one static call and one {@code Map#size}.</p>
 *
 * <p>Nothing is invented: {@link MathLocks#resolve} reads the pack's own {@code ACTIVE} table and
 * refuses removed or dead rounds, so a round that has just detonated is not resurrected by a lookup
 * made in the same tick.</p>
 */
@Mixin(ServerLevel.class)
public abstract class MathEntityLookupMixin {

    @Inject(
            method = "getEntity(Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void firecontrolcompat$getMathMissile(UUID id, CallbackInfoReturnable<Entity> cir) {
        try {
            if (cir.getReturnValue() != null || id == null) {
                return;
            }
            if (!MathLocks.anyActive()) {
                return;
            }
            Entity round = MathLocks.resolve((ServerLevel) (Object) this, id);
            if (round != null) {
                cir.setReturnValue(round);
            }
        } catch (Throwable ignored) {
            // a lookup that cannot answer keeps the level's own answer
        }
    }
}
