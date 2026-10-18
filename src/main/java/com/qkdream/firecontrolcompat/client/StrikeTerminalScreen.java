package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.StrikeTerminalItem;
import com.qkdream.firecontrolcompat.network.StrikePointPayload;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The strike board's map: the ground around the bound computer, drawn from
 * Xaero's own world map where that map has the ground and from the blocks the
 * game has loaded where it has not, with a marker the crew puts where the next
 * rack is to be released. Left click writes the marker, right click takes it off,
 * and the marker is remembered on the board itself, so it is still there the next
 * time the board is opened.
 */
@OnlyIn(Dist.CLIENT)
public class StrikeTerminalScreen extends Screen {

    /** How many squares the fallback picture is sampled at, across and down. */
    private static final int GRID = 72;
    /** How many ticks pass before the picture is drawn again. */
    private static final int REFRESH_TICKS = 4;
    private static final double ZOOM_STEP = 1.4D;
    private static final double MIN_SPAN = 32.0D;
    private static final double MAX_SPAN = 4096.0D;

    private final BindingRef controller;
    private final ItemStack board;
    private final int[] picture = new int[GRID * GRID];
    private List<XaeroMapTiles.Tile> tiles = List.of();
    private Vec3 center;
    private double span = 320.0D;
    /** Where the board's picture came from this refresh: the corner map or local sampling. */
    private String source = "local";
    private boolean pictureTickOnly;
    /** Whether the pack's map tiles were drawn this frame. */
    private boolean mapTilesDrawn;
    /** The line the board shows under its title: where the picture comes from. */
    private String sourceText = "本地采样";
    private Vec3 marker;
    private int panelLeft;
    private int panelTop;
    private int panelSize;
    private int mapLeft;
    private int mapTop;
    private int pictureTick;
    /** Ticks left before the map says what it found in the log again. */
    private int mapLogCountdown;

    public StrikeTerminalScreen(BindingRef controller, ItemStack board) {
        super(Component.translatable("gui.firecontrolcompat.strike_terminal"));
        this.controller = controller;
        this.board = board;
        BlockPos remembered = StrikeTerminalItem.rememberedPoint(board);
        if (remembered != null) {
            this.marker = new Vec3(remembered.getX() + 0.5D, remembered.getY(), remembered.getZ() + 0.5D);
        }
    }

