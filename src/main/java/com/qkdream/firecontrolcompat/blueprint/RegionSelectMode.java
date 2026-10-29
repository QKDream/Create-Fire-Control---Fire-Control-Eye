package com.qkdream.firecontrolcompat.blueprint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.qkdream.firecontrolcompat.FireControlCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * {@code /sableexport select}: the two-left-click selection, with Sable's tool fingertip.
 *
 * <p>The blueprint tool records a region with two left clicks in its library mode, and that mode
 * cannot be entered in the pack this ships with: the click digs the block instead of picking a
 * corner. This is the same gesture, on our own state, feeding {@link RegionSelection}.
 *
 * <p>Three interception points, for three different reasons:
 *
 * <ul>
 *   <li>{@link InputEvent.MouseButton.Pre} is the one that does the work, and the only one that
 *       can. It is fired at the top of {@code MouseHandler#onPress}, and cancelling it keeps
 *       {@code keyAttack} from ever going down, so neither {@code Minecraft#startAttack} nor
 *       {@code Minecraft#continueAttack} runs and no {@code ServerboundPlayerActionPacket} is
 *       sent at all - the only way the block is safe on a real server, because everything further
 *       down sends that packet whether or not it was cancelled (javap of
 *       {@code MultiPlayerGameMode}: {@code lambda$startDestroyBlock$1} builds the packet before
 *       the {@code getUseItem()} check and returns it as-is when that check is
 *       {@code TriState.FALSE}, and {@code continueDestroyBlock} falls back to
 *       {@code startDestroyBlock} on its last branch, offset 394).
 *   <li>{@link InputEvent.InteractionKeyMappingTriggered} covers an attack bound to a key instead
 *       of the mouse, with the same cancel and the same corner logic.
 *   <li>{@link PlayerInteractEvent.LeftClickBlock} is the safety net: whatever still reaches the
 *       destroy path - another mod calling {@code startDestroyBlock} itself - is cancelled there,
 *       with {@code useBlock} and {@code useItem} denied so the block is not attacked either. It
 *       is cancelled for our own player only, and only while the mode is on: the server half of
 *       the event still arrives when the world is local, and must not touch anybody else's mining.
 * </ul>
 *
 * <p>Everything is wrapped in try/catch: a click that cannot be read is one line in the log, never
 * a throw out of a game event.
 */
@EventBusSubscriber(modid = FireControlCompat.MOD_ID, value = Dist.CLIENT,
    bus = EventBusSubscriber.Bus.GAME)
public final class RegionSelectMode {

    /** The corners of a box are drawn in this colour while a selection is in hand. */
    private static final int BOX_COLOUR = 0x8FD6FF;

    /** How solid the selection box is drawn. */
    private static final float BOX_ALPHA = 0.9F;

    /** The twelve edges of a cube, as corner indices. */
    private static final int[][] EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7},
        {0, 2}, {1, 3}, {4, 6}, {5, 7},
        {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /** Whether the next two left clicks belong to the selection instead of the world. */
    private static volatile boolean enabled;

    private RegionSelectMode() {
    }

    /** Whether the next two left clicks belong to the selection instead of the world. */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Turns the mode on or off, answering in chat.
     *
     * <p>Turning it on drops the corners of a previous selection: the next click is the first
     * corner of a new one, which is what the message then says the player is doing.
     */
    public static int toggle(CommandSourceStack source) {
        try {
            enabled = !enabled;
            if (enabled) {
                RegionSelection.clearCommandCorners();
                BlueprintExporter.LOGGER.info("select-mode: enabled=true player={}", playerName());
                reply(source, "选区模式：开 —— 左键点第一个角，再左键点第二个角（Shift+左键取消）");
            } else {
                BlueprintExporter.LOGGER.info("select-mode: enabled=false player={}", playerName());
                reply(source, "选区模式：关");
            }
            return 1;
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("select-mode: toggle failed ({})", t.toString());
            return 0;
        }
    }

    /**
     * The click that picks a corner, taken at the mouse itself, before the game turns it into an
     * attack.
     *
     * <p>This is the click that has to be stopped. The later interception points do not do:
     * cancelling {@link InputEvent.InteractionKeyMappingTriggered} only stops the press, because a
     * held button still reaches {@code MultiPlayerGameMode#continueDestroyBlock}, which falls back
     * to {@code startDestroyBlock} on its last branch (javap: offset 394) and sends the destroy
     * packet anyway - so the server would start breaking the block by itself, and it would also do
     * so for the click that <em>ends</em> the mode, when nothing is cancelled any more. Consuming
     * the button at {@code MouseHandler#onPress} keeps {@code keyAttack} from ever going down, so
     * neither {@code startAttack} nor {@code continueAttack} runs and no packet exists to break
     * anything with, on any server.
     *
     * <p>Only presses are taken, and only in the world: a release still reaches the key mapping,
     * and a click in a menu - or the click that grabs the mouse back into the game - is left
     * alone.
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        try {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || event.getAction() != GLFW.GLFW_PRESS) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null || minecraft.screen != null) {
                return;
            }
            if (minecraft.mouseHandler != null && !minecraft.mouseHandler.isMouseGrabbed()) {
                return;
            }
            if (!click(minecraft)) {
                return;
            }
            event.setCanceled(true);
            minecraft.player.swing(InteractionHand.MAIN_HAND);
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("select-mode: click failed ({})", t.toString());
        }
    }

    /**
     * The second interception point, for an attack that never came from the mouse.
     *
     * <p>An attack bound to a key goes through {@link InputEvent.InteractionKeyMappingTriggered}
     * instead of the mouse, and is cancelled here for the same reason. In the ordinary case the
     * mouse handler above has already consumed the press, so this never fires - the two share
     * {@link #click} rather than each recording a corner of their own.
     */
    @SubscribeEvent
    public static void onInput(InputEvent.InteractionKeyMappingTriggered event) {
        try {
            if (!event.isAttack() || event.getHand() != InteractionHand.MAIN_HAND) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) {
                return;
            }
            if (!click(minecraft)) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(true);
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("select-mode: click failed ({})", t.toString());
        }
    }

    /**
     * Records the corner the click picked, if this click belongs to the selection.
     *
     * <p>The position is the block the crosshair is on, which is the block the click was about to
     * destroy - the same position a {@code PlayerInteractEvent.LeftClickBlock} would carry. It is
     * the pick from the frame the click arrives in, at most one frame old, because the game does
     * not pick again for a click it is about to be told to ignore.
     *
     * @return whether the click belongs to the selection, i.e. whether the caller has to cancel it
     */
    private static boolean click(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        BlockPos pos = target(minecraft);
        if (pos == null) {
            // Air, or an entity: the click is not about a block, so it is left to the game.
            return false;
        }
        boolean shift = player.isShiftKeyDown();
        if (!enabled) {
            // The mode is off; the only click still owed to the selection is the one that drops a
            // selection left over from it, so a stale box can be cleared without re-entering.
            if (!shift || !RegionSelection.hasCommandCornerA()) {
                return false;
            }
            clear(player);
            return true;
        }
        if (shift) {
            clear(player);
            return true;
        }
        BlockPos cornerA = RegionSelection.commandCornerA();
        if (cornerA == null) {
            RegionSelection.setCommandCorners(pos, null);
            player.displayClientMessage(Component.literal(
                "角点 A = (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"), false);
            BlueprintExporter.LOGGER.info("select-mode: corner=A pos=({}, {}, {})",
                pos.getX(), pos.getY(), pos.getZ());
            return true;
        }
        RegionSelection.setCommandCorners(cornerA, pos);
        // The gesture is a pair of clicks, so the second one ends the mode: the next left click is
        // the player mining again, exactly as Sable's tool leaves them.
        enabled = false;
        int minX = Math.min(cornerA.getX(), pos.getX());
        int minY = Math.min(cornerA.getY(), pos.getY());
        int minZ = Math.min(cornerA.getZ(), pos.getZ());
        int maxX = Math.max(cornerA.getX(), pos.getX());
        int maxY = Math.max(cornerA.getY(), pos.getY());
        int maxZ = Math.max(cornerA.getZ(), pos.getZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        player.displayClientMessage(Component.literal(
            "选区 (" + minX + ", " + minY + ", " + minZ + ")..(" + maxX + ", " + maxY + ", " + maxZ
                + ") 体积 " + volume + " 方块"), false);
        BlueprintExporter.LOGGER.info(
            "select-mode: corner=B pos=({}, {}, {}) aabb=({}, {}, {})..({}, {}, {}) volume={} enabled=false",
            pos.getX(), pos.getY(), pos.getZ(), minX, minY, minZ, maxX, maxY, maxZ, volume);
        int limit = BlueprintBake.regionMaxBlocks();
        if (volume > limit) {
            // The command still owns the refusal; saying it here saves a trip to it.
            player.displayClientMessage(Component.literal(
                "注意：体积 " + volume + " 超过 regionMaxBlocks=" + limit + "，导出会被拒绝"), false);
        }
        return true;
    }

    /**
     * The safety net: nothing may mine a block while the mode is on.
     *
     * <p>Reached for the held-button repeat (the game fires this event with
     * {@code CLIENT_HOLD} every tick the button stays down) and for any other caller of
     * {@code startDestroyBlock}. Cancelling stops the block from being destroyed, and the two
     * {@link TriState#FALSE} results stop the block's own left-click behaviour and the item's
     * anyway - a selection click is not a use of anything.
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        try {
            if (!enabled) {
                return;
            }
            Player clicked = event.getEntity();
            if (clicked == null || !isLocalPlayer(clicked)) {
                // Somebody else's block, in a world whose server half we also listen to.
                return;
            }
            event.setCanceled(true);
            event.setUseBlock(TriState.FALSE);
            event.setUseItem(TriState.FALSE);
        } catch (Throwable t) {
            BlueprintExporter.LOGGER.warn("select-mode: left click failed ({})", t.toString());
        }
    }

    /** The block the crosshair is on, or {@code null} when it is on air or on an entity. */
    private static BlockPos target(Minecraft minecraft) {
        HitResult hit = minecraft.hitResult;
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = block.getBlockPos();
            return pos == null ? null : pos.immutable();
        }
        return null;
    }

    /**
     * Whether the clicked player is the one at this keyboard.
     *
     * <p>The comparison is by id, not by object: the server half of the event carries the
     * {@code ServerPlayer}, which is a different object for the same person, and comparing
     * references would let our own click through.
     */
    private static boolean isLocalPlayer(Player clicked) {
        LocalPlayer local = Minecraft.getInstance().player;
        return local != null && local.getUUID().equals(clicked.getUUID());
    }

    private static void clear(LocalPlayer player) {
        RegionSelection.clearCommandCorners();
        player.displayClientMessage(Component.literal("选区已清空"), false);
        BlueprintExporter.LOGGER.info("select-mode: cleared");
    }

    private static String playerName() {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? "?" : player.getName().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    /**
     * Draws the selection while there is one, so a player who picked one corner can see which
     * block it was before picking the second.
     *
     * <p>Written by hand, the way this mod's other world overlays are: the vertices are the twelve
     * edges of the box in world space with the camera taken off, and the depth test is left on so
     * the box sits in the world instead of floating in front of a hill.
     */
    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        try {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                return;
            }
            AABB box = RegionSelection.commandBox();
            if (box == null) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.level == null) {
                return;
            }
            BufferBuilder lines = Tesselator.getInstance()
                .begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            if (lines == null) {
                return;
            }
            RenderSystem.getModelViewStack().pushMatrix().identity();
            RenderSystem.applyModelViewMatrix();
            try {
                PoseStack pose = new PoseStack();
                pose.mulPose(event.getModelViewMatrix());
                Matrix4f matrix = pose.last().pose();
                Vec3 camera = event.getCamera().getPosition();
                try {
                    box(lines, matrix, camera, box);
                } finally {
                    // In a finally so the tesselator is always released: a builder left open stops
                    // every later pass in the game from starting one of its own.
                    flush(lines);
                }
            } finally {
                RenderSystem.getModelViewStack().popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        } catch (Throwable ignored) {
            // a frame that cannot draw its box is a frame, not a crash
        }
    }

    private static void box(BufferBuilder lines, Matrix4f matrix, Vec3 camera, AABB box) {
        double[][] corners = {
            {box.minX, box.minY, box.minZ}, {box.maxX, box.minY, box.minZ},
            {box.minX, box.maxY, box.minZ}, {box.maxX, box.maxY, box.minZ},
            {box.minX, box.minY, box.maxZ}, {box.maxX, box.minY, box.maxZ},
            {box.minX, box.maxY, box.maxZ}, {box.maxX, box.maxY, box.maxZ}
        };
        float red = ((BOX_COLOUR >> 16) & 0xFF) / 255.0F;
        float green = ((BOX_COLOUR >> 8) & 0xFF) / 255.0F;
        float blue = (BOX_COLOUR & 0xFF) / 255.0F;
        for (int[] edge : EDGES) {
            vertex(lines, matrix, camera, red, green, blue, corners[edge[0]]);
            vertex(lines, matrix, camera, red, green, blue, corners[edge[1]]);
        }
    }

    private static void vertex(BufferBuilder lines, Matrix4f matrix, Vec3 camera,
                               float red, float green, float blue, double[] corner) {
        lines.addVertex(matrix,
                (float) (corner[0] - camera.x),
                (float) (corner[1] - camera.y),
                (float) (corner[2] - camera.z))
            .setColor(red, green, blue, BOX_ALPHA);
    }

    private static void flush(BufferBuilder lines) {
        MeshData mesh = lines.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        try {
            BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }
}
