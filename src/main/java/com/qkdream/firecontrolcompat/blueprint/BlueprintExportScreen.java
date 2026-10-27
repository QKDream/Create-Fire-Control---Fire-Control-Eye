package com.qkdream.firecontrolcompat.blueprint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;

import java.nio.file.Path;
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
 */
public class BlueprintExportScreen extends Screen {

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

        int listHeight = Math.max(40, height - 44 - 60);
        list = new BlueprintList(minecraft, width, listHeight, 44, 24);
        for (BlueprintBake.Candidate candidate : candidates) {
            list.add(new BlueprintList.Entry(candidate));
        }
        addRenderableWidget(list);

        int bottom = height - 52;

        addRenderableWidget(Button.builder(Component.literal("Export selected"),
                b -> exportSelected())
                .bounds(width / 2 - 154, bottom, 150, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Export all"),
                b -> exportAll())
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

        addRenderableWidget(Button.builder(Component.literal("Refresh"),
                b -> rebuildWidgets())
                .bounds(width / 2 + 4, bottom + 24, 150, 20).build());

        if (candidates.isEmpty()) {
            StringBuilder sb = new StringBuilder("No .nbt found. Searched:");
            for (Path dir : BlueprintBake.candidateDirs()) {
                sb.append(' ').append(dir);
            }
            status = sb.toString();
        } else {
            status = candidates.size() + " blueprint(s) found";
        }
    }

    private void exportSelected() {
        BlueprintList.Entry chosen = list == null ? null : list.getSelected();
        if (chosen == null) {
            status = "Select a blueprint first";
            return;
        }
        runExport(List.of(chosen.candidate));
    }

    private void exportAll() {
        if (candidates.isEmpty()) {
            status = "Nothing to export";
            return;
        }
        runExport(new ArrayList<>(candidates));
    }

    private void runExport(List<BlueprintBake.Candidate> targets) {
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
