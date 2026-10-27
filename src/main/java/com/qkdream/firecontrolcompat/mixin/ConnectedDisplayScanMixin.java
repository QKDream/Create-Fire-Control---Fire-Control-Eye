package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.qkdream.firecontrolcompat.CompatScanner;
import com.qkdream.firecontrolcompat.EmpEffect;
import com.qkdream.firecontrolcompat.FireControlLeadSettings;
import com.qkdream.firecontrolcompat.IffRadar;
import com.qkdream.firecontrolcompat.RadarEmitters;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Injects vestalihy / CBCMS / TAOV contacts into the connected display radar
 * right before {@code updateTrackedContacts} stores the scan result, so the
 * automatic missile defense reacts to them through the vanilla pipeline.
 *
 * <p>The stored list goes through the same radar gate as the fire control
 * computer's scan: platforms sharing an IFF channel with the display's rig stay
 * invisible to it, and the speed gate is honoured when it is armed.</p>
 *
 * <p>The gate also takes the EMP blackout into account: while the display sits
 * inside a burst it stores an empty sweep and is not registered as an emitter,
 * so the screen goes blind for the length of the blackout.</p>
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
    public abstract void invokeUpdateTrackedContacts(
            List<ConnectedDisplayBlockEntity.Contact> contacts,
            long time,
            Set<String> iffChannels,
            Set<UUID> friendlyPlayers);

    @Redirect(
            method = "scan",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hooya/stabilizedturret/content/display/ConnectedDisplayBlockEntity;"
                            + "updateTrackedContacts(Ljava/util/List;JLjava/util/Set;Ljava/util/Set;)V"
            )
    )
    private void firecontrolcompat$redirectUpdateTrackedContacts(
            ConnectedDisplayBlockEntity instance,
            List<ConnectedDisplayBlockEntity.Contact> found,
            long time,
            Set<String> iffChannels,
            Set<UUID> friendlyPlayers
    ) {
        Level level = instance.getLevel();
        if (EmpEffect.isDown(level, instance.getBlockPos())) {
            found.clear();
            this.invokeUpdateTrackedContacts(found, time, iffChannels, friendlyPlayers);
            return;
        }
        CompatScanner.appendDisplayContacts(instance, found, this.invokeRadarOrigin(), this.radarRange, this.radarVelocity);
        CompatScanner.stripStealthCruiseContactsDisplay(level, found, this.invokeRadarOrigin());
        RadarEmitters.rememberDisplay(instance);
        if (level != null) {
            Set<String> own = IffRadar.ownChannels(level, instance);
            if (!own.isEmpty()) {
                found.removeIf(contact -> IffRadar.hiddenFromDisplayRadar(level, contact, own));
            }
        }
        this.invokeUpdateTrackedContacts(found, time, iffChannels, friendlyPlayers);
    }
}
