package com.qkdream.firecontrolcompat.sonar;

import com.qkdream.firecontrolcompat.FireControlCompat;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockRef;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Keeps a sonar set's binding when a blueprint (or any other copy) carries it somewhere else.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The binding lives in the set's own block entity NBT, so a blueprint does carry it - but the
 * half that names the <em>place</em> of the computer is an absolute {@code BlockPos}, and a
 * blueprint writes the same bytes at a different block. The set then wakes up answering to
 * coordinates that hold nothing, the computer's sonar page lists no sets, and the only way back is
 * to point the wrench at it again. That is the "every time I put it down I have to bind it again"
 * report.</p>
 *
 * <p>{@link SonarBlockEntity} already repairs the common half of this by itself: the binding is
 * also stored as an offset from the set, which a pure translation cannot break. This mapper is the
 * exact answer on top of that. The blueprint tool hands every block a
 * {@link BlueprintBlockRef} - "blueprint sub-level N, local position P" - and can map that ref back
 * to the place the copy was actually printed at, so the binding is rewritten from the reference
 * rather than guessed from an offset. It also survives what an offset cannot: a blueprint printed
 * at another scale, and a computer that rides a different sub-level of the same blueprint.</p>
 *
 * <h2>Not a hard dependency</h2>
 *
 * <p>This is the same shape 火控学 itself uses for its own controller bindings
 * ({@code FireControlBlueprintCompat}: portable ref on save, {@code context.mapBlock} on load), and
 * it is registered the same way. Nothing here runs unless the blueprint tool is installed: the
 * class is only touched from a guarded call in common set-up, and every entry point is wrapped so
 * a tool whose API moved cannot take the mod down with it.</p>
 */
public final class SonarBlueprintCompat implements SableBlueprintBlockMapper {

    /** Our compound in a blueprint's block entity tag: sub-level id plus local position. */
    public static final String PORTABLE = "firecontrolcompat:portable_controller";

    private static final String SUB_LEVEL_ID = "SubLevelId";
    private static final String LOCAL_POS = "LocalPos";

    private static boolean registered;

    private SonarBlueprintCompat() {
    }

    /** Registers the mapper. Safe to call more than once, and safe to call without the tool. */
    public static void register() {
        if (registered) {
            return;
        }
        try {
            SableBlueprintMapperRegistry.register(SonarBlocks.SONAR_BE.get(), new SonarBlueprintCompat());
            registered = true;
            FireControlCompat.LOGGER.info("sonar-binding: registered the blueprint mapper for the sonar set");
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.warn("sonar-binding: could not register the blueprint mapper: {}",
                    failure.toString());
        }
    }

    /**
     * Writes the computer's place as a blueprint reference.
     *
     * <p>The absolute {@code ControllerPos} is dropped from the copy on purpose: left in, it would
     * name the old coordinates at the new place, which is the defect itself. The offset keys the
     * block entity already wrote are kept, so a tool without this hook still has something to go
     * on.</p>
     */
    @Override
    public CompoundTag save(BlueprintBlockSaveContext context, CompoundTag tag) {
        if (tag == null || !tag.contains(SonarBlockEntity.KEY_CONTROLLER_POS)) {
            return tag;
        }
        try {
            BlockPos controller = BlockPos.of(tag.getLong(SonarBlockEntity.KEY_CONTROLLER_POS));
            Optional<BlueprintBlockRef> found = context.blockRef(controller);
            if (found.isEmpty()) {
                // The computer is not inside what is being saved, so there is no reference that
                // could be mapped; the offset keys stay as the only handle.
                return tag;
            }
            BlueprintBlockRef portable = found.get();
            CompoundTag stored = new CompoundTag();
            stored.putInt(SUB_LEVEL_ID, portable.subLevelId());
            stored.putLong(LOCAL_POS, portable.localPos().asLong());
            tag.put(PORTABLE, stored);
            tag.remove(SonarBlockEntity.KEY_CONTROLLER_POS);
            tag.remove(SonarBlockEntity.KEY_CONTROLLER_SUB_LEVEL);
            FireControlCompat.LOGGER.info(
                    "sonar-binding: save blueprint be={} portable=[sub={} local={}]",
                    context.localPos().toShortString(), portable.subLevelId(),
                    portable.localPos().toShortString());
            return tag;
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.warn("sonar-binding: could not write the blueprint reference: {}",
                    failure.toString());
            return tag;
        }
    }

    /**
     * Rewrites the bound place from the blueprint reference, in the place the copy was printed at.
     *
     * <p>Both halves are written: the absolute place for the screen, and the offset so the set can
     * rebuild it again if the body it now rides is re-assembled later.</p>
     */
    @Override
    public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
        if (tag == null || !tag.contains(PORTABLE)) {
            return;
        }
        try {
            CompoundTag stored = tag.getCompound(PORTABLE);
            int subLevelId = stored.getInt(SUB_LEVEL_ID);
            BlueprintBlockRef ref = new BlueprintBlockRef(subLevelId, BlockPos.of(stored.getLong(LOCAL_POS)));
            BlockPos mapped = context.mapBlock(ref);
            tag.remove(PORTABLE);
            if (mapped == null) {
                FireControlCompat.LOGGER.warn(
                        "sonar-binding: blueprint reference be={} sub={} local={} has no printed block",
                        context.storagePos().toShortString(), subLevelId, ref.localPos().toShortString());
                return;
            }
            tag.putLong(SonarBlockEntity.KEY_CONTROLLER_POS, mapped.asLong());
            ServerSubLevel placed = context.session().placedSubLevel(subLevelId);
            if (placed != null) {
                tag.putUUID(SonarBlockEntity.KEY_CONTROLLER_SUB_LEVEL, placed.getUniqueId());
            } else {
                tag.remove(SonarBlockEntity.KEY_CONTROLLER_SUB_LEVEL);
            }
            BlockPos delta = mapped.subtract(context.storagePos());
            tag.putInt(SonarBlockEntity.KEY_CONTROLLER_DX, delta.getX());
            tag.putInt(SonarBlockEntity.KEY_CONTROLLER_DY, delta.getY());
            tag.putInt(SonarBlockEntity.KEY_CONTROLLER_DZ, delta.getZ());
            tag.putBoolean(SonarBlockEntity.KEY_CONTROLLER_SAME_BODY, subLevelId == context.blueprintSubLevelId());
            FireControlCompat.LOGGER.info(
                    "sonar-binding: remap blueprint be={} -> controller={} sub={}",
                    context.storagePos().toShortString(), mapped.toShortString(), subLevelId);
        } catch (Throwable failure) {
            FireControlCompat.LOGGER.warn("sonar-binding: could not remap the blueprint reference: {}",
                    failure.toString());
        }
    }

    /** The data is loaded; let the set rebuild and report the binding it came back with. */
    @Override
    public void afterLoadBlockEntity(
            BlueprintBlockPlaceContext context, BlockEntity blockEntity, CompoundTag loadedTag) {
        try {
            if (blockEntity instanceof SonarBlockEntity sonar) {
                sonar.onBlueprintRestored();
            }
        } catch (Throwable ignored) {
        }
    }
}
