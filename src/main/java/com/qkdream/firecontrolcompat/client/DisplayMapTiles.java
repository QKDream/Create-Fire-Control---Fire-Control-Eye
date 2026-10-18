package com.qkdream.firecontrolcompat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The fire control mod's own map picture, borrowed for the strike terminal.
 *
 * <p>The pack already draws a map on its connected displays, and that path reads Xaero's own
 * cache rather than asking Xaero for a live canvas - which is exactly what a terminal needs,
 * because the canvas a minimap hands out only covers the strip the HUD is drawing at that
 * moment. Reusing the pack's tiles therefore gives the terminal the same picture its displays
 * show, with the same cache reading, instead of a second implementation of Xaero's format.</p>
 */
public final class DisplayMapTiles {

    /** The pack's map compatibility class, reached by name because the pack is optional. */
    private static final String COMPAT = "com.hooya.stabilizedturret.client.XaeroDisplayMapCompat";

    private static boolean resolved;
    private static Method consoleMapTiles;
    private static Method releaseConsoleTexture;
    private static Method tileId;
    private static Method tileLocation;
    private static Method tileMinX;
    private static Method tileMinZ;
    private static Method tileSize;
    private static boolean sawTiles;
    private static String status = "not looked yet";

    private DisplayMapTiles() {
    }

    private static boolean hook() {
        if (resolved) {
            return consoleMapTiles != null;
        }
        resolved = true;
        try {
            Class<?> compat = Class.forName(COMPAT);
            Class<?> tile = Class.forName(COMPAT + "$ConsoleMapTile");
            consoleMapTiles = compat.getMethod("consoleMapTiles", Object.class, Vec3.class,
                    double.class, double.class, int.class, int.class);
            releaseConsoleTexture = compat.getMethod("releaseConsoleTexture", Object.class);
            tileId = tile.getMethod("textureId");
            tileLocation = tile.getMethod("textureLocation");
            tileMinX = tile.getMethod("minX");
            tileMinZ = tile.getMethod("minZ");
            tileSize = tile.getMethod("size");
            status = "hooked";
        } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException | LinkageError failure) {
            consoleMapTiles = null;
            status = "no pack map: " + failure;
        }
        return consoleMapTiles != null;
    }

    /** Whether the pack's map picture can be used at all. */
    public static boolean available() {
        return hook();
    }

    /** Says what happened, for the log the terminal already writes. */
    public static String status() {
        return status;
    }

    /**
     * Draws the pack's map tiles over the board.
     *
     * @return whether anything was drawn, so the caller can fall back to sampling blocks
     */
    public static boolean draw(GuiGraphics graphics, Object owner, Vec3 centre,
            double halfSpanX, double halfSpanZ, int left, int top, int size) {
        if (!hook()) {
            return false;
        }
        List<?> tiles;
        try {
            Object value = consoleMapTiles.invoke(null, owner, centre, halfSpanX, halfSpanZ, size, size);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                status = "no tiles";
                return false;
            }
            tiles = list;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            status = "tiles failed: " + failure;
            return false;
        }
        boolean drew = false;
        for (Object tile : tiles) {
            try {
                int tileTexture = (Integer) tileId.invoke(tile);
                ResourceLocation location = (ResourceLocation) tileLocation.invoke(tile);
                int minX = (Integer) tileMinX.invoke(tile);
                int minZ = (Integer) tileMinZ.invoke(tile);
                int tileBlocks = (Integer) tileSize.invoke(tile);
                int x1 = left + (int) Math.round((minX - (centre.x - halfSpanX)) / (halfSpanX * 2.0D) * size);
                int z1 = top + (int) Math.round((minZ - (centre.z - halfSpanZ)) / (halfSpanZ * 2.0D) * size);
                int x2 = left + (int) Math.round((minX + tileBlocks - (centre.x - halfSpanX))
                        / (halfSpanX * 2.0D) * size);
                int z2 = top + (int) Math.round((minZ + tileBlocks - (centre.z - halfSpanZ))
                        / (halfSpanZ * 2.0D) * size);
                if (x2 - x1 < 1 || z2 - z1 < 1) {
                    continue;
                }
                if (location != null) {
                    graphics.blit(location, x1, z1, Math.max(1, x2 - x1), Math.max(1, z2 - z1),
                            0.0F, 0.0F, 512, 512, 512, 512);
                } else if (tileTexture > 0) {
                    textured(graphics, x1, z1, x2, z2, tileTexture);
                }
                drew = true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
        sawTiles = true;
        status = "tiles=" + tiles.size() + " drawn=" + drew;
        return drew;
    }

    /** Releases the pack's texture for this owner, so a closed terminal leaves nothing behind. */
    public static void release(Object owner) {
        if (!hook() || releaseConsoleTexture == null || !sawTiles) {
            return;
        }
        try {
            releaseConsoleTexture.invoke(null, owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    /** Draws a raw GL texture, the way the pack's own display does. */
    private static void textured(GuiGraphics graphics, int x1, int y1, int x2, int y2, int texture) {
        Matrix4f matrix = graphics.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x1, y2, 0.0F).setUv(0.0F, 1.0F);
        buffer.addVertex(matrix, x2, y2, 0.0F).setUv(1.0F, 1.0F);
        buffer.addVertex(matrix, x2, y1, 0.0F).setUv(1.0F, 0.0F);
        buffer.addVertex(matrix, x1, y1, 0.0F).setUv(0.0F, 0.0F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
