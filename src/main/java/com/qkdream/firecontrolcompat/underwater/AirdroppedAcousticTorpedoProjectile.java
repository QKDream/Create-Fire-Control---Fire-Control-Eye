package com.qkdream.firecontrolcompat.underwater;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The passive acoustic torpedo as the aircraft rack carries it.
 *
 * <p>Underneath it is the same round the tubes throw - the same ears, the same
 * swim, the same burst, the same settings read off the computer - and only its
 * body says otherwise: 面包学's own visually guided round is what the rack, the
 * world and the hand show, while everything that swims is the acoustic round's.
 * The block is still a real round, so a fuze can be fitted to it before it goes
 * on the rail.</p>
 */
public class AirdroppedAcousticTorpedoProjectile extends AcousticTorpedoProjectile {

    public AirdroppedAcousticTorpedoProjectile(EntityType<? extends AirdroppedAcousticTorpedoProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    public BlockState getRenderedBlockState() {
        return AcousticTorpedoes.AIRDROPPED_ACOUSTIC_TORPEDO.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH);
    }

    @Override
    protected String logName() {
        return "air-dropped acoustic torpedo";
    }
}
