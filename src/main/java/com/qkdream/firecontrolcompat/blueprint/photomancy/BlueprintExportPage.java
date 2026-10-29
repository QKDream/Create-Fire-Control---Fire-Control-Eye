package com.qkdream.firecontrolcompat.blueprint.photomancy;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.qkdream.firecontrolcompat.blueprint.BlueprintBake;
import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;
import com.qkdream.firecontrolcompat.blueprint.RegionExport;
import com.qkdream.firecontrolcompat.blueprint.RegionSelection;
import dev.rew1nd.sableschematicapi.tool.client.session.BlueprintToolClientSession;
import dev.rew1nd.sableschematicapi.tool.client.storage.BlueprintToolLocalFiles;
import dev.rew1nd.sableschematicapi.tool.ui.BlueprintToolUiKit;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * The export page on the blueprint tool.
 *
 * <p>Mirrors the library page's shape - a header, a row of actions, the local
 * blueprint list, and a status line - because it is the same job with a different
 * verb, and a page that looked different for no reason would just be confusing.
 *
 * <p>Reading the blueprint goes through the tool's own storage rather than scanning
 * the filesystem: the tool already knows where its blueprints are, and it hands
 * over the same bytes it places from.
 */
public final class BlueprintExportPage {

    /** The hover text of the primary button. */
    private static final String REGION_TOOLTIP =
        "用蓝图工具（或 /sableexport select 左键两次）划好选区后点击，导出为 OBJ（含子关卡/实体）";

    /** What a player who never drew a selection is told. */
    private static final String NO_SELECTION =
        "请先用蓝图工具或 /sableexport select 划出选区";

    /** The name stem a region export gets when the player did not type one. */
    private static final java.time.format.DateTimeFormatter REGION_STAMP =
        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private BlueprintExportPage() {}

    public static UIElement create(Player player) {
        UIElement page = new UIElement()
            .setId("sable_blueprint_export_page")
            .layout(layout -> {
                layout.positionType(TaffyPosition.ABSOLUTE);
                layout.left(0.0F);
                layout.top(0.0F);
                layout.width(360.0F);
                layout.height(224.0F);
                layout.flexDirection(FlexDirection.COLUMN);
                layout.alignItems(AlignItems.CENTER);
                layout.gapAll(4.0F);
            });

        // TEMP-DISABLED(blueprint-export): the saved-blueprint buttons only exist while the config
        // switch is on, so the page can not offer a path that has been turned off.
        boolean blueprintEnabled = BlueprintBake.blueprintExportEnabled();

        // The primary action, first in the row where the .nbt export used to start: it reads the
        // live world, so it is the export that produces the tracks, bearings and textures the saved
        // file cannot carry.
        Button exportRegion = BlueprintToolUiKit.textButton(Component.literal("选区导出"));
        exportRegion.layout(layout -> {
            layout.width(88.0F);
            layout.height(16.0F);
        });
        exportRegion.style(style -> style.tooltips(Component.literal(REGION_TOOLTIP)));
        exportRegion.setOnClick(event -> exportRegion(player));

        Button exportOne = BlueprintToolUiKit.iconButton(
            com.lowdragmc.lowdraglib2.gui.texture.Icons.EXPORT,
            BlueprintToolUiKit.tr("ui.export_selected"));
        exportOne.layout(layout -> {
            layout.width(18.0F);
            layout.height(16.0F);
        });
        exportOne.setOnClick(event -> exportSelected(player));

        Button exportAll = BlueprintToolUiKit.textButton(
            BlueprintToolUiKit.tr("ui.export_all"));
        exportAll.layout(layout -> {
            layout.width(96.0F);
            layout.height(16.0F);
        });
        exportAll.setOnClick(event -> exportAll(player));

        Button reveal = BlueprintToolUiKit.iconButton(
            com.lowdragmc.lowdraglib2.gui.texture.Icons.FOLDER,
            BlueprintToolUiKit.tr("ui.open_output"));
        reveal.layout(layout -> {
            layout.width(18.0F);
            layout.height(16.0F);
        });
        reveal.setOnClick(event -> openOutput(player));

        UIElement toolbar = new UIElement().layout(layout -> {
            layout.width(360.0F);
            layout.height(16.0F);
            layout.flexDirection(FlexDirection.ROW);
            layout.gapAll(4.0F);
        });
        toolbar.addChildren(exportRegion);
        if (blueprintEnabled) {
            toolbar.addChildren(exportOne, exportAll);
        }
        toolbar.addChildren(reveal);

        // With the .nbt path off there is nothing for a blueprint list to feed, so the space it
        // took goes to the hint that names the gesture which does work.
        UIElement body = blueprintEnabled
            ? BlueprintList.create(player, 360.0F, 132.0F)
            : disabledNotice();

        page.addChildren(
            BlueprintToolUiKit.modeHeader(component("mode.export")),
            toolbar,
            BlueprintToolUiKit.boundLabel(BlueprintToolClientSession::selectionLabel),
            body,
            BlueprintToolUiKit.boundLabel(BlueprintToolClientSession::status),
            hint()
        );
        return page;
    }

    /**
     * What takes the blueprint list's place while the saved-blueprint path is off.
     *
     * <p>Short lines rather than {@link BlueprintExporter#DISABLED_TEXT} in one piece: this page is
     * 360 wide, and a label does not wrap, so the sentence is split where it would otherwise run
     * past the edge. The wording is the command's - the same order, the same words - only broken.
     */
    private static UIElement disabledNotice() {
        UIElement notice = new UIElement().layout(layout -> {
            layout.width(360.0F);
            layout.height(132.0F);
            layout.flexDirection(FlexDirection.COLUMN);
            layout.alignItems(AlignItems.CENTER);
            layout.gapAll(4.0F);
        });
        notice.addChildren(
            line("本地蓝图导出已停用", net.minecraft.ChatFormatting.RED),
            line("请用 /sableexport select 划选区", net.minecraft.ChatFormatting.GRAY),
            line("再点下方「选区导出」按钮", net.minecraft.ChatFormatting.GRAY),
            line("（/sableexport region <名字> 效果相同）", net.minecraft.ChatFormatting.DARK_GRAY));
        return notice;
    }

    /** One centred, wrapped line of the notice. */
    private static Label line(String text, net.minecraft.ChatFormatting colour) {
        Label label = BlueprintToolUiKit.bareLabel(Horizontal.CENTER);
        label.setText(Component.literal(text).withStyle(colour));
        label.layout(layout -> {
            layout.width(352.0F);
            layout.height(20.0F);
        });
        return label;
    }

    private static Label hint() {
        Label label = BlueprintToolUiKit.bareLabel(Horizontal.LEFT);
        label.setText(Component.translatable("sableblueprintexporter.page.hint"));
        label.layout(layout -> {
            layout.width(360.0F);
            layout.height(28.0F);
        });
        return label;
    }

    private static Component component(String key, Object... args) {
        return Component.translatable("sableblueprintexporter." + key, args);
    }

    // ----------------------------------------------------------------- actions

    /**
     * Exports the region the player drew with the blueprint tool, through {@link RegionExport#run}.
     *
     * <p>The tool is where the selection comes from in the first place, so this page is the most
     * natural place to spend it: the player drew the box while holding this item, and the button is
     * one click away from the list they were about to use.
     */
    private static void exportRegion(Player player) {
        if (RegionSelection.read() == null) {
            // Shorter than RegionExport's own refusal on purpose: that one names the full
            // two-left-click gesture for a typed command, this one is read next to the button.
            BlueprintExporter.LOGGER.info("region: refused reason=no-selection by=gui");
            message(player, ChatFormatting.RED, NO_SELECTION);
            return;
        }
        // Close first, like the saved-blueprint path: the bake needs baked models and therefore
        // blocks the render thread, so the page must not sit frozen over it.
        net.minecraft.client.Minecraft.getInstance().setScreen(null);
        String name = "region_" + java.time.LocalDateTime.now().format(REGION_STAMP);
        int result;
        try {
            result = RegionExport.run(BlueprintExporter.chatSource("选区导出"), name);
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.error("region export from the blueprint tool page failed", t);
            message(player, ChatFormatting.RED, "选区导出失败：" + t);
            result = 0;
        }
        BlueprintExporter.LOGGER.info("region export: source=gui-page name={} result={}",
            name, result);
    }

    private static void exportSelected(Player player) {
        // TEMP-DISABLED(blueprint-export): the button is not created while the switch is off; this
        // guard covers a config value changed under a page that is already open.
        if (!BlueprintBake.blueprintExportEnabled()) {
            BlueprintExporter.logBlueprintExportDisabled("gui");
            message(player, ChatFormatting.RED, BlueprintExporter.DISABLED_TEXT);
            return;
        }
        BlueprintToolLocalFiles.Entry entry = BlueprintToolClientSession.selectedBlueprint();
        if (entry == null) {
            BlueprintToolClientSession.setStatusKey("select_file");
            BlueprintToolClientSession.notifyStatus(player, ChatFormatting.YELLOW);
            return;
        }
        runExport(player, List.of(entry));
    }

    private static void exportAll(Player player) {
        if (!BlueprintBake.blueprintExportEnabled()) {
            BlueprintExporter.logBlueprintExportDisabled("gui");
            message(player, ChatFormatting.RED, BlueprintExporter.DISABLED_TEXT);
            return;
        }
        List<BlueprintToolLocalFiles.Entry> all = BlueprintToolClientSession.localBlueprints();
        if (all.isEmpty()) {
            BlueprintToolClientSession.setStatusKey("no_local_schematics");
            BlueprintToolClientSession.notifyStatus(player, ChatFormatting.YELLOW);
            return;
        }
        runExport(player, all);
    }

    private static void openOutput(Player player) {
        try {
            net.minecraft.Util.getPlatform().openFile(BlueprintBake.outputDir().toFile());
        } catch (Throwable t) {
            BlueprintToolClientSession.setStatusKey("status.read_failed",
                                                    String.valueOf(t));
            BlueprintToolClientSession.notifyStatus(player, ChatFormatting.RED);
        }
    }

