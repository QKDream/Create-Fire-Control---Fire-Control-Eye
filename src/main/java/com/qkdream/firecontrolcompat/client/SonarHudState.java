package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.network.SonarSessionPayload;
import com.qkdream.firecontrolcompat.sonar.SonarKind;
import com.qkdream.firecontrolcompat.sonar.SonarWeight;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * The sonar pictures the server last sent for the station being operated: one
 * entry per set wired to that computer, each with the dish it belongs to, the
 * window it listens through and the place it wants to be drawn.
 *
 * <p>The mark the operator has picked off the picture rides with them, so the
 * screen can put the bars around it wherever it falls.</p>
 */
public final class SonarHudState {

    /** One mark on a picture: what was heard, how loud, how strong, and where. */
    public record Mark(SonarKind kind, SonarWeight weight, int echo, Vec3 position) {
    }

    /** One set's picture. */
    public record Panel(
            Vec3 origin,
            Vec3 forward,
            double range,
            double halfAngle,
            double elevationUp,
            double elevationDown,
            boolean pinging,
            double hudX,
            double hudY,
            double hudScale,
            String name,
            List<Mark> marks) {
    }

    private static boolean active;
    private static Vec3 lock;
    private static SonarKind lockKind;
    private static List<Panel> panels = List.of();

    private SonarHudState() {
    }

    public static void accept(SonarSessionPayload payload) {
        active = payload.active();
        lock = payload.lock();
        lockKind = payload.lockKind();
        List<Panel> received = new ArrayList<>(payload.panels().size());
        for (SonarSessionPayload.Panel panel : payload.panels()) {
            List<Mark> marks = new ArrayList<>(panel.marks().size());
            for (SonarSessionPayload.Mark mark : panel.marks()) {
                marks.add(new Mark(mark.kind(), mark.weight(), mark.echo(), mark.position()));
            }
            received.add(new Panel(
                    panel.origin(),
                    panel.forward(),
                    Math.max(1.0D, panel.range()),
                    Math.max(1.0D, panel.halfAngle()),
                    Math.max(0.0D, panel.elevationUp()),
                    Math.max(0.0D, panel.elevationDown()),
                    panel.pinging(),
                    panel.hudX(),
                    panel.hudY(),
                    panel.hudScale(),
                    panel.name(),
                    marks));
        }
        panels = received;
    }

    public static void clear() {
        active = false;
        lock = null;
        lockKind = null;
        panels = List.of();
    }

    public static boolean isActive() {
        return active && !panels.isEmpty();
    }

    public static List<Panel> panels() {
        return panels;
    }

    /** The mark the operator has picked off the picture, or null when none. */
    public static Vec3 lock() {
        return lock;
    }

    /** What was picked - how it sounded - or null when the picture holds no mark. */
    public static SonarKind lockKind() {
        return lockKind;
    }

}
