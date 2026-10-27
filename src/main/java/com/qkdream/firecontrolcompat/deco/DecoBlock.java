package com.qkdream.firecontrolcompat.deco;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Flat decoration block used by the blue archive poster/tile set.
 *
 * <p>The supplied Blockbench models are 16x16 planes sitting at the very bottom
 * of the block, so the block keeps a one pixel carpet-like shape: it stays
 * clickable (model tools and wrenches need a non empty outline) while never
 * blocking the build volume above it. Translucent regions of the texture are
 * handled by the cutout render type of the block model.</p>
 *
 * <p>{@code facing} is the direction the picture looks at, exactly like the
 * vanilla ladder: the plane always touches the block behind it, so a decal
 * clicked onto a floor uses {@code facing=up}, one clicked under a ceiling uses
 * {@code facing=down} and one clicked onto a wall uses the clicked face. The
 * blockstate json only rotates the single flat model, which keeps the artwork
 * upright and flush with the wall on all four wall sides.</p>
 *
 * <p>{@code mirrored} flips the artwork left to right, something a rotation can
 * not express.</p>
 *
 * <p>{@code rotation} stores how far the artwork is turned inside its own plane,
 * counted in clockwise quarter turns seen from above. It only means something
 * for the two horizontal mounts: an upright artwork on a wall is already fixed
 * by the wall, while a decal lying on a floor is read from whichever side the
 * player happens to stand on. A structure built at an angle (a sable sub level,
 * for example) therefore shows floor decals "upside down" for a viewer standing
 * on the other side of the artwork, which is what this property is for.</p>
 *
 * <p>Right clicking with an empty hand turns a horizontal decal by 90 degrees
 * (sneak to flip it instead); on a wall the same click flips the artwork. Eight
 * clicks on a horizontal decal walk through all four turns plus the flip.</p>
 */
public class DecoBlock extends Block {

    /** Direction the picture looks at; the plane touches the opposite block face. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    /** Left to right flipped artwork, toggled by right clicking with an empty hand. */
    public static final BooleanProperty MIRRORED = BooleanProperty.create("mirrored");
    /** Clockwise quarter turns of the artwork inside its plane, used by up/down decals only. */
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);

    private static final VoxelShape SHAPE_UP = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 1.0D, 16.0D);
    private static final VoxelShape SHAPE_DOWN = Block.box(0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_NORTH = Block.box(0.0D, 0.0D, 15.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 1.0D);
    private static final VoxelShape SHAPE_EAST = Block.box(15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_WEST = Block.box(0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D);

    public DecoBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case UP -> SHAPE_UP;
            case DOWN -> SHAPE_DOWN;
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!level.isClientSide) {
            boolean horizontal = state.getValue(FACING).getAxis() == Direction.Axis.Y;
            BlockState next = horizontal && !player.isShiftKeyDown()
                    ? state.setValue(ROTATION, (state.getValue(ROTATION) + 1) % 4)
                    : state.cycle(MIRRORED);
            level.setBlock(pos, next, Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.7F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction facing = rotation.rotate(state.getValue(FACING));
        int turns = facing.getAxis() == Direction.Axis.Y ? (state.getValue(ROTATION) + rotation.ordinal()) % 4 : 0;
        return state.setValue(FACING, facing).setValue(ROTATION, turns);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        Direction facing = mirror.mirror(state.getValue(FACING));
        int turns = facing.getAxis() == Direction.Axis.Y ? mirroredTurns(state.getValue(ROTATION), mirror) : 0;
        return state.setValue(FACING, facing).setValue(ROTATION, turns).cycle(MIRRORED);
    }

    /** Reading direction of the artwork is a compass direction, so a mirrored world mirrors it as well. */
    private static int mirroredTurns(int turns, Mirror mirror) {
        return switch (mirror) {
            case FRONT_BACK -> switch (turns) {
                case 0 -> 2;
                case 2 -> 0;
                default -> turns;
            };
            case LEFT_RIGHT -> switch (turns) {
                case 1 -> 3;
                case 3 -> 1;
                default -> turns;
            };
            case NONE -> turns;
        };
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROTATION, MIRRORED);
    }
}
