package com.qkdream.firecontrolcompat.compat.yuushya;

import java.util.Map;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.CBCCompatTransformers;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;

/**
 * Where the muzzle effect of a shot belongs when the barrel is a modelling
 * block.
 *
 * <p>Create Big Cannons fires the plume and the blast wave of a shot from a
 * spot two blocks behind the mouth of the barrel. Two blocks is just inside the
 * bore of a real barrel many blocks long, but it sits at the breech of a
 * modelling block that plays a whole barrel in one block, so a shot out of a
 * modelling cannon looks like it went off at the back. A shot whose effect
 * lands on a modelling barrel is moved onto the mouth of the frontmost
 * modelling barrel of that cannon instead, which is where the model shows the
 * bore.</p>
 */
public final class YuushyaMuzzlePlacement {

    /** How far out of the barrel mouth the effect is placed, in blocks. */
    private static final double MOUTH_OFFSET = 0.6;

    /** How far off the line of fire a barrel still counts as the muzzle, in blocks. */
    private static final double LINE_TOLERANCE = 1.5;

    /** How far in front of the effect the muzzle is looked for, in blocks. */
    private static final double REACH = 12.0;

    private YuushyaMuzzlePlacement() {
    }

    /**
     * Moves the spot a muzzle effect is placed at onto the mouth of the
     * modelling barrel the shot came out of, then hands the position to Create
     * Big Cannons' own transform, so the effect still ends up where the rest of
     * the shot does. A cannon built from ordinary barrels is left exactly as it
     * was.
     */
    public static Vec3 adjust(Contraption contraption, Direction firing, Level level, Vec3 position, Vec3 root) {
        Vec3 mouth = mouthPosition(contraption, firing, position);
        return CBCCompatTransformers.transformVec3(level, mouth == null ? position : mouth, root);
    }

    /** The mouth of the modelling barrel the effect belongs to, or null. */
    private static Vec3 mouthPosition(Contraption contraption, Direction firing, Vec3 position) {
        if (contraption == null || firing == null || position == null
                || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || !YuushyaBigCannonCompat.isAvailable()) {
            return null;
        }
        AbstractContraptionEntity entity = contraption.entity;
        if (entity == null) {
            return null;
        }
        Vec3 forward = directionOf(entity, firing);
        if (forward == null) {
            return null;
        }
        Map<BlockPos, StructureBlockInfo> blocks = contraption.getBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        Vec3 mouth = null;
        double mouthAhead = Double.NEGATIVE_INFINITY;
        double barrelAhead = Double.NEGATIVE_INFINITY;
        for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
            StructureBlockInfo info = entry.getValue();
            if (info == null) {
                continue;
            }
            boolean modelling = YuushyaBigCannonCompat.isBigCannonBarrel(info.state());
            boolean barrel = modelling || info.state().getBlock() instanceof BigCannonBlock;
            if (!barrel) {
                continue;
            }
            Vec3 centre = entity.toGlobalVector(Vec3.atCenterOf(entry.getKey()), 0.0F);
            Vec3 offset = centre.subtract(position);
            double ahead = offset.dot(forward);
            if (ahead < 0.0 || ahead > REACH) {
                continue;
            }
            if (offset.subtract(forward.scale(ahead)).length() > LINE_TOLERANCE) {
                continue;
            }
            if (modelling) {
                if (ahead > mouthAhead) {
                    mouthAhead = ahead;
                    mouth = centre;
                }
            } else if (ahead > barrelAhead) {
                barrelAhead = ahead;
            }
        }
        if (mouth == null || barrelAhead > mouthAhead) {
            return null;
        }
        return mouth.add(forward.scale(MOUTH_OFFSET));
    }

    /** The direction the cannon fires in, in the space its contraption lives in. */
    private static Vec3 directionOf(AbstractContraptionEntity entity, Direction firing) {
        Vec3 origin = Vec3.atCenterOf(BlockPos.ZERO);
        Vec3 ahead = origin.add(firing.getStepX(), firing.getStepY(), firing.getStepZ());
        Vec3 direction = entity.toGlobalVector(ahead, 0.0F).subtract(entity.toGlobalVector(origin, 0.0F));
        return direction.lengthSqr() < 1.0E-6 ? null : direction.normalize();
    }
}