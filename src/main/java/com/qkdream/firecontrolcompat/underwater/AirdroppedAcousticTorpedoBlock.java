package com.qkdream.firecontrolcompat.underwater;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;

/**
 * The air-dropped acoustic torpedo as it lies in the world.
 *
 * <p>It is the tube round's block in every mechanical way, so it is placed,
 * fitted with a fuze and a tracer tip and picked up again exactly like one; all
 * that differs is the body it wears and the round it turns into.</p>
 */
public class AirdroppedAcousticTorpedoBlock extends AcousticTorpedoBlock {

    private static final MapCodec<AirdroppedAcousticTorpedoBlock> CODEC = simpleCodec(AirdroppedAcousticTorpedoBlock::new);

    public AirdroppedAcousticTorpedoBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public EntityType<? extends AcousticTorpedoProjectile> getAssociatedEntityType() {
        return AcousticTorpedoes.AIRDROPPED_ACOUSTIC_TORPEDO_ENTITY.get();
    }
}
