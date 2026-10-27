package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import com.qkdream.firecontrolcompat.IffRadar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Invalidates every cached friend-or-foe marking whenever a transponder's channel is written.
 *
 * <p>This is the only write path a transponder has. 0.9.4 has no separate on/off field:
 * {@code IffTransponderBlockEntity#isEnabled} answers {@code !channel.isEmpty()}, and the channel
 * screen sends the empty string to turn the transponder off, so <b>switching one off, changing its
 * channel and switching on again all arrive here</b>.</p>
 *
 * <p>Without this hook the mark table answered the same key for the rest of the session: a target
 * that went dark kept its old "hostile" (or "friendly") marking, because neither the transponder's
 * state nor its channel was part of that key. {@link IffRadar#bump} moves the generation, which is
 * part of every key now, so every marking computed before this write becomes unreachable at once -
 * the table itself is left alone and swept at the next insert instead of being emptied here.</p>
 */
@Mixin(IffTransponderBlockEntity.class)
public abstract class IffTransponderChannelMixin {

    @Inject(method = "setChannel", at = @At("HEAD"))
    private void firecontrolcompat$invalidateMarks(String value, CallbackInfo ci) {
        try {
            IffRadar.bump("transponder-channel");
        } catch (Throwable ignored) {
            // a lost invalidation costs at most the TTL window, never the write itself
        }
    }
}
