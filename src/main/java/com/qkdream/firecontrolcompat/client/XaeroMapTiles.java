package com.qkdream.firecontrolcompat.client;

import java.lang.reflect.Method;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Xaero's own map, on the strike board.
 *
 * <p>The board used to draw its picture from the blocks the game had loaded,
 * which is a picture of what the player can already see and nothing else. Xaero's
 * world map keeps a rendered tile for every piece of ground the player has been
 * to, explored ground included, so the board draws those tiles instead and falls
 * back to its own sampling only where Xaero has nothing.</p>
 *
 * <p>Xaero is not a dependency of this mod and is not on the compile classpath,
 * so the handful of its classes that are needed are reached by name. The tiles
 * are drawn through Xaero's own batcher and its own map shader - the tiles carry
 * their light in the colour - because a plain blit would show them washed out.
 * Nothing is created or loaded here: a piece of ground Xaero has not drawn is
 * simply left to the board's own sampling.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class XaeroMapTiles {

    /** One tile's worth of ground, in blocks, along one side. */
    public static final int TILE_BLOCKS = 64;
    /** How many tiles the board will draw before it gives up on Xaero's map. */
    private static final int TILE_LIMIT = 4096;

    private static boolean resolved;
    private static boolean usable;
    private static Method sessionGet;
    private static Method processorGet;
    private static Method leafMapRegion;
    private static Method regionChunk;
    private static Method chunkTexture;
    private static Method textureBind;
    private static Method processorTextures;
    private static Method getRenderer;
    private static Method providerDraw;
    private static Method drawWithLighting;
    private static Method defaultTextureBind;
    private static Object mapRenderType;

    /** What the last look at Xaero's map found, for the log. */
    private static String report = "not asked";

    private XaeroMapTiles() {
    }

    /** One tile of Xaero's map, placed on the board. */
    public record Tile(int glId, double left, double top, double right, double bottom) {
    }

    /**
     * Collects the tiles that cover the ground the board is looking at, each one
     * already placed where it belongs on the picture. An empty list means Xaero
     * has none of this ground and the board's own sampling stands.
     */
    public static java.util.List<Tile> collect(double centerX, double centerZ, double span,
            int left, int top, int size) {
        java.util.List<Tile> tiles = new java.util.ArrayList<>();
        if (!(span > 0.0D) || size <= 0) {
            report = "board has no room";
            return tiles;
        }
        if (!hook()) {
            report = "xaero classes missing";
            return tiles;
        }
        Object processor = processor();
        if (processor == null) {
            report = "no xaero session";
            return tiles;
        }
        int minChunk = Mth.floor((centerX - span / 2.0D) / TILE_BLOCKS);
        int maxChunk = Mth.floor((centerX + span / 2.0D) / TILE_BLOCKS);
        int minTileZ = Mth.floor((centerZ - span / 2.0D) / TILE_BLOCKS);
        int maxTileZ = Mth.floor((centerZ + span / 2.0D) / TILE_BLOCKS);
        long wanted = (long) (maxChunk - minChunk + 1) * (maxTileZ - minTileZ + 1);
        if (wanted <= 0L || wanted > TILE_LIMIT) {
            report = "too much ground for one look: " + wanted;
            return tiles;
        }
        int looked = 0;
        int found = 0;
        int[] counts = new int[3];
        double scale = size / span;
        double half = size / 2.0D;
        for (int tileX = minChunk; tileX <= maxChunk; tileX++) {
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                looked++;
                int glId = tileTexture(processor, tileX, tileZ, counts);
                if (glId <= 0) {
                    continue;
                }
                found++;
                double x0 = left + (tileX * (double) TILE_BLOCKS - centerX) * scale + half;
                double z0 = top + (tileZ * (double) TILE_BLOCKS - centerZ) * scale + half;
                double x1 = x0 + TILE_BLOCKS * scale;
                double z1 = z0 + TILE_BLOCKS * scale;
                tiles.add(new Tile(glId, x0, z0, x1, z1));
            }
        }
        report = "looked=" + looked + " regions=" + counts[0] + " chunks=" + counts[1]
                + " textures=" + counts[2] + " drawn=" + found
                + " span=" + (int) span + " at=" + (int) centerX + "," + (int) centerZ;
        return tiles;
    }

    /** What the last look at Xaero's map found. */
    public static String report() {
        return report;
    }

    /**
     * Opens a batch of tiles, drawn the way Xaero's own map draws them, or
     * answers null when that is not possible and the plain blit has to stand.
     */
    public static Object beginBatch() {
        if (!hook() || drawWithLighting == null || mapRenderType == null) {
            return null;
        }
        try {
            Object processor = processor();
            if (processor == null) {
                return null;
            }
            Object provider = processorTextures.invoke(processor);
            Object renderer = getRenderer.invoke(provider, shaderBinder(), rawBinder(), mapRenderType);
            return new Object[] { provider, renderer };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            report = report + " (no xaero batcher)";
            return null;
        }
    }

    /** Draws one tile into the batch, with Xaero's own shader. */
    public static void tile(GuiGraphics graphics, Object batch, Tile tile) {
        Object[] pair = (Object[]) batch;
        try {
            drawWithLighting.invoke(null, graphics.pose().last().pose(),
                    (float) tile.left(), (float) tile.top(),
                    (float) (tile.right() - tile.left()), (float) (tile.bottom() - tile.top()),
                    tile.glId(), false, pair[1]);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The batch is dropped by the caller; the tiles still stand.
        }
    }

    /** Draws the batch and hands Xaero its batcher back. */
    public static void endBatch(Object batch) {
        Object[] pair = (Object[]) batch;
        try {
            providerDraw.invoke(pair[0], pair[1]);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    /** Draws one tile with a plain blit. The caller has set up the pose already. */
    public static void drawPlain(GuiGraphics graphics, Tile tile) {
        com.mojang.blaze3d.systems.RenderSystem.setShader(
                net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, tile.glId());
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        org.joml.Matrix4f pose = graphics.pose().last().pose();
        com.mojang.blaze3d.vertex.BufferBuilder buffer = com.mojang.blaze3d.vertex.Tesselator.getInstance()
                .begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        float x0 = (float) tile.left();
        float y0 = (float) tile.top();
        float x1 = (float) tile.right();
        float y1 = (float) tile.bottom();
        buffer.addVertex(pose, x0, y1, 0.0F).setUv(0.0F, 1.0F);
        buffer.addVertex(pose, x1, y1, 0.0F).setUv(1.0F, 1.0F);
        buffer.addVertex(pose, x1, y0, 0.0F).setUv(1.0F, 0.0F);
        buffer.addVertex(pose, x0, y0, 0.0F).setUv(0.0F, 0.0F);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.buildOrThrow());
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** The texture Xaero keeps for one tile of ground, or -1 while it has none. */
    private static int tileTexture(Object processor, int tileX, int tileZ, int[] counts) {
        try {
            Object region = leafMapRegion.invoke(processor, 0, tileX >> 3, tileZ >> 3, false);
            if (region == null) {
                return -1;
            }
            counts[0]++;
            Object chunk = regionChunk.invoke(region, tileX & 7, tileZ & 7);
            if (chunk == null) {
                return -1;
            }
            counts[1]++;
            Object texture = chunkTexture.invoke(chunk);
            if (texture == null) {
                return -1;
            }
            counts[2]++;
            // The map's own draw binds the texture, which makes it if it is not
            // there yet; a texture still waiting for its pixels is left to the
            // board's own sampling for this turn.
            return (Integer) textureBind.invoke(texture, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    /** The map processor of the running world map session, or null without one. */
    private static Object processor() {
        try {
            Object session = sessionGet.invoke(null);
            return session == null ? null : processorGet.invoke(session);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static IntConsumer shaderBinder() {
        return id -> com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, id);
    }

    private static IntConsumer rawBinder() {
        return id -> {
            try {
                defaultTextureBind.invoke(null, id);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        };
    }

    /** Finds Xaero's map classes the first time they are asked for. */
    private static boolean hook() {
        if (resolved) {
            return usable;
        }
        resolved = true;
        try {
            Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
            Class<?> processorClass = Class.forName("xaero.map.MapProcessor");
            Class<?> regionClass = Class.forName("xaero.map.region.MapRegion");
            Class<?> chunkClass = Class.forName("xaero.map.region.MapTileChunk");
            Class<?> textureClass = Class.forName("xaero.map.region.texture.RegionTexture");
            sessionGet = sessionClass.getMethod("getCurrentSession");
            processorGet = sessionClass.getMethod("getMapProcessor");
            leafMapRegion = processorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            regionChunk = regionClass.getMethod("getChunk", int.class, int.class);
            chunkTexture = chunkClass.getMethod("getLeafTexture");
            textureBind = textureClass.getMethod("bindColorTexture", boolean.class);
            usable = true;
        } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException | LinkageError ignored) {
            usable = false;
            return false;
        }
        try {
            Class<?> processorClass = Class.forName("xaero.map.MapProcessor");
            Class<?> providerClass = Class.forName(
                    "xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider");
            Class<?> rendererClass = Class.forName(
                    "xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer");
            Class<?> renderTypesClass = Class.forName("xaero.map.graphics.CustomRenderTypes");
            Class<?> mapGuiClass = Class.forName("xaero.map.gui.GuiMap");
            processorTextures = processorClass.getMethod("getMultiTextureRenderTypeRenderers");
            getRenderer = providerClass.getMethod("getRenderer", IntConsumer.class, IntConsumer.class,
                    net.minecraft.client.renderer.RenderType.class);
            providerDraw = providerClass.getMethod("draw", rendererClass);
            defaultTextureBind = providerClass.getMethod("defaultTextureBind", int.class);
            mapRenderType = renderTypesClass.getField("MAP").get(null);
            drawWithLighting = mapGuiClass.getMethod("renderTexturedModalRectWithLighting3",
                    org.joml.Matrix4f.class, float.class, float.class, float.class, float.class,
                    int.class, boolean.class, rendererClass);
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                | IllegalAccessException | RuntimeException | LinkageError ignored) {
            processorTextures = null;
            getRenderer = null;
            providerDraw = null;
            defaultTextureBind = null;
            mapRenderType = null;
            drawWithLighting = null;
        }
        return true;
    }

    // ---------------------------------------------------------------- the corner map

    /** The minimap's own cached colours: the very pixels the corner map draws. */
    private static int minimapLogCountdown = 1;

    /** Says once in a while why the corner map could not be read. */
    private static boolean minimapFail(String why) {
        if (minimapLogCountdown-- <= 0) {
            minimapLogCountdown = 10;
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] minimap read failed: {}", why);
        }
        return false;
    }
    private static boolean minimapResolved;
    private static boolean minimapUsable;
    private static Object minimapWriterCache;
    private static java.lang.reflect.Field processorWriter;
    private static java.lang.reflect.Method loadedBlocks;
    private static java.lang.reflect.Method chunkCopy;
    private static java.lang.reflect.Method chunkX;
    private static java.lang.reflect.Method chunkZ;
    private static java.lang.reflect.Method chunkHas;
    private static java.lang.reflect.Method processorOf;

    private static boolean minimapHook() {
        if (minimapResolved) {
            return minimapUsable;
        }
        minimapResolved = true;
        try {
            Class<?> sessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Class<?> processorClass = Class.forName("xaero.common.minimap.MinimapProcessor");
            Class<?> writerClass = Class.forName("xaero.common.minimap.write.MinimapWriter");
            Class<?> chunkClass = Class.forName("xaero.common.minimap.region.MinimapChunk");
            processorOf = sessionClass.getMethod("getMinimapProcessor");
            processorWriter = processorClass.getDeclaredField("minimapWriter");
            processorWriter.setAccessible(true);
            loadedBlocks = writerClass.getMethod("getLoadedBlocks");
            chunkCopy = chunkClass.getMethod("copyBuffer", int.class, java.nio.IntBuffer.class);
            chunkX = chunkClass.getMethod("getX");
            chunkZ = chunkClass.getMethod("getZ");
            chunkHas = chunkClass.getMethod("isHasSomething");
            minimapUsable = true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                | RuntimeException | LinkageError failure) {
            minimapUsable = false;
            String fields = "?";
            try {
                Class<?> processorClass = Class.forName("xaero.common.minimap.MinimapProcessor");
                StringBuilder names = new StringBuilder();
                for (java.lang.reflect.Field field : processorClass.getDeclaredFields()) {
                    names.append(field.getName()).append(' ');
                }
                fields = names.toString();
            } catch (RuntimeException | LinkageError | ClassNotFoundException ignored) {
            }
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] minimap hook failed: {} {}", failure.toString(), fields);
        }
        if (minimapUsable) {
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] minimap hook ready");
        }
        return minimapUsable;
    }

    /**
     * Fills the board from the corner map's own cache.
     *
     * <p>This is the map a crew can actually see: it is fed by the minimap's writer as the
     * world streams in, so it is current wherever the minimap is, without the world map ever
     * having been opened. Its chunks are read through {@code copyBuffer}, which is the call the
     * minimap's own renderer makes, and the pixels are laid straight onto the board.</p>
     */
    public static boolean fillFromMinimap(double centreX, double centreZ, double span, int grid, int[] picture) {
        if (!minimapHook()) {
            return minimapFail("hook");
        }
        Object processor;
        try {
            Object session = Class.forName("xaero.common.XaeroMinimapSession")
                    .getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                return minimapFail("no session");
            }
            processor = processorOf.invoke(session);
            if (processor == null) {
                return minimapFail("no processor");
            }
        } catch (RuntimeException | ReflectiveOperationException | LinkageError failure) {
            return minimapFail("session call " + failure);
        }
        Object writer;
        try {
            writer = processorWriter.get(processor);
        } catch (IllegalAccessException | RuntimeException failure) {
            return minimapFail("writer field " + failure);
        }
        if (writer == null) {
            return minimapFail("writer null");
        }
        Object[][ ] blocks;
        try {
            blocks = (Object[][]) loadedBlocks.invoke(writer);
        } catch (RuntimeException | IllegalAccessException
                | java.lang.reflect.InvocationTargetException failure) {
            return minimapFail("loaded blocks " + failure);
        }
        if (blocks == null) {
            return minimapFail("blocks null");
        }
        java.nio.IntBuffer buffer = java.nio.ByteBuffer.allocateDirect(4096 * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        double cell = span / grid;
        int painted = 0;
        for (Object[] column : blocks) {
            if (column == null) {
                continue;
            }
            for (Object entry : column) {
                if (entry == null) {
                    continue;
                }
                try {
                    if (!((Boolean) chunkHas.invoke(entry))) {
                        continue;
                    }
                    int chunkBlockX = (Integer) chunkX.invoke(entry) * 64;
                    int chunkBlockZ = (Integer) chunkZ.invoke(entry) * 64;
                    if (chunkBlockX + 64 < centreX - span / 2.0D || chunkBlockX > centreX + span / 2.0D
                            || chunkBlockZ + 64 < centreZ - span / 2.0D || chunkBlockZ > centreZ + span / 2.0D) {
                        continue;
                    }
                    buffer.clear();
                    chunkCopy.invoke(entry, 0, buffer);
                    for (int row = 0; row < grid; row++) {
                        double worldZ = centreZ + (row + 0.5D - grid / 2.0D) * cell;
                        int pixelZ = (int) Math.floor(worldZ) - chunkBlockZ;
                        if (pixelZ < 0 || pixelZ >= 64) {
                            continue;
                        }
                        for (int column2 = 0; column2 < grid; column2++) {
                            double worldX = centreX + (column2 + 0.5D - grid / 2.0D) * cell;
                            int pixelX = (int) Math.floor(worldX) - chunkBlockX;
                            if (pixelX < 0 || pixelX >= 64) {
                                continue;
                            }
                            int colour = buffer.get(pixelZ * 64 + pixelX);
                            if ((colour >>> 24) != 0) {
                                picture[row * grid + column2] = colour | 0xFF000000;
                                painted++;
                            }
                        }
                    }
                } catch (RuntimeException | ReflectiveOperationException | LinkageError ignored) {
                }
            }
        }
        if (minimapLogCountdown-- <= 0) {
            minimapLogCountdown = 10;
            int rows = blocks.length;
            int nonNull = 0;
            int hasSomething = 0;
            String first = "none";
            for (Object[] column : blocks) {
                if (column == null) {
                    continue;
                }
                for (Object entry : column) {
                    if (entry == null) {
                        continue;
                    }
                    nonNull++;
                    try {
                        if ((Boolean) chunkHas.invoke(entry)) {
                            hasSomething++;
                            if ("none".equals(first)) {
                                first = "x=" + chunkX.invoke(entry) + " z=" + chunkZ.invoke(entry);
                            }
                        }
                    } catch (RuntimeException | ReflectiveOperationException | LinkageError ignored) {
                    }
                }
            }
            com.qkdream.firecontrolcompat.FireControlCompat.LOGGER.info(
                    "[firecontrolcompat] minimap read rows={} nonNull={} hasSomething={} painted={} first={}",
                    rows, nonNull, hasSomething, painted, first);
        }
        if (painted > 0) {
            report = "minimap chunks painted=" + painted;
        }
        return painted > 0;
    }
}