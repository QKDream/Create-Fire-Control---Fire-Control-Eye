package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.MathematicalMissileManager;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.missile.MathIntercept;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the interception sweep over the rounds the pack flies in mathematical mode.
 *
 * <p>Those rounds are entity objects that <b>never join the level</b>:
 * {@code MathematicalMissileManager.capture} records them and then cancels the join event, so a
 * plain {@code getEntities} sweep - the one the old interception used for its shells, and the one
 * the radar used for its contacts - can never meet one. What they do have is a live position and
 * velocity, stepped every tick, and they are listed by
 * {@code MathematicalMissileManager.missiles(level)}.</p>
 *
 * <p>{@code sync(ServerTickEvent.Post)} is the manager's own once-per-tick entry point and the last
 * thing it does with the round table each tick, which makes it the right place to take the sweep:
 * one call per tick, the positions are this tick's, and the level list comes from the event itself
 * rather than from another private field.</p>
 */
@Mixin(MathematicalMissileManager.class)
public abstract class MathEntityInterceptMixin {

    @Inject(method = "sync", at = @At("HEAD"), require = 0)
    private static void firecontrolcompat$sweepMathMissiles(
            net.neoforged.neoforge.event.tick.ServerTickEvent.Post event, CallbackInfo ci) {
        try {
            MinecraftServer server = event.getServer();
            if (server == null) {
                return;
            }
            // One sweep per root level, not one per level object: a mathematical round is filed
            // under its root, so sweeping the root once and each of its plots again would run the
            // same round through the same fuse several times a tick - and pay for it. A Sable plot
            // that does appear in {@code getAllLevels} is therefore skipped here.
            Seen seen = new Seen();
            for (ServerLevel level : server.getAllLevels()) {
                if (level == null) {
                    continue;
                }
                ServerLevel root = MathIntercept.rootOf(level);
                if (!seen.add(root)) {
                    continue;
                }
                MathIntercept.sweepLevel(root);
            }
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.info("math-intercept sweep hook skipped: {}", failure.toString());
        }
    }

    /** Identity set of the root levels already swept this tick. */
    private static final class Seen {

        private final java.util.Set<ServerLevel> roots =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        private boolean add(ServerLevel root) {
            return root != null && this.roots.add(root);
        }
    }
}