    /**
     * Bake every requested blueprint, reporting one line per file in chat.
     *
     * <p>Runs on the render thread because the bake needs baked models, so this
     * blocks for as long as it takes; the page is closed first so the game does not
     * sit on a frozen UI pretending to still be responsive.
     */
    private static void runExport(Player player, List<BlueprintToolLocalFiles.Entry> entries) {
        // TEMP-DISABLED(blueprint-export): the funnel both .nbt buttons go through, so the gate is
        // here as well as on the buttons themselves - no caller reaches the bake past it.
        if (!BlueprintBake.blueprintExportEnabled()) {
            BlueprintExporter.logBlueprintExportDisabled("gui");
            message(player, ChatFormatting.RED, BlueprintExporter.DISABLED_TEXT);
            return;
        }
        net.minecraft.client.Minecraft.getInstance().setScreen(null);

        int ok = 0;
        for (BlueprintToolLocalFiles.Entry entry : entries) {
            try {
                int tris = exportOne(entry);
                ok++;
                message(player, ChatFormatting.GREEN,
                        entry.name() + " -> " + entry.name() + ".obj (" + tris + " triangles)");
            } catch (Throwable t) {
                BlueprintExporter.LOGGER.error("export failed for {}", entry.name(), t);
                message(player, ChatFormatting.RED,
                        entry.name() + " - " + t);
            }
        }
        message(player, ChatFormatting.AQUA,
                "exported " + ok + "/" + entries.size() + " to " + BlueprintBake.outputDirSafe());
    }

    private static int exportOne(BlueprintToolLocalFiles.Entry entry) throws Exception {
        byte[] data = BlueprintToolLocalFiles.read(entry);
        CompoundTag root;
        try (var in = new ByteArrayInputStream(data)) {
            root = NbtIo.readCompressed(in, NbtAccounter.create(512L * 1024 * 1024));
        }
        return BlueprintBake.bake(root, entry.name());
    }

    private static void message(Player player, ChatFormatting colour, String text) {
        player.displayClientMessage(Component.literal("[Sable] " + text)
                                    .withStyle(colour), false);
    }

    // -------------------------------------------------------------------- list

    /**
     * The local blueprint list, with select-on-left-click.
     *
     * <p>Deliberately a near-copy of the library page's list rather than a shared
     * widget: that one is package-private inside the blueprint tool, and reaching
     * into it would tie this page to an internal class that can move.
     */
    static final class BlueprintList extends ScrollerView {
        private final Player player;
        private final float rowWidth;
        private int revision = Integer.MIN_VALUE;

        private BlueprintList(Player player, float width) {
            this.player = player;
            this.rowWidth = width - 12.0F;
            refreshIfNeeded();
        }

        static ScrollerView create(Player player, float width, float height) {
            BlueprintList scroller = new BlueprintList(player, width);
            scroller.setId("sable_blueprint_export_list");
            scroller.layout(layout -> {
                layout.width(width);
                layout.height(height);
            });
            scroller.style(style -> style.backgroundTexture(BlueprintToolUiKit.displayTexture()));
            scroller.viewContainer(container -> container.layout(layout -> {
                layout.width(width - 10.0F);
                layout.heightAuto();
                layout.flexDirection(FlexDirection.COLUMN);
                layout.gapAll(2.0F);
            }));
            return scroller;
        }

        @Override
        public void screenTick() {
            super.screenTick();
            refreshIfNeeded();
        }

        private void refreshIfNeeded() {
            int current = BlueprintToolClientSession.localBlueprintRevision();
            if (current != revision) {
                revision = current;
                rebuild();
            }
        }

        private void rebuild() {
            clearAllScrollViewChildren();
            List<BlueprintToolLocalFiles.Entry> entries =
                BlueprintToolClientSession.localBlueprints();
            if (entries.isEmpty()) {
                Label label = BlueprintToolUiKit.bareLabel(Horizontal.LEFT);
                label.setText(BlueprintToolUiKit.tr("ui.no_local_schematics"));
                label.layout(layout -> {
                    layout.width(rowWidth);
                    layout.height(16.0F);
                });
                addScrollViewChild(label);
                return;
            }
            for (BlueprintToolLocalFiles.Entry entry : entries) {
                addScrollViewChild(row(entry));
            }
        }

        private UIElement row(BlueprintToolLocalFiles.Entry entry) {
            Button row = BlueprintToolUiKit.textButton(Component.literal(entry.name()));
            row.layout(layout -> {
                layout.width(rowWidth);
                layout.height(16.0F);
            });
            row.addEventListener("mouseDown", event -> {
                if (event.button == 0) {
                    BlueprintToolClientSession.selectBlueprint(entry);
                    BlueprintToolClientSession.notifyStatus(player, ChatFormatting.AQUA);
                    event.stopPropagation();
                }
            });
            return row;
        }
    }
}