    @Override
    protected void init() {
        this.panelSize = Math.min(Math.min(this.width - 40, this.height - 60), 340);
        this.panelLeft = (this.width - this.panelSize) / 2;
        this.panelTop = (this.height - this.panelSize) / 2;
        this.mapLeft = this.panelLeft + 14;
        this.mapTop = this.panelTop + 34;
        Level level = this.minecraft == null ? null : this.minecraft.level;
        // Re-centred on every open, not only the first time. The centre is a field that lives as
        // long as the screen, so a board opened once somewhere stayed centred there for good:
        // every cell was in a chunk the client did not hold, and the map became one flat colour
        // while the marking and the guidance - which do not depend on the map - kept working.
        // The player's own ground is always in memory, so the board now always has something to
        // draw, the block fallback included, wherever Xaero has no tiles yet.
        Vec3 at = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.position()
                : (level == null ? null : this.controller.worldCenter(level));
        this.center = at != null ? at : Vec3.ZERO;
        this.marker = this.marker; // kept: a point already set on the board follows the board
        // The point the board still carries is written back onto the computer, so
        // a rack released after the board was put away still has somewhere to go.
        if (this.marker != null) {
            StrikePointPayload.sendSet(this.controller, BlockPos.containing(this.marker));
        }
        int buttonWidth = (this.panelSize - 28 - 8) / 2;
        int buttonY = this.mapTop + mapSize() + 12;
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.firecontrolcompat.strike_clear"),
                        button -> {
                            this.marker = null;
                            StrikeTerminalItem.rememberPoint(this.board, null);
                            StrikePointPayload.sendClear(this.controller);
                        })
                .bounds(this.mapLeft, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.back"),
                        button -> this.onClose())
                .bounds(this.mapLeft + buttonWidth + 8, buttonY, buttonWidth, 20).build());
        this.drawPicture();
    }

    /**
     * Draws the player in the middle of the board, pointing the way the player faces.
     *
     * <p>The board is centred on the player, so the middle of the board is the player: an arrow
     * that turns with the body is all a crew needs to line the board up with the window.</p>
     */
    private void drawPlayerArrow(GuiGraphics graphics, int size) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        int centreX = this.mapLeft + size / 2;
        int centreY = this.mapTop + size / 2;
        double radians = Math.toRadians(this.minecraft.player.getYRot());
        // Minecraft's yaw grows clockwise from south; on the board z grows south and x grows east.
        double dirX = -Math.sin(radians);
        double dirZ = Math.cos(radians);
        int tipX = centreX + (int) Math.round(dirX * 9.0D);
        int tipY = centreY + (int) Math.round(dirZ * 9.0D);
        int colour = 0xFFFFD24A;
        for (int step = 0; step <= 9; step++) {
            int x = centreX + (tipX - centreX) * step / 9;
            int y = centreY + (tipY - centreY) * step / 9;
            graphics.fill(x, y, x + 1, y + 1, colour);
        }
        int wingX = (int) Math.round(-dirZ * 5.0D);
        int wingY = (int) Math.round(dirX * 5.0D);
        graphics.fill(centreX + wingX, centreY + wingY, centreX + wingX + 1, centreY + wingY + 1, colour);
        graphics.fill(centreX - wingX, centreY - wingY, centreX - wingX + 1, centreY - wingY + 1, colour);
        graphics.drawString(this.font, Component.literal("YOU"), centreX + 7, centreY - 4, colour, false);
    }

    /** Whether nothing at all has been sampled for the board yet. */
    private boolean pictureEmpty() {
        for (int colour : this.picture) {
            if ((colour >>> 24) != 0) {
                return false;
            }
        }
        return true;
    }

    private int mapSize() {
        return this.panelSize - 28 - 22;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.pictureTick++ % REFRESH_TICKS == 0) {
            this.drawPicture();
        }
    }

    /**
     * Takes in the ground the board is looking at: Xaero's tiles where that map
     * has been, and the sampled colours of the loaded blocks underneath them.
     */
    private void drawPicture() {
        this.tiles = XaeroMapTiles.collect(this.center.x, this.center.z, this.span,
                this.mapLeft, this.mapTop, mapSize());
        if (this.mapLogCountdown-- <= 0) {
            this.mapLogCountdown = 10;
            FireControlCompat.LOGGER.info("[firecontrolcompat] strike map {} tiles={}",
                    XaeroMapTiles.report(), DisplayMapTiles.status());
        }
        // The corner map's own pixels first: they are the map a crew can actually see, and
        // they are current wherever the minimap is. Local block sampling is the fallback for
        // ground the minimap has not drawn yet.
        // The pack's own map tiles first: its displays already read Xaero's cache, so the
        // terminal shows the same picture they do. Local sampling stays as the fallback.
        this.source = DisplayMapTiles.available() ? "packmap" : "local";
        this.sourceText = this.source.equals("packmap") ? "火控学地图 · Xaero 缓存" : "本地采样";
        Level level = this.minecraft == null ? null : this.minecraft.level;
        if (level == null) {
            return;
        }
        double cell = this.span / GRID;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int row = 0; row < GRID; row++) {
            double z = this.center.z + (row + 0.5D - GRID / 2.0D) * cell;
            for (int column = 0; column < GRID; column++) {
                double x = this.center.x + (column + 0.5D - GRID / 2.0D) * cell;
                int cellX = Mth.floor(x);
                int cellZ = Mth.floor(z);
                int index = row * GRID + column;
                if (!level.hasChunkAt(pos.set(cellX, Mth.floor(this.center.y), cellZ))) {
                    this.picture[index] = 0xFF101018;
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, cellX, cellZ);
                pos.set(cellX, Math.max(level.getMinBuildHeight(), top - 1), cellZ);
                BlockState state = level.getBlockState(pos);
                MapColor colour = state.getMapColor(level, pos);
                int base = colour == null ? 0x808080 : colour.col;
                double shade = Mth.clamp(0.72D + (top - this.center.y) * 0.012D, 0.45D, 1.35D);
                int red = Mth.clamp((int) (((base >> 16) & 0xFF) * shade), 0, 255);
                int green = Mth.clamp((int) (((base >> 8) & 0xFF) * shade), 0, 255);
                int blue = Mth.clamp((int) ((base & 0xFF) * shade), 0, 255);
                this.picture[index] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }
    }

    @Override
    public void removed() {
        DisplayMapTiles.release(this);
        super.removed();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int right = this.panelLeft + this.panelSize;
        int bottom = this.panelTop + this.panelSize;
        graphics.fill(this.panelLeft + 3, this.panelTop + 4, right + 4, bottom + 5, 0x90000000);
        graphics.fill(this.panelLeft, this.panelTop, right, bottom, 0xFF2A2A30);
        graphics.fill(this.panelLeft + 2, this.panelTop + 2, right - 2, bottom - 2, 0xFF1B1B20);
        graphics.fill(this.mapLeft, this.mapTop, this.mapLeft + mapSize(), this.mapTop + mapSize(), 0xFF000000);
        int size = mapSize();
        if (DisplayMapTiles.available() && this.source.equals("packmap")) {
            // Clipped to the frame: the tiles are drawn as quads and would otherwise cover the
            // whole window instead of only the board.
            graphics.flush();
            graphics.enableScissor(this.mapLeft, this.mapTop, this.mapLeft + size, this.mapTop + size);
            try {
                this.mapTilesDrawn = DisplayMapTiles.draw(graphics, this,
                        this.center, this.span / 2.0D, this.span / 2.0D, this.mapLeft, this.mapTop, size);
            } finally {
                graphics.disableScissor();
                graphics.flush();
            }
        }
        double pixelsPerCell = (double) size / GRID;
        for (int row = 0; row < GRID && !this.mapTilesDrawn; row++) {
            for (int column = 0; column < GRID; column++) {
                int colour = this.picture[row * GRID + column];
                if ((colour >>> 24) == 0) {
                    continue;
                }
                int left = this.mapLeft + (int) Math.floor(column * pixelsPerCell);
                int top = this.mapTop + (int) Math.floor(row * pixelsPerCell);
                int cellRight = this.mapLeft + (int) Math.ceil((column + 1) * pixelsPerCell);
                int cellBottom = this.mapTop + (int) Math.ceil((row + 1) * pixelsPerCell);
                graphics.fill(left, top, cellRight, cellBottom, colour);
            }
        }
        // Xaero's tiles are drawn with their own texture, which has to go out
        // before the panel's queued fills would cover it.
        if (!this.tiles.isEmpty()) {
            graphics.flush();
            graphics.enableScissor(this.mapLeft, this.mapTop, this.mapLeft + size, this.mapTop + size);
            Object batch = XaeroMapTiles.beginBatch();
            try {
                for (XaeroMapTiles.Tile tile : this.tiles) {
                    if (batch == null) {
                        XaeroMapTiles.drawPlain(graphics, tile);
                    } else {
                        XaeroMapTiles.tile(graphics, batch, tile);
                    }
                }
            } finally {
                if (batch != null) {
                    XaeroMapTiles.endBatch(batch);
                }
            }
            graphics.disableScissor();
            graphics.flush();
        }
        // Always drawn: the arrow belongs to the player, not to whether Xaero had tiles for
        // this ground, and it used to sit inside that check so a board without tiles had no
        // arrow either.
        this.drawPlayerArrow(graphics, size);
        graphics.renderOutline(this.mapLeft, this.mapTop, size, size, 0xFF6E5A2A);
        if (this.tiles.isEmpty() && this.pictureEmpty()) {
            graphics.drawString(this.font, Component.literal("no map data"),
                    this.mapLeft + 4, this.mapTop + 4, 0xFFB0B0B0, false);
        }

        // The computer the board is bound to, and the marker the crew set.
        int centreX = this.mapLeft + size / 2;
        int centreY = this.mapTop + size / 2;
        graphics.fill(centreX - 4, centreY, centreX + 4, centreY + 1, 0xFF64C8FF);
        graphics.fill(centreX, centreY - 4, centreX + 1, centreY + 4, 0xFF64C8FF);
        if (this.marker != null) {
            int markerX = this.mapLeft + (int) Math.round((this.marker.x - this.center.x) / this.span * size + size / 2.0D);
            int markerY = this.mapTop + (int) Math.round((this.marker.z - this.center.z) / this.span * size + size / 2.0D);
            graphics.fill(markerX - 6, markerY - 6, markerX + 7, markerY + 7, 0x60FF3020);
            graphics.renderOutline(markerX - 6, markerY - 6, 13, 13, 0xFFFF4030);
            graphics.fill(markerX, markerY - 2, markerX + 1, markerY + 3, 0xFFFF4030);
            graphics.fill(markerX - 2, markerY, markerX + 3, markerY + 1, 0xFFFF4030);
        }
        // The square under the pointer, so a click lands where it looks.
        if (this.hovering(mouseX, mouseY)) {
            int hoverX = Mth.floor(this.center.x + (mouseX - this.mapLeft - size / 2.0D) / size * this.span);
            int hoverZ = Mth.floor(this.center.z + (mouseY - this.mapTop - size / 2.0D) / size * this.span);
            graphics.drawString(this.font, Component.literal(hoverX + ", " + hoverZ),
                    this.mapLeft + 4, this.mapTop + size - 12, 0xFFE0E0E0, true);
        }

        graphics.drawString(this.font, this.title, this.panelLeft + 6, this.panelTop + 6, 0xFFE8D9A0);
        String picture = this.tiles.isEmpty()
                ? Component.translatable("gui.firecontrolcompat.strike_sampled").getString()
                : "Xaero " + this.tiles.size();
        String info = String.format(Locale.ROOT, "%s  %s  %d x %d  ·  %s",
                Component.translatable("gui.firecontrolcompat.strike_map").getString(),
                Component.translatable("gui.firecontrolcompat.strike_scope").getString(),
                (int) this.span, (int) this.span, picture);
        graphics.drawString(this.font, info, this.panelLeft + 6, this.panelTop + 20, 0xFFB0B0B0);
        graphics.drawString(this.font, Component.translatable("gui.firecontrolcompat.strike_hint"),
                this.panelLeft + 6, this.mapTop + size + 36, 0xFF9A9A9A);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private boolean hovering(double mouseX, double mouseY) {
        return mouseX >= this.mapLeft && mouseX < this.mapLeft + mapSize()
                && mouseY >= this.mapTop && mouseY < this.mapTop + mapSize();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.hovering(mouseX, mouseY) && (button == 0 || button == 1)) {
            int size = mapSize();
            if (button == 1) {
                this.marker = null;
                StrikeTerminalItem.rememberPoint(this.board, null);
                StrikePointPayload.sendClear(this.controller);
                return true;
            }
            Level level = this.minecraft == null ? null : this.minecraft.level;
            int floor = level == null ? 64 : level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    Mth.floor(this.center.x), Mth.floor(this.center.z));
            int x = Mth.floor(this.center.x + (mouseX - this.mapLeft - size / 2.0D) / size * this.span);
            int z = Mth.floor(this.center.z + (mouseY - this.mapTop - size / 2.0D) / size * this.span);
            int y = level == null ? floor : level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            this.marker = new Vec3(x + 0.5D, y, z + 0.5D);
            StrikeTerminalItem.rememberPoint(this.board, BlockPos.containing(this.marker));
            StrikePointPayload.sendSet(this.controller, BlockPos.containing(this.marker));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D && this.hovering(mouseX, mouseY)) {
            this.span = Mth.clamp(scrollY > 0.0D ? this.span / ZOOM_STEP : this.span * ZOOM_STEP,
                    MIN_SPAN, MAX_SPAN);
            this.drawPicture();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}