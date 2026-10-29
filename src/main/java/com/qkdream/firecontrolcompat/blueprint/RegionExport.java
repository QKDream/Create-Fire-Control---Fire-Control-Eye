package com.qkdream.firecontrolcompat.blueprint;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * {@code /sableexport region <name>}: export the area the blueprint tool has selected.
 *
 * <p>The blueprint path reads a saved file and rebuilds a structure from it, and every part that
 * file does not carry has to be guessed at later - which is why tracks, tyres and sub-level
 * assembly came out wrong. This path reads the world instead: the selection is the one the Sable
 * blueprint tool is already showing ({@link RegionSelection}), and the blocks are the ones the
 * client is drawing, with their real block entities.
 *
 * <p>Deliberately one command. There is no {@code pos1}/{@code pos2} here: the selection is "the
 * region the player drew", and a second way to draw one would disagree with the first. The tool's
 * own library mode is unreachable in the pack this ships with, so the same two-left-click gesture
 * is offered on this mod's own state instead ({@code /sableexport select}), and
 * {@link RegionSelection} decides which of the two recorded the region.
 */
public final class RegionExport {

    private RegionExport() {
    }

    /**
     * Runs the export, answering in chat and in the log rather than by throwing.
     *
     * @param source the command source, so a refusal reaches the player
     * @param name   file name stem of the export
     */
    public static int run(CommandSourceStack source, String name) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc == null ? null : mc.level;
        if (level == null) {
            BlueprintExporter.LOGGER.info("region: refused reason=no-world");
            source.sendFailure(Component.literal("region: 先进世界（no world loaded）"));
            return 0;
        }

        RegionSelection.Box selection = RegionSelection.read();
        if (selection == null) {
            // The one case the player has to act on, so it names the gesture instead of reporting
            // an empty box.
            BlueprintExporter.LOGGER.info(
                "region: refused reason=no-selection tool-present={} class={}",
                RegionSelection.available(), RegionSelection.SESSION_CLASS);
            source.sendFailure(Component.literal(
                "region: 没有选区 —— 先 /sableexport select 再左键点两个对角，"
                    + "或用蓝图工具 library 模式左键点两个对角（Shift+左键清空）"));
            return 0;
        }
        if (!selection.bothCorners()) {
            BlueprintExporter.LOGGER.info(
                "region: refused reason=one-corner aabb={} source={}",
                selection.describe(), selection.source());
            source.sendFailure(Component.literal(
                "region: 只划了一个角点（" + selection.describe() + "）—— 再左键点对角"));
            return 0;
        }

        long volume = selection.volume();
        int limit = BlueprintBake.regionMaxBlocks();
        if (volume > limit) {
            BlueprintExporter.LOGGER.info(
                "region: refused reason=too-large aabb={} volume={} limit={}",
                selection.describe(), volume, limit);
            source.sendFailure(Component.literal("region: 选区太大 " + volume + " 格 > 上限 "
                + limit + "（config/firecontrolcompat-pod.txt 的 regionMaxBlocks）"));
            return 0;
        }

        try {
            // Anything collected by a previous export is not about this one, and the notes are
            // echoed into chat below.
            BlueprintExporter.NOTES.clear();
            int faces = BlueprintBake.bakeRegion(name, selection.aabb(), selection.source());
            String obj = BlueprintBake.outputDir().resolve(name + ".obj").toString();
            source.sendSuccess(() -> Component.literal(
                "sableexport region: " + obj + " (" + faces + " faces)"), false);
            java.util.List<String> notes = new java.util.ArrayList<>(BlueprintExporter.NOTES);
            for (int i = 0; i < Math.min(notes.size(), 4); i++) {
                String note = notes.get(i);
                source.sendSuccess(() -> Component.literal("  note: " + note), false);
            }
            return 1;
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.error("sableexport region failed", t);
            source.sendFailure(Component.literal("sableexport region failed: " + t));
            return 0;
        }
    }
}
