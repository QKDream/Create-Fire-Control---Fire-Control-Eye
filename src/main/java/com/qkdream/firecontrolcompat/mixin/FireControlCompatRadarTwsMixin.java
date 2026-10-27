package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.client.ClientAimState;
import com.hooya.stabilizedturret.client.ClientRadarState;
import com.hooya.stabilizedturret.client.ClientTelevisionGuidanceState;
import com.hooya.stabilizedturret.client.RadarHudRenderer;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.client.LeadArrowRenderer;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The radar HUD overlay, reduced to the one thing fire control does not draw itself.
 *
 * <h2>What was removed, and why (2026-10-26, task 4)</h2>
 *
 * <p>This mixin used to draw the whole track-while-scan picture: a four-corner bracket on every
 * contact, a closed square plus selection bars on the locked one, and a dot on a distant one. It was
 * written on the belief that fire control's own {@code renderWorldFrames} was unreachable in first
 * person - which was true for the pass <b>that</b> version of the mod called, but the pack has its
 * own drawing for exactly these contacts:</p>
 *
 * <pre>RadarHudRenderer#renderWorldFrames (:635-682, 0.9.4)
 *   else branch (ground radar):
 *     for each ClientRadarState.Contact
 *       locked  -&gt; CannonLeadHud.render + TargetLockFrame.render(.., Style.GROUND_RADAR, amber)
 *       other   -&gt; TargetLockFrame.renderRadarContact(.., -798041913)</pre>
 *
 * <p>so in third person or through the scope the gunner saw two frames on one track: the pack's and
 * ours, at slightly different sizes. The report is the user's own words - the lock frame overlaps.</p>
 *
 * <p>Ours is therefore gone. The pack's is the only frame, which also means the <b>contact list</b>
 * it draws from is the only source: a mathematical round is a {@code RadarContact} on the server
 * (this mod appends it, see {@code CompatScanner#appendMathRadarContacts}), it travels to the client
 * in the pack's own payload and lands in {@code ClientRadarState.getContacts()} like any other
 * track - so the pack's drawer frames it with no help from here. Nothing has to be re-fed.</p>
 *
 * <h2>What is kept</h2>
 *
 * <p>The lead arrow. It is this mod's own designation aid ({@link LeadArrowRenderer}, driven by
 * {@code stabilizedContactVelocity}) and the pack's {@code CannonLeadHud} is drawn only inside
 * {@code renderWorldFrames} - i.e. never in the unscoped <b>first person</b> camera, which is how a
 * gunner sits at a computer. So the arrow is drawn exactly where the pack draws nothing, and
 * suppressed where the pack's own lead marker is on screen. That is the whole rule:
 * <b>one picture, from whichever side has one.</b></p>
 *
 * <p>Every frame says which side drew: {@code radar tws: ours=off pack=&lt;on|off&gt; contacts=..}.</p>
 *
 * <p>The removed drawing is preserved verbatim in
 * {@code _bak_src/task4_tws_20261026_223328/FireControlCompatRadarTwsMixin.java} - restoring it is
 * copying that file back and rebuilding.</p>
 */
@Mixin(RadarHudRenderer.class)
public abstract class FireControlCompatRadarTwsMixin {

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private static void firecontrolcompat$twsOverlay(RenderGuiEvent.Post event, CallbackInfo ci) {
        try {
            firecontrolcompat$drawTws(event);
        } catch (Throwable failure) {
            firecontrolcompat$reportTws(0, 0, false, "exception:" + failure);
        }
    }

    /**
     * Whether fire control is drawing the radar frames this frame.
     *
     * <p>Mirrors the condition in {@code RadarHudRenderer#render} :54 exactly - aircraft radar, a
     * scoped sight, or any camera but the first-person one. Inside that condition the pack draws
     * every contact and the lock; outside it draws nothing at all.</p>
     */
    @Unique
    private static boolean firecontrolcompat$packDrawsFrames(Minecraft minecraft) {
        try {
            return ClientRadarState.isAircraftMode()
                    || ClientAimState.isScoped()
                    || minecraft.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON;
        } catch (Throwable ignored) {
            // Unreadable state: assume the pack is drawing, because a missing frame is recoverable
            // with a camera change and a duplicate frame is the defect being fixed.
            return true;
        }
    }

    /**
     * Draws the lead arrow when - and only when - fire control is not drawing its own.
     */
    @Unique
    private static void firecontrolcompat$drawTws(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientRadarState.isActive()
                || ClientAimState.isPodView()
                || ClientTelevisionGuidanceState.isActive()
                || minecraft.options.hideGui
                || minecraft.screen != null) {
            return;
        }
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts == null) {
            firecontrolcompat$reportTws(0, 0, false, "no-contact-table");
            return;
        }
        boolean packFrames = firecontrolcompat$packDrawsFrames(minecraft);
        if (packFrames) {
            // The pack's TargetLockFrame / CannonLeadHud own the picture; ours would be a duplicate
            // drawn a few pixels off, which is the overlap this round removed.
            firecontrolcompat$reportTws(contacts.size(), 0, true, "pack-owns-frames");
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        UUID locked = ClientRadarState.getLockedTarget();
        if (locked == null) {
            firecontrolcompat$reportTws(contacts.size(), 0, false, "pack-off-no-lock");
            return;
        }
        int arrows = 0;
        for (ClientRadarState.Contact contact : contacts) {
            if (contact == null || !locked.equals(contact.id())) {
                continue;
            }
            if (firecontrolcompat$groundLeadArrow(graphics, minecraft, partialTick, contact)) {
                arrows++;
            }
            break;
        }
        firecontrolcompat$reportTws(contacts.size(), arrows, false,
                arrows > 0 ? "lead-arrow" : "pack-off-no-velocity");
    }

    /**
     * One line per change, at most one every {@link #TWS_LOG_TICKS} ticks.
     *
     * <p>The stamp is tested against {@code 0} directly and never subtracted from
     * {@code Long.MIN_VALUE}: that comparison wraps and silences the line for every positive game
     * time, which is how three earlier "no contacts" reports went unread.</p>
     *
     * <pre>radar tws: ours=off pack=&lt;on|off&gt; contacts=&lt;n&gt; arrows=&lt;n&gt; reason=&lt;..&gt;</pre>
     */
    @Unique
    private static void firecontrolcompat$reportTws(
            int contacts, int arrows, boolean packFrames, String reason) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
            boolean pack = packFrames || "pack-owns-frames".equals(reason);
            if (contacts == firecontrolcompat$lastContacts && arrows == firecontrolcompat$lastArrows
                    && pack == firecontrolcompat$lastPack
                    && reason.equals(firecontrolcompat$lastReason)
                    && firecontrolcompat$twsLogAt != 0L && now - firecontrolcompat$twsLogAt < TWS_LOG_TICKS) {
                return;
            }
            firecontrolcompat$twsLogAt = now;
            firecontrolcompat$lastContacts = contacts;
            firecontrolcompat$lastArrows = arrows;
            firecontrolcompat$lastPack = pack;
            firecontrolcompat$lastReason = reason;
            FireControlCompat.LOGGER.info(
                    "radar tws: ours=off pack={} contacts={} arrows={} reason={}",
                    pack ? "on" : "off", contacts, arrows, reason);
        } catch (Throwable ignored) {
            // Reporting must never take the HUD down.
        }
    }

    @Unique
    private static final int TWS_LOG_TICKS = 40;

    @Unique
    private static long firecontrolcompat$twsLogAt;

    @Unique
    private static int firecontrolcompat$lastContacts = -1;

    @Unique
    private static int firecontrolcompat$lastArrows = -1;

    @Unique
    private static boolean firecontrolcompat$lastPack;

    @Unique
    private static String firecontrolcompat$lastReason = "";

    /**
     * The lead arrow at the locked contact, if it has a velocity worth leading.
     *
     * @return whether an arrow was actually drawn
     */
    @Unique
    private static boolean firecontrolcompat$groundLeadArrow(
            GuiGraphics graphics, Minecraft minecraft, float partialTick,
            ClientRadarState.Contact lockedContact) {
        if (ClientAimState.isOpticalLockActive()) {
            return false;
        }
        int[] origin = firecontrolcompat$project(
                minecraft, lockedContact.center(), graphics.guiWidth(), graphics.guiHeight(), partialTick);
        if (origin == null) {
            return false;
        }
        Vec3 leadVelocity =
                LeadArrowRenderer.stabilizedContactVelocity(
                        lockedContact.id(), lockedContact.velocity());
        if (leadVelocity == null) {
            return false;
        }
        LeadArrowRenderer.draw(
                graphics, origin[0], origin[1], minecraft, partialTick,
                lockedContact.center(), leadVelocity);
        return true;
    }

    @Unique
    private static int[] firecontrolcompat$project(
            Minecraft minecraft, Vec3 position, int width, int height, float partialTicks) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 offset = position.subtract(camera.getPosition());
        Vector3f look = camera.getLookVector();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vec3 lookVector = new Vec3(look.x, look.y, look.z);
        Vec3 leftVector = new Vec3(left.x, left.y, left.z);
        Vec3 upVector = new Vec3(up.x, up.y, up.z);
        double forward = offset.dot(lookVector);
        if (forward <= 0.05D) {
            return null;
        }
        double scale = firecontrolcompat$pixelScale(minecraft, camera, height, partialTicks);
        int x = width / 2 + (int) Math.round(-offset.dot(leftVector) * scale / forward);
        int y = height / 2 + (int) Math.round(-offset.dot(upVector) * scale / forward);
        return new int[] {x, y};
    }

    /** Pixels per unit at one block of depth, from the field of view the pack draws with. */
    @Unique
    private static double firecontrolcompat$pixelScale(
            Minecraft minecraft, Camera camera, int height, float partialTicks) {
        double fov = ((GameRendererAccessor) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, partialTicks, true);
        return (height * 0.5D) / Math.tan(Math.toRadians(fov) * 0.5D);
    }
}
