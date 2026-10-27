package com.qkdream.firecontrolcompat.client;

import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.sonar.SonarKind;
import com.qkdream.firecontrolcompat.sonar.SonarWeight;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * The sonar pictures: a bearing-against-range plot of everything each set has
 * heard, drawn while the player is sitting at a station that has sets wired up.
 *
 * <p>One mark on the picture answers to the key rather than to the water: the
 * contact the operator has picked, bracketed by the two bars that say it is the
 * one the computer's tubes are laid on.</p>
 *
 * <p>A computer can carry several dishes, so every set gets its own panel, put
 * where its own HUD settings ask for it and sized by its own scale. The dish
 * looks up the screen. A mark is placed by where it is off the bow and how far
 * away it is, and its colour is its weight - a living thing or a noise from the
 * world is pale, a hull under way is mid blue, a gun going off or another set
 * pinging is deep blue - so the loud things stand out without reading anything.
 * One line belongs to the operator rather than to the water: the column of the
 * plot that their own view falls in, so the way they are looking can be read
 * against the bearings the set is reporting.</p>
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT)
public final class SonarHudRenderer {

    private static final int BORDER = 0xFF2B4B72;
    private static final int SHADOW = 0xC0000000;
    private static final int PANEL = 0xF0080C18;
    private static final int HEADER = 0xF00A2244;
    private static final int PLOT = 0xF0101826;
    private static final int GRID = 0x55233C5C;
    private static final int TEXT = 0xFF9FD8FF;
    private static final int DIM = 0xFF4E7BA8;
    private static final int SWEEP = 0x5560E0FF;
    private static final int OWN = 0xFF7CFFB4;
    private static final int LOCK = 0xFFFFC24D;
    /** The word that names a picked mark: a plain blue. */
    private static final int LOCK_TEXT = 0xFF3FA9FF;

    /** The three things a set tells apart by shape, in the order it names them. */
    private static final SonarKind[] MARK_KINDS = {SonarKind.MOB, SonarKind.BODY, SonarKind.AMMO};
    /** Side of the square drawn for a hull in the key to the marks. */
    private static final int LEGEND_SQUARE = 5;

    /** Side of the square a mark is drawn at, before its echo is counted in. */
    private static final int MARK_SIDE = 4;
    /** Pixels of mark each step of the echo a hull gives back adds. */
    private static final int ECHO_SIDE = 3;
    /** Side of the cross and the diamond a creature or a warhead is drawn with. */
    private static final int SHAPE_SIDE = 5;
    /** How far the bars of a pick stand off the mark they bracket. */
    private static final int LOCK_BAR_CLEARANCE = 3;
    /** How long the bars of a pick are at the very least, so they are still seen. */
    private static final int LOCK_BAR_MIN_HALF = 2;
    /** Distance the name of a picked mark is kept from its bars. */
    private static final int LOCK_LABEL_GAP = 3;
    /** How far a mark may lie from the picked point and still be that same mark. */
    private static final double LOCK_MARK_RANGE = 4.0D;

    /** Width a set's picture is drawn at, before its own scale is applied. */
    public static final int BASE_WIDTH = 200;
    /** Height of a picture, as a fraction of its width. */
    public static final float ASPECT = 0.64F;

    private SonarHudRenderer() {
    }

    /** Width a picture takes at that scale, clamped to what the window can hold. */
    public static int panelWidth(int screenWidth, double scale) {
        int wanted = (int) Math.round(BASE_WIDTH * Mth.clamp(scale, 40.0D, 180.0D) / 100.0D);
        return Mth.clamp(wanted, 90, Math.max(90, screenWidth - 8));
    }

