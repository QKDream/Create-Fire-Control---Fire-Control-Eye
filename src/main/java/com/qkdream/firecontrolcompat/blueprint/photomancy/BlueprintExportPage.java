package com.qkdream.firecontrolcompat.blueprint.photomancy;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.qkdream.firecontrolcompat.blueprint.BlueprintBake;
import com.qkdream.firecontrolcompat.blueprint.BlueprintExporter;
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
        }).addChildren(exportOne, exportAll, reveal);

        page.addChildren(
            BlueprintToolUiKit.modeHeader(component("mode.export")),
            toolbar,
            BlueprintToolUiKit.boundLabel(BlueprintToolClientSession::selectionLabel),
            BlueprintList.create(player, 360.0F, 132.0F),
            BlueprintToolUiKit.boundLabel(BlueprintToolClientSession::status),
            hint()
        );
        return page;
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

    private static void exportSelected(Player player) {
        BlueprintToolLocalFiles.Entry entry = BlueprintToolClientSession.selectedBlueprint();
        if (entry == null) {
            BlueprintToolClientSession.setStatusKey("select_file");
            BlueprintToolClientSession.notifyStatus(player, ChatFormatting.YELLOW);
            return;
        }
        runExport(player, List.of(entry));
    }

    private static void exportAll(Player player) {
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
