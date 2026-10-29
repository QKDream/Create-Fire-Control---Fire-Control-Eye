package com.qkdream.firecontrolcompat.blueprint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The blueprint export screen.
 *
 * <p>A visual front end for what {@code /sableexport} does from the command line.
 * Offering the blueprints as a list rather than making the user retype a file name
 * is the whole point: the folder layout differs per instance (a pack that redirects
 * its game directory keeps its schematics beside the version folders), so the user
 * generally does not know what to type.
 *
 * <p>Exporting is deliberately synchronous. Baking needs baked models, which exist
 * only on the client render thread, so it cannot be moved to a worker without
 * breaking the export. The screen closes on the way in and reports the result in
 * chat, which is honest about the freeze instead of pretending to be async.
 *
 * <p>TEMP-DISABLED(blueprint-export): the primary button is now {@code 选区导出}
 * ({@link RegionExport}), which reads the live world, and it sits where
 * {@code Export selected} used to. While {@link BlueprintBake#blueprintExportEnabled()}
 * is false - the shipped default - the two saved-blueprint buttons are not created at
 * all and the list that would feed them is not shown; the sentence naming the gesture
 * that does work is drawn in their place. The {@code .nbt} buttons come back, in this
 * same layout, the moment the switch is turned on.
 */
public class BlueprintExportScreen extends Screen {

    /** The name stem a region export gets when the player did not type one. */
    private static final DateTimeFormatter REGION_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** What a player who never drew a selection is told, in chat and on this screen. */
    private static final String NO_SELECTION =
        "请先用蓝图工具或 /sableexport select 划出选区";

    /** The hover text of the primary button, also spelled out in the page's hint line. */
    private static final String REGION_TOOLTIP =
        "用蓝图工具（或 /sableexport select 左键两次）划好选区后点击，导出为 OBJ（含子关卡/实体）";

    private final List<BlueprintBake.Candidate> candidates = new ArrayList<>();
    private BlueprintList list;
    private String status = "";

    public BlueprintExportScreen() {
        super(Component.literal("Sable Blueprint Export"));
    }

    @Override
    protected void init() {
        candidates.clear();
        candidates.addAll(BlueprintBake.listBlueprints());

        // TEMP-DISABLED(blueprint-export): one switch decides which buttons exist, so a player can
        // never reach a path the config has turned off - the buttons are absent, not merely refused.
        boolean blueprintEnabled = BlueprintBake.blueprintExportEnabled();

        // Three rows when the saved-blueprint buttons are back, two when only the region export is
        // offered. The list is shorter in the first case by exactly the row the extra button needs.
        int bottom = height - (blueprintEnabled ? 76 : 52);
        int listHeight = Math.max(40, bottom - 8 - 44);

        if (blueprintEnabled) {
            list = new BlueprintList(minecraft, width, listHeight, 44, 24);
            for (BlueprintBake.Candidate candidate : candidates) {
                list.add(new BlueprintList.Entry(candidate));
            }
            addRenderableWidget(list);
        }

        // The primary action, in the slot "Export selected" used to occupy: it is the one export a
        // player should reach for, so it is the one under the left thumb by default.
        addRenderableWidget(Button.builder(Component.literal("选区导出"),
                b -> exportRegion())
                .tooltip(Tooltip.create(Component.literal(REGION_TOOLTIP)))
                .bounds(width / 2 - 154, bottom, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Refresh"),
                b -> rebuildWidgets())
                .bounds(width / 2 + 4, bottom, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Open output folder"),
                b -> {
                    try {
                        Util.getPlatform().openFile(BlueprintBake.outputDir().toFile());
                    } catch (Exception e) {
                        status = "cannot open output folder: " + e;
                    }
                })
                .bounds(width / 2 - 154, bottom + 24, 150, 20).build());

        if (blueprintEnabled) {
            addRenderableWidget(Button.builder(Component.literal("Export all"),
                    b -> exportAll())
                    .bounds(width / 2 + 4, bottom + 24, 150, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Export selected"),
                    b -> exportSelected())
                    .bounds(width / 2 - 154, bottom + 48, 150, 20).build());
        }

        if (!blueprintEnabled) {
            // The banner in render() carries the notice, so the status line is left empty for the
            // result of the button the player actually clicks. Reset because init() runs again on
            // Refresh, and a stale refusal under a freshly built screen would be a lie.
            status = "";
        } else if (candidates.isEmpty()) {
            StringBuilder sb = new StringBuilder("No .nbt found. Searched:");
            for (Path dir : BlueprintBake.candidateDirs()) {
                sb.append(' ').append(dir);
            }
            status = sb.toString();
        } else {
            status = candidates.size() + " blueprint(s) found";
        }
    }

    /**
     * Exports the region the player drew, through {@link RegionExport#run}.
     *
     * <p>No selection is answered here rather than by {@code RegionExport}: its own refusal names
     * the two-left-click gesture in full, which is the right sentence for a command that was typed,
     * while a player looking at a button needs the shorter one that tells them what to do next. A
     * half-drawn selection, a box over the limit and a bake failure are all left to
     * {@code RegionExport}, whose chat lines already name those cases.
     */
    private void exportRegion() {
        Minecraft mc = Minecraft.getInstance();
        if (RegionSelection.read() == null) {
            BlueprintExporter.LOGGER.info("region: refused reason=no-selection by=gui");
            status = NO_SELECTION;
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§c" + NO_SELECTION), false);
            }
            return;
        }

        // Close first, for the same reason the blueprint path does: the bake needs baked models and
        // therefore blocks the render thread, and a frozen screen over it only makes the game look
        // hung. Chat carries the result instead of the screen that no longer exists.
        mc.setScreen(null);

        String name = "region_" + LocalDateTime.now().format(REGION_STAMP);
        int result;
        try {
            result = RegionExport.run(BlueprintExporter.chatSource("选区导出"), name);
        } catch (Throwable t) {
            // RegionExport answers in chat and in the log itself; this is only for the case where
            // something outside its own guards throws, so the player never gets a silent no-op.
            BlueprintExporter.LOGGER.error("region export from the export screen failed", t);
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.literal("§c选区导出失败：" + t), false);
            }
            result = 0;
        }
        BlueprintExporter.LOGGER.info("region export: source=gui-button name={} result={}",
            name, result);
    }

    private void exportSelected() {
        // TEMP-DISABLED(blueprint-export): belt and braces. The button is not created while the
        // switch is off, but the config is read per call, so a value changed under a screen that is
        // already open must still not reach the bake.
        if (!BlueprintBake.blueprintExportEnabled()) {
            BlueprintExporter.logBlueprintExportDisabled("gui");
            status = BlueprintExporter.DISABLED_TEXT;
            return;
        }
        BlueprintList.Entry chosen = list == null ? null : list.getSelected();
        if (chosen == null) {
            status = "Select a blueprint first";
            return;
        }
        runExport(List.of(chosen.candidate));
    }

    private void exportAll() {
        if (!BlueprintBake.blueprintExportEnabled()) {
            BlueprintExporter.logBlueprintExportDisabled("gui");
            status = BlueprintExporter.DISABLED_TEXT;
            return;
        }
        if (candidates.isEmpty()) {
            status = "Nothing to export";
            return;
        }
        runExport(new ArrayList<>(candidates));
    }

    private void runExport(List<BlueprintBake.Candidate> targets) {
        // TEMP-DISABLED(blueprint-export): the funnel every saved-blueprint export goes through, so
        // the gate is here as well as on the two buttons - no caller can reach bakeAll past it.
        if (!BlueprintBake.blueprintExportEnabled()) {
            BlueprintExporter.logBlueprintExportDisabled("gui");
            status = BlueprintExporter.DISABLED_TEXT;
            return;
        }
        // Close first: the bake blocks, and a frozen screen over it would only make
        // the game look hung. Chat gets the per-file result instead.
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);

        List<BlueprintBake.Outcome> outcomes = BlueprintBake.bakeAll(targets, null);

        int ok = 0;
        for (BlueprintBake.Outcome outcome : outcomes) {
            if (outcome.ok()) {
                ok++;
            }
        }
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                "[Sable] exported " + ok + "/" + outcomes.size() + " blueprint(s) to "
                + BlueprintBake.outputDirSafe()), false);
            for (BlueprintBake.Outcome outcome : outcomes) {
                mc.player.displayClientMessage(Component.literal(
                    "  " + (outcome.ok() ? "OK   " : "FAIL ") + outcome.name()
                    + " - " + outcome.detail()), false);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(font,
            Component.literal("Output: " + BlueprintBake.outputDirSafe()),
            width / 2, 26, 0xA0A0A0);

        if (!BlueprintBake.blueprintExportEnabled()) {
            // Where the .nbt list used to be: what replaced it, and how to use it.
            graphics.drawCenteredString(font,
                Component.literal(BlueprintExporter.DISABLED_TEXT), width / 2, 52, 0xFF5555);
            graphics.drawCenteredString(font,
                Component.literal(REGION_TOOLTIP), width / 2, 68, 0xA0A0A0);
        }

        if (!status.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(status),
                width / 2, height - 14, 0xE0E0E0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------- list

    /**
     * The blueprint list. Kept as a static nested class so it needs no enclosing
     * screen instance, which is what made an earlier non-static version fail to
     * compile against {@code AbstractSelectionList}'s generic entry type.
     */
    static class BlueprintList extends ObjectSelectionList<BlueprintList.Entry> {

        BlueprintList(Minecraft mc, int width, int height, int top, int itemHeight) {
            super(mc, width, height, top, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return 300;
        }

        /**
         * {@code AbstractSelectionList.addEntry} is protected, and the screen is not
         * a subclass, so it needs this to fill the list.
         */
        void add(Entry entry) {
            addEntry(entry);
        }

        static class Entry extends ObjectSelectionList.Entry<Entry> {
            final BlueprintBake.Candidate candidate;

            Entry(BlueprintBake.Candidate candidate) {
                this.candidate = candidate;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left,
                               int width, int height, int mouseX, int mouseY,
                               boolean hovering, float partialTick) {
                int colour = hovering ? 0xFFFF80 : 0xFFFFFF;
                graphics.drawString(Minecraft.getInstance().font, candidate.name,
                                    left + 4, top + 7, colour, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(candidate.name);
            }
        }
    }
}