    /** Height a picture takes at that width. */
    public static int panelHeight(int width) {
        return Math.round(width * ASPECT);
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!SonarHudState.isActive() || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        List<SonarHudState.Panel> panels = SonarHudState.panels();
        for (int index = 0; index < panels.size(); index++) {
            SonarHudState.Panel panel = panels.get(index);
            int width = panelWidth(screenWidth, panel.hudScale());
            int height = panelHeight(width);
            int maxX = Math.max(0, screenWidth - width);
            int maxY = Math.max(0, screenHeight - height);
            int left = Mth.clamp((int) Math.round(maxX * Mth.clamp(panel.hudX(), 0.0D, 100.0D) / 100.0D), 0, maxX);
            int top = Mth.clamp((int) Math.round(maxY * Mth.clamp(panel.hudY(), 0.0D, 100.0D) / 100.0D), 0, maxY);
            drawPanel(graphics, minecraft, left, top, width, height, panel, index, panels.size());
        }
    }

    private static void drawPanel(
            GuiGraphics graphics,
            Minecraft minecraft,
            int left,
            int top,
            int width,
            int height,
            SonarHudState.Panel panel,
            int index,
            int total) {
        int right = left + width;
        int bottom = top + height;
        int plotLeft = left + 6;
        int plotRight = right - 6;
        int plotTop = top + 26;
        // A picture wide enough carries the key to its marks: the weights in
        // colour and the three things a set looks for in shape. A small one
        // keeps the plot and the colours.
        boolean keyed = width >= 150;
        int plotBottom = bottom - (keyed ? 34 : 15);
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, SHADOW);
        graphics.fill(left, top, right, bottom, PANEL);
        graphics.fill(left, top, right, top + 15, HEADER);
        graphics.fill(plotLeft, plotTop, plotRight, plotBottom, PLOT);
        graphics.fill(left, top, right, top + 1, BORDER);
        graphics.fill(left, bottom - 1, right, bottom, BORDER);
        graphics.fill(left, top, left + 1, bottom, BORDER);
        graphics.fill(right - 1, top, right, bottom, BORDER);

        String name = panel.name();
        Component title = name == null || name.isEmpty()
                ? (total > 1
                        ? Component.translatable("gui.firecontrolcompat.sonar_hud_title_named", index + 1)
                        : Component.translatable("gui.firecontrolcompat.sonar_hud_title"))
                : Component.literal(name);
        graphics.drawString(minecraft.font, title, left + 4, top + 4, TEXT, false);
        String mode = Component.translatable(panel.pinging()
                ? "gui.firecontrolcompat.sonar_active"
                : "gui.firecontrolcompat.sonar_passive").getString();
        graphics.drawString(minecraft.font, mode, right - 4 - minecraft.font.width(mode), top + 4, TEXT, false);

        for (int column = 0; column <= 4; column++) {
            int x = plotLeft + (plotRight - plotLeft) * column / 4;
            graphics.fill(x, plotTop, x + 1, plotBottom, GRID);
        }
        for (int row = 0; row <= 3; row++) {
            int y = plotTop + (plotBottom - plotTop) * row / 3;
            graphics.fill(plotLeft, y, plotRight, y + 1, GRID);
        }

        long time = minecraft.level.getGameTime();
        double phase = time % 100L / 50.0D;
        double sweep = phase <= 1.0D ? phase : 2.0D - phase;
        int sweepX = plotLeft + (int) Math.round((plotRight - plotLeft) * sweep);
        graphics.fill(sweepX - 1, plotTop, sweepX + 1, plotBottom, SWEEP);

        drawSightline(graphics, minecraft, panel, plotLeft, plotTop, plotRight, plotBottom);
        drawMarks(graphics, panel, plotLeft, plotTop, plotRight, plotBottom);
        drawLock(graphics, panel, plotLeft, plotTop, plotRight, plotBottom);

        int centreX = (plotLeft + plotRight) / 2;
        graphics.fill(centreX - 2, plotBottom - 3, centreX + 2, plotBottom, OWN);
        graphics.fill(centreX - 1, plotBottom - 6, centreX + 1, plotBottom - 3, OWN);

