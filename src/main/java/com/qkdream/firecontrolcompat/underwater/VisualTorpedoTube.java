package com.qkdream.firecontrolcompat.underwater;

import com.cainiao1053.cbcmoreshells.cannon_control.contraption.MountedTorpedoTubeContraption;
import com.cainiao1053.cbcmoreshells.cannons.torpedo_tube.ITorpedoTubeBlockEntity;
import com.cainiao1053.cbcmoreshells.cannons.torpedo_tube.TorpedoTubeBlock;
import com.cainiao1053.cbcmoreshells.cannons.torpedo_tube.breeches.quick_firing_breech.TorpQuickfiringBreechBlock;
import com.cainiao1053.cbcmoreshells.cannons.torpedo_tube.breeches.quick_firing_breech.TorpQuickfiringBreechBlockEntity;
import com.qkdream.firecontrolcompat.FireControlCompat;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.mcreator.myfirstmod.init.MianbaosModernwarfareModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.multiloader.NetworkPlatform;
import rbasamoyai.createbigcannons.network.ClientboundUpdateContraptionPacket;

/**
 * 面包学's own visually guided round, put into a CBCMS torpedo tube.
 *
 * <p>A CBCMS tube only takes a round that is one of its own torpedo blocks, and
 * 面包学's round is a plain item, so the click is answered here instead: the
 * round goes into the tube as one of our own torpedo blocks, carrying a mark
 * that says which round it really is. What comes out of the tube is 面包学's
 * own round, swimming the way it swims everywhere else - see
 * {@link VisualTorpedoRelease} - and a round pulled back out of the tube is
 * handed back as the item it went in as.</p>
 *
 * <p>The load itself is the one CBCMS does by hand: the same block, the same
 * sound, the same sync to the clients watching the contraption, so a tube on a
 * mount shows the round sitting in it exactly as it shows its own.</p>
 *
 * <p>CBCMS fills a tube through the breech at its tail, so this does too: the
 * click has to land on the breech's own back face with the breech swung open,
 * and the round then goes into the space in front of it. A round already lying
 * there is pushed one block further up the barrel, and a barrel with no room
 * left takes nothing - the same answers the tube's own rounds get.</p>
 */
public final class VisualTorpedoTube {

    private VisualTorpedoTube() {
    }

