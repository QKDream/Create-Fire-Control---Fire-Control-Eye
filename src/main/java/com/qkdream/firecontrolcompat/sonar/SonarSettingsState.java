package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.network.SonarSettingsSyncPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The sonar settings last sent by the server, so the page on the fire control
 * screen lists the sets that are really wired to the computer and shows the
 * values they are running with. One entry per set, in the order the server
 * found them, with the page remembering which of them the player is editing.
 */
public final class SonarSettingsState {

    /** One set as the page shows it. */
    public static final class Entry {

        private final long pos;
        private final UUID subLevel;
        private double range;
        private double halfAngle;
        private double elevationUp;
        private double elevationDown;
        private boolean active;
        private double hudX;
        private double hudY;
        private double hudScale;
        private String name = "";

        Entry(
                long pos,
                UUID subLevel,
                double range,
                double halfAngle,
                double elevationUp,
                double elevationDown,
                boolean active,
                double hudX,
                double hudY,
                double hudScale,
                String name) {
            this.pos = pos;
            this.subLevel = subLevel;
            this.range = SonarBlockEntity.clampRange(range);
            this.halfAngle = SonarBlockEntity.clampAngle(halfAngle);
            this.elevationUp = SonarBlockEntity.clampElevation(elevationUp);
            this.elevationDown = SonarBlockEntity.clampElevation(elevationDown);
            this.active = active;
            this.hudX = SonarBlockEntity.clampHud(hudX);
            this.hudY = SonarBlockEntity.clampHud(hudY);
            this.hudScale = SonarBlockEntity.clampHudScale(hudScale);
            this.name = SonarBlockEntity.clampName(name);
        }

        public long pos() {
            return this.pos;
        }

        public UUID subLevel() {
            return this.subLevel;
        }

        public double range() {
            return this.range;
        }

        public void range(double value) {
            this.range = SonarBlockEntity.clampRange(value);
        }

        public double halfAngle() {
            return this.halfAngle;
        }

        public void halfAngle(double value) {
            this.halfAngle = SonarBlockEntity.clampAngle(value);
        }

        public double elevationUp() {
            return this.elevationUp;
        }

        public void elevationUp(double value) {
            this.elevationUp = SonarBlockEntity.clampElevation(value);
        }

        public double elevationDown() {
            return this.elevationDown;
        }

        public void elevationDown(double value) {
            this.elevationDown = SonarBlockEntity.clampElevation(value);
        }

        public boolean activeMode() {
            return this.active;
        }

        public void activeMode(boolean value) {
            this.active = value;
        }

        public double hudX() {
            return this.hudX;
        }

        public void hudX(double value) {
            this.hudX = SonarBlockEntity.clampHud(value);
        }

        public double hudY() {
            return this.hudY;
        }

        public void hudY(double value) {
            this.hudY = SonarBlockEntity.clampHud(value);
        }

        public double hudScale() {
            return this.hudScale;
        }

        public String name() {
            return this.name;
        }

        public void name(String value) {
            this.name = SonarBlockEntity.clampName(value);
        }

        public void hudScale(double value) {
            this.hudScale = SonarBlockEntity.clampHudScale(value);
        }
    }

    private static boolean bound;
    private static List<Entry> entries = List.of();

    private SonarSettingsState() {
    }

    /** Takes an answer from the server, keeping the edited values of sets it still lists. */
    public static void accept(boolean hasSonar, List<SonarSettingsSyncPayload.Entry> received) {
        bound = hasSonar;
        List<Entry> next = new ArrayList<>(received.size());
        for (SonarSettingsSyncPayload.Entry entry : received) {
            next.add(new Entry(
                    entry.pos(), entry.subLevel(), entry.range(), entry.halfAngle(),
                    entry.elevationUp(), entry.elevationDown(), entry.active(),
                    entry.hudX(), entry.hudY(), entry.hudScale(), entry.name()));
        }
        entries = next;
    }

    /** Whether the computer whose screen is open has a set wired to it. */
    public static boolean hasSonar() {
        return bound && !entries.isEmpty();
    }

    public static List<Entry> entries() {
        return entries;
    }

    /** The set at that index, or null when the list is shorter than that. */
    public static Entry entry(int index) {
        return index < 0 || index >= entries.size() ? null : entries.get(index);
    }

    public static int size() {
        return entries.size();
    }
}