        if (keyed) {
            String window = String.format(
                    Locale.ROOT, "%.0f°  +%.0f/-%.0f°  %.0f m",
                    panel.halfAngle(), panel.elevationUp(), panel.elevationDown(), panel.range());
            graphics.drawString(minecraft.font, window, plotLeft, top + 17, DIM, false);

            // What the marks are, next to the colours that say how loud they are,
            // so a set that is pinging can be read for what is in the water. A set
            // that is only listening names nothing, so it carries the colours of
            // the weights alone and no key to the shapes.
            if (panel.pinging()) {
                int shapeX = left + 8;
                int shapeY = bottom - 26;
                for (SonarKind kind : MARK_KINDS) {
                    drawShape(graphics, kind, shapeX, shapeY, LEGEND_SQUARE, DIM);
                    Component label = Component.translatable(markKey(kind));
                    graphics.drawString(minecraft.font, label, shapeX + 7, shapeY - 4, DIM, false);
                    shapeX += 13 + minecraft.font.width(label);
                }
            }
        }

        int legendY = keyed ? bottom - 11 : bottom - 12;
        int x = left + 4;
        for (SonarWeight weight : SonarWeight.values()) {
            graphics.fill(x, legendY, x + 4, legendY + 4, weight.color());
            Component label = Component.translatable(weight.translationKey());
            graphics.drawString(minecraft.font, label, x + 6, legendY - 2, DIM, false);
            x += 10 + minecraft.font.width(label);
        }
    }

    /**
     * Draws what a set has heard: one mark per source, coloured by how loud it
     * is and shaped by what it is. A hull answers a ping as loudly as she is
     * large, so her mark is drawn the bigger for the echo that came off her.
     *
     * <p>A set that is only listening tells nothing apart: every mark is the
     * same square in the colour of the weight that was heard, because how loud a
     * thing is is all a hydrophone can say. Naming what was heard - a creature, a
     * hull, ordnance - is the ping's work and is only drawn off a ping.</p>
     */
    private static void drawMarks(
            GuiGraphics graphics,
            SonarHudState.Panel panel,
            int plotLeft,
            int plotTop,
            int plotRight,
            int plotBottom) {
        Plot plot = Plot.of(panel, plotLeft, plotTop, plotRight, plotBottom);

        for (SonarHudState.Mark mark : panel.marks()) {
            int[] point = plot.of(mark.position());
            if (point == null) {
                continue;
            }
            if (!panel.pinging()) {
                drawSound(graphics, point[0], point[1], mark.weight().color());
                continue;
            }
            drawShape(graphics, mark.kind(), point[0], point[1], markSide(mark), mark.weight().color());
        }
    }

    /**
     * The two bars of the mark the operator has picked, and the word that names
     * it.
     *
     * <p>A picked mark is the one the computer's torpedo tubes are laid on, so
     * it is bracketed rather than framed: two bars either side of it and no
     * square over it, so the contact itself stays readable - what it is, how
     * loud it is and how big a mark it is drawn with. The bars are as long as
     * that mark is tall and stand off it by the same measure, so a hull that
     * answers a ping loudly carries longer bars than a quiet swimmer and the
     * pick reads as the contact it belongs to. A picture the mark does not fall
     * in - behind the dish, outside the window it listens through, or past its
     * range - has nothing to draw.</p>
     */
    private static void drawLock(
            GuiGraphics graphics,
            SonarHudState.Panel panel,
            int plotLeft,
            int plotTop,
            int plotRight,
            int plotBottom) {
        Vec3 locked = SonarHudState.lock();
        if (locked == null) {
            return;
        }
        int[] point = Plot.of(panel, plotLeft, plotTop, plotRight, plotBottom).of(locked);
        if (point == null) {
            return;
        }
        int x = point[0];
        int y = point[1];
        // No square is drawn over the mark: the picture keeps showing what the
        // contact itself is, and the two bars say which one has been taken.
        int half = Math.max(LOCK_BAR_MIN_HALF, (lockMarkSide(panel, locked) + 1) / 2);
        int gap = half + LOCK_BAR_CLEARANCE;
        graphics.fill(x - gap - 1, y - half, x - gap, y + half, LOCK);
        graphics.fill(x + gap, y - half, x + gap + 1, y + half, LOCK);
        // And the mark is named: what was picked, and how far off the dish it
        // lies. The word stays inside the plot, so a mark on the very edge of
        // the picture still reads.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }
        String label = lockLabel(panel, locked);
        int textX = Mth.clamp(
                x + gap + 1 + LOCK_LABEL_GAP,
                plotLeft,
                Math.max(plotLeft, plotRight - minecraft.font.width(label)));
        int textY = Math.min(y + half + 1 + LOCK_LABEL_GAP, plotBottom - 9);
        graphics.drawString(minecraft.font, label, textX, textY, LOCK_TEXT, false);
    }

    /**
     * How big a mark is drawn, which is what the bars of a pick are measured
     * against: a ping sizes a hull by the echo that came back off her, a
     * creature and a warhead keep the shape the picture gives them whatever the
     * echo, and a set that is only listening knows one size for everything.
     */
    private static int markSide(SonarHudState.Mark mark) {
        return switch (mark.kind()) {
            case MOB, AMMO, MISSILE, TORPEDO -> SHAPE_SIDE;
            default -> MARK_SIDE + mark.echo() * ECHO_SIDE;
        };
    }

    /**
     * The size the picked mark is drawn at, taken off the picture it was picked
     * from: that contact's own size while it is still drawn, and the smallest
     * mark there is once it is gone.
     */
    private static int lockMarkSide(SonarHudState.Panel panel, Vec3 locked) {
        SonarHudState.Mark mark = lockMark(panel, locked);
        if (mark == null || !panel.pinging()) {
            return MARK_SIDE;
        }
        return markSide(mark);
    }

    /** The mark the pick stands on, while the picture still draws one there. */
    private static SonarHudState.Mark lockMark(SonarHudState.Panel panel, Vec3 locked) {
        SonarHudState.Mark best = null;
        double closest = LOCK_MARK_RANGE * LOCK_MARK_RANGE;
        for (SonarHudState.Mark mark : panel.marks()) {
            double distance = mark.position().distanceToSqr(locked);
            if (distance <= closest) {
                closest = distance;
                best = mark;
            }
        }
        return best;
    }

    /** The name of a picked mark: what it is, then how far off the dish it lies. */
    private static String lockLabel(SonarHudState.Panel panel, Vec3 locked) {
        Vec3 dish = panel.origin();
        long range = dish == null ? 0L : Math.round(dish.distanceTo(locked));
        return markName(SonarHudState.lockKind()) + " " + range + "m";
    }

    /**
     * What a picked mark is called: the same three things the picture tells
     * apart, and the words the action bar names a pick with.
     */
    private static String markName(SonarKind kind) {
        return Component.translatable("gui.firecontrolcompat.sonar_mark." + switch (kind == null ? SonarKind.BODY : kind) {
            case MOB -> "mob";
            case AMMO, MISSILE, TORPEDO -> "ammo";
            default -> "body";
        }).getString();
    }

    /** A mark that names nothing: a square in the colour of the sound it stands for. */
    private static void drawSound(GuiGraphics graphics, int x, int y, int colour) {
        graphics.fill(x - 2, y - 2, x + 2, y + 2, colour);
    }

    /**
     * One mark, drawn by what it is: a creature is a cross, ordnance a diamond,
     * a hull a square whose side is the echo that came back off her, and anything
     * else in the water keeps the plain square its weight asks for. The sound a
     * colour stands for is the mark's own weight.
     */
    private static void drawShape(GuiGraphics graphics, SonarKind kind, int x, int y, int square, int colour) {
        switch (kind) {
            case MOB -> {
                graphics.fill(x - 2, y - 1, x + 3, y + 2, colour);
                graphics.fill(x - 1, y - 2, x + 2, y + 3, colour);
            }
            case AMMO, MISSILE, TORPEDO -> {
                graphics.fill(x, y - 2, x + 1, y - 1, colour);
                graphics.fill(x - 1, y - 1, x + 2, y, colour);
                graphics.fill(x - 2, y, x + 3, y + 1, colour);
                graphics.fill(x - 1, y + 1, x + 2, y + 2, colour);
                graphics.fill(x, y + 2, x + 1, y + 3, colour);
            }
            default -> graphics.fill(x - square / 2, y - square / 2,
                    x - square / 2 + square, y - square / 2 + square, colour);
        }
    }

    /** The word the picture names a mark with. */
    private static String markKey(SonarKind kind) {
        return switch (kind) {
            case MOB -> "gui.firecontrolcompat.sonar_mark.mob";
            case AMMO -> "gui.firecontrolcompat.sonar_mark.ammo";
            default -> "gui.firecontrolcompat.sonar_mark.body";
        };
    }

    /**
     * The line that follows the operator's own view.
     *
     * <p>A set reports everything off its own bow, and the one direction the
     * player sitting at the station cannot see on the picture is their own: this
     * puts it there. It is the column of the plot their heading falls in, drawn
     * up from the marker that stands for the dish, in the same green as that
     * marker - so whatever is straight ahead of the player is on the same column
     * as the line. A view turned outside the window the set listens through has
     * no column to draw, and leaves the picture alone.</p>
     */
    private static void drawSightline(
            GuiGraphics graphics,
            Minecraft minecraft,
            SonarHudState.Panel panel,
            int plotLeft,
            int plotTop,
            int plotRight,
            int plotBottom) {
        if (minecraft.player == null) {
            return;
        }
        Vec3 look = minecraft.player.getViewVector(1.0F);
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return;
        }
        flat = flat.normalize();
        Plot plot = Plot.of(panel, plotLeft, plotTop, plotRight, plotBottom);
        double azimuth = Math.toDegrees(Math.atan2(flat.dot(plot.right()), flat.dot(plot.forward())));
        if (Math.abs(azimuth) > plot.halfAngle()) {
            return;
        }
        int x = plot.centreX() + (int) Math.round(azimuth / plot.halfAngle() * plot.halfWidth());
        graphics.fill(x, plot.bottom() - plot.height(), x + 1, plot.bottom(), OWN);
    }

    /**
     * Where a set's marks lie on its picture: the bow points up the plot and the
     * window it listens through is the width of it, so a mark is placed by how
     * far off the bow it is and how far away it is. A mark the set cannot hear -
     * behind it, outside its window or past its range - is left out.
     */
    private record Plot(
            Vec3 origin,
            Vec3 forward,
            Vec3 right,
            double halfAngle,
            double range,
            int centreX,
            int halfWidth,
            int height,
            int bottom) {

        static Plot of(SonarHudState.Panel panel, int plotLeft, int plotTop, int plotRight, int plotBottom) {
            Vec3 heading = panel.forward();
            Vec3 forward = new Vec3(heading.x, 0.0D, heading.z);
            if (forward.lengthSqr() < 1.0E-6D) {
                forward = new Vec3(0.0D, 0.0D, -1.0D);
            }
            forward = forward.normalize();
            return new Plot(
                    panel.origin(),
                    forward,
                    new Vec3(-forward.z, 0.0D, forward.x),
                    Math.max(1.0D, panel.halfAngle()),
                    Math.max(1.0D, panel.range()),
                    (plotLeft + plotRight) / 2,
                    Math.max(1, (plotRight - plotLeft) / 2),
                    Math.max(1, plotBottom - plotTop),
                    plotBottom);
        }

        /** The pixel a point in the water is drawn at, or null when the set cannot hear it. */
        int[] of(Vec3 position) {
            Vec3 delta = position.subtract(this.origin);
            double longitudinal = delta.dot(this.forward);
            double lateral = delta.dot(this.right);
            double distance = Math.hypot(longitudinal, lateral);
            if (distance > this.range) {
                return null;
            }
            if (longitudinal <= 0.0D && this.halfAngle < 180.0D) {
                return null;
            }
            double azimuth = Math.toDegrees(Math.atan2(lateral, longitudinal));
            if (Math.abs(azimuth) > this.halfAngle) {
                return null;
            }
            return new int[] {
                    this.centreX + (int) Math.round(azimuth / this.halfAngle * this.halfWidth),
                    this.bottom - (int) Math.round(distance / this.range * this.height)};
        }
    }
}