    /**
     * Puts 面包学's visually guided round into the tube that was clicked, the way
     * the tube's own rounds go in. Answers whether the click belonged to this
     * round at all, so anything else falls through to the tube.
     */
    public static boolean load(PitchOrientedContraptionEntity carrier, Player player, BlockPos localPos,
                               Direction side, InteractionHand hand) {
        if (carrier == null || player == null) {
            return false;
        }
        if (!(carrier.getContraption() instanceof MountedTorpedoTubeContraption tube)) {
            return false;
        }
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty() || held.getItem() != MianbaosModernwarfareModItems.HIGHEXPLOSIVEVISUALGUIDEDTORPEDOITEM.get()) {
            return false;
        }
        StructureBlockInfo info = tube.getBlocks().get(localPos);
        if (info == null || !(info.state().getBlock() instanceof TorpedoTubeBlock tubeBlock)) {
            return false;
        }
        if (tubeBlock.getFacing(info.state()).getAxis() != side.getAxis()) {
            return false;
        }
        if (info.state().getBlock() instanceof TorpQuickfiringBreechBlock) {
            return throughBreech(tube, carrier, player, held, localPos, side);
        }
        BlockEntity blockEntity = tube.presentBlockEntities.get(localPos);
        if (!(blockEntity instanceof ITorpedoTubeBlockEntity tubeEntity)) {
            return false;
        }
        BigCannonBehavior behavior = tubeEntity.cannonBehavior();
        if (behavior.isConnectedTo(side)) {
            return false;
        }
        Level level = player.level();
        if (level.isClientSide) {
            return true;
        }
        StructureBlockInfo round = marked(held, localPos, side, level, player);
        if (!behavior.tryLoadingBlock(round)) {
            return false;
        }
        sync(blockEntity, info, tube, carrier, level);
        SoundType sound = round.state().getSoundType();
        level.playSound(null, player.blockPosition(), sound.getPlaceSound(), SoundSource.BLOCKS,
                sound.getVolume(), sound.getPitch());
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] 面包学's visual round loaded into a torpedo tube");
        return true;
    }

    /**
     * Puts the round in through the breech at the tail of the tube, which is the
     * only place CBCMS fills one: the click has to land on the breech's back face
     * with the breech swung open, and the round then goes into the space in front
     * of it. A round already lying there is pushed one block further up the
     * barrel first, and a barrel with no room left takes nothing - the same
     * answers the tube's own rounds get.
     */
    private static boolean throughBreech(MountedTorpedoTubeContraption tube, PitchOrientedContraptionEntity carrier,
                                         Player player, ItemStack held, BlockPos localPos, Direction side) {
        BlockEntity blockEntity = tube.presentBlockEntities.get(localPos);
        if (!(blockEntity instanceof TorpQuickfiringBreechBlockEntity breech)) {
            return false;
        }
        if (breech.cannonBehavior().isConnectedTo(side)) {
            return false;
        }
        if (!breech.isOpen() || breech.onInteractionCooldown()) {
            FireControlCompat.LOGGER.info("[firecontrolcompat] the torpedo tube breech is shut, nothing went in");
            return true;
        }
        Direction pushDirection = side.getOpposite();
        BlockPos nextPos = localPos.relative(pushDirection);
        if (!(tube.presentBlockEntities.get(nextPos) instanceof ITorpedoTubeBlockEntity chamber)) {
            return false;
        }
        Level level = player.level();
        if (level.isClientSide) {
            return true;
        }
        StructureBlockInfo round = marked(held, nextPos, pushDirection, level, player);
        StructureBlockInfo sitting = chamber.cannonBehavior().block();
        Set<BlockPos> changed = new LinkedHashSet<>(2);
        if (!sitting.state().isAir()) {
            BlockPos beyond = nextPos.relative(pushDirection);
            if (!(tube.presentBlockEntities.get(beyond) instanceof ITorpedoTubeBlockEntity further)
                    || !further.cannonBehavior().canLoadBlock(sitting)) {
                FireControlCompat.LOGGER.info("[firecontrolcompat] that torpedo tube is full, nothing went in");
                return true;
            }
            further.cannonBehavior().loadBlock(sitting);
            chamber.cannonBehavior().removeBlock();
            changed.add(beyond);
        }
        if (!chamber.cannonBehavior().tryLoadingBlock(round)) {
            return true;
        }
        changed.add(nextPos);
        syncAll(changed, tube, carrier, level);
        SoundType sound = round.state().getSoundType();
        level.playSound(null, player.blockPosition(), sound.getPlaceSound(), SoundSource.BLOCKS,
                sound.getVolume(), sound.getPitch());
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        FireControlCompat.LOGGER.info("[firecontrolcompat] the visual round went in through the breech");
        return true;
    }

    /** Shows every block a load changed to everybody watching the contraption, the way CBCMS does. */
    private static void syncAll(Set<BlockPos> changed, MountedTorpedoTubeContraption tube,
                                PitchOrientedContraptionEntity carrier, Level level) {
        Map<BlockPos, StructureBlockInfo> blocks = tube.getBlocks();
        Map<BlockPos, StructureBlockInfo> updates = new HashMap<>(changed.size());
        for (BlockPos pos : changed) {
            StructureBlockInfo old = blocks.get(pos);
            if (old == null) {
                continue;
            }
            BlockEntity blockEntity = tube.presentBlockEntities.get(pos);
            CompoundTag tag = null;
            if (blockEntity != null) {
                tag = blockEntity.saveWithFullMetadata(level.registryAccess());
                tag.remove("x");
                tag.remove("y");
                tag.remove("z");
            }
            StructureBlockInfo updated = new StructureBlockInfo(old.pos(), old.state(), tag);
            blocks.put(pos, updated);
            updates.put(pos, updated);
        }
        if (!updates.isEmpty()) {
            NetworkPlatform.sendToClientTracking(ClientboundUpdateContraptionPacket.entity(carrier, updates), carrier);
        }
    }

    /** The item that went into the tube, for a round pulled back out of it. */
    public static ItemStack visualRoundItem() {
        return new ItemStack(MianbaosModernwarfareModItems.HIGHEXPLOSIVEVISUALGUIDEDTORPEDOITEM.get());
    }

    /**
     * The round as the tube files it: one of our torpedo blocks, with the mark
     * that says which round it stands for and whose eyes it swims towards.
     */
    private static StructureBlockInfo marked(ItemStack held, BlockPos localPos, Direction side, Level level,
                                             Player player) {
        ItemStack one = held.copy();
        one.setCount(1);
        BigCannonMunitionBlock block = WireGuidedTorpedoes.WIRE_GUIDED_TORPEDO.get();
        StructureBlockInfo base = block.getHandloadingInfo(one, localPos, side, level.registryAccess());
        CompoundTag tag = base.nbt() == null ? new CompoundTag() : base.nbt().copy();
        tag.putBoolean(WireGuidedTorpedoProjectile.VISUAL_TAG, true);
        tag.putString(WireGuidedTorpedoProjectile.VISUAL_OWNER_TAG, player.getStringUUID());
        return new StructureBlockInfo(base.pos(), base.state(), tag);
    }

    /** Shows the round to everybody watching the contraption, the way CBCMS does. */
    private static void sync(BlockEntity blockEntity, StructureBlockInfo info, MountedTorpedoTubeContraption tube,
                             PitchOrientedContraptionEntity carrier, Level level) {
        CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
        tag.remove("x");
        tag.remove("y");
        tag.remove("z");
        StructureBlockInfo updated = new StructureBlockInfo(info.pos(), info.state(), tag);
        tube.getBlocks().put(info.pos(), updated);
        NetworkPlatform.sendToClientTracking(ClientboundUpdateContraptionPacket.entity(carrier, info.pos(), updated),
                carrier);
    }
}
