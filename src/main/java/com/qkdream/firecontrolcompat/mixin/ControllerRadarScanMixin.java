package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.qkdream.firecontrolcompat.CompatScanner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Adds hostile ordnance entities (mianbaos / vestalihy / CBCMS) and Shaolib
 * Tau/Hellfire instances to the stabilizer controller radar scan, so they get
 * the same HUD contacts, lock boxes and weapon guidance as Sable structures.
 */
@Mixin(StabilizerControllerBlockEntity.class)
public abstract class ControllerRadarScanMixin {

    @Redirect(
            method = "updateRadarScan",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;copyOf(Ljava/util/Collection;)Ljava/util/List;"
            )
    )
    private List<StabilizerControllerBlockEntity.RadarContact> firecontrolcompat$redirectCopyOf(
            Collection<StabilizerControllerBlockEntity.RadarContact> collection
    ) {
        List<StabilizerControllerBlockEntity.RadarContact> contacts = new ArrayList<>(collection);
        CompatScanner.appendRadarContacts((StabilizerControllerBlockEntity) (Object) this, contacts);
        return List.copyOf(contacts);
    }
}
