package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.CompatScanner;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Injects vestalihy / CBCMS / TAOV contacts into the connected display radar
 * right before {@code updateTrackedContacts} stores the scan result, so the
 * automatic missile defense reacts to them through the vanilla pipeline.
 */
@Mixin(ConnectedDisplayBlockEntity.class)
public abstract class ConnectedDisplayScanMixin {

    @Shadow
    private double radarRange;

    @Shadow
    private Vec3 radarVelocity;

    @Invoker("radarOrigin")
    public abstract Vec3 invokeRadarOrigin();

    @Invoker("updateTrackedContacts")
    public abstract void invokeUpdateTrackedContacts(List<ConnectedDisplayBlockEntity.Contact> contacts, long now);

    @Redirect(
            method = "scan",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity;updateTrackedContacts(Ljava/util/List;J)V"
            )
    )
    private void firecontrolcompat$redirectUpdateTrackedContacts(
            ConnectedDisplayBlockEntity instance,
            List<ConnectedDisplayBlockEntity.Contact> found,
            long now
    ) {
        CompatScanner.appendDisplayContacts(instance, found, this.invokeRadarOrigin(), this.radarRange, this.radarVelocity);
        this.invokeUpdateTrackedContacts(found, now);
    }
}