package dev.betteri2m;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One item frame, whatever is providing it: a vanilla entity or a FastItemFrames block. A wall
 * can end up mixing the two, so placing and clearing go through this to keep them alike.
 */
public final class FrameRef {
	private static final Identifier FAST_ITEM_FRAME = Identifier.fromNamespaceAndPath("fastitemframes", "item_frame");
	private static final Identifier FAST_GLOW_ITEM_FRAME = Identifier.fromNamespaceAndPath("fastitemframes", "glow_item_frame");

	private final BlockPos pos;
	private final Direction facing;
	private final int entityId;

	private FrameRef(BlockPos pos, Direction facing, int entityId) {
		this.pos = pos;
		this.facing = facing;
		this.entityId = entityId;
	}

	public BlockPos pos() {
		return this.pos;
	}

	public Direction facing() {
		return this.facing;
	}

	public boolean isBlock() {
		return this.entityId < 0;
	}

	public static boolean isFastItemFramesLoaded() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fastitemframes");
	}

	/** True when the block at this position is a FastItemFrames frame. */
	public static boolean isFastFrame(Level level, BlockPos pos) {
		if (!isFastItemFramesLoaded()) {
			return false;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
		return FAST_ITEM_FRAME.equals(id) || FAST_GLOW_ITEM_FRAME.equals(id);
	}

	/** Finds the frame attached to {@code pos} facing {@code facing}, entity or block. */
	public static FrameRef find(Level level, BlockPos pos, Direction facing) {
		if (isFastFrame(level, pos)) {
			BlockState state = level.getBlockState(pos);
			Direction blockFacing = facingOf(state);
			if (blockFacing == facing) {
				return new FrameRef(pos.immutable(), facing, -1);
			}
		}
		List<ItemFrame> frames = level.getEntitiesOfClass(
			ItemFrame.class,
			AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(pos)),
			frame -> frame.getDirection() == facing && frame.blockPosition().equals(pos)
		);
		if (frames.isEmpty()) {
			return null;
		}
		ItemFrame frame = frames.get(0);
		return new FrameRef(pos.immutable(), facing, frame.getId());
	}

	public static FrameRef ofEntity(ItemFrame frame) {
		return new FrameRef(frame.blockPosition(), frame.getDirection(), frame.getId());
	}

	private static Direction facingOf(BlockState state) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals("facing") && property instanceof net.minecraft.world.level.block.state.properties.EnumProperty<?> enumProperty) {
				Object value = state.getValue(enumProperty);
				if (value instanceof Direction direction) {
					return direction;
				}
			}
		}
		return Direction.NORTH;
	}

	public ItemStack item(Level level) {
		if (this.isBlock()) {
			BlockEntity entity = level.getBlockEntity(this.pos);
			if (entity instanceof net.minecraft.world.Container container && container.getContainerSize() > 0) {
				return container.getItem(0);
			}
			return ItemStack.EMPTY;
		}
		Entity entity = level.getEntity(this.entityId);
		return entity instanceof ItemFrame frame ? frame.getItem() : ItemStack.EMPTY;
	}

	public boolean isEmpty(Level level) {
		return this.item(level).isEmpty();
	}

	/** Puts a map in the frame, hides the frame, and sets the rotation. */
	public boolean place(Level level, ItemStack stack, int rotation) {
		if (this.isBlock()) {
			BlockEntity blockEntity = level.getBlockEntity(this.pos);
			if (!(blockEntity instanceof net.minecraft.world.Container container) || container.getContainerSize() == 0) {
				return false;
			}
			container.setItem(0, stack.copyWithCount(1));
			if (blockEntity instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
				be.setChanged();
			}
			BlockState state = level.getBlockState(this.pos);
			state = setBool(state, "invisible", true);
			state = setInt(state, "rotation", rotation & 7);
			level.setBlock(this.pos, state, 3);
			if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
				serverLevel.sendBlockUpdated(this.pos, state, state, 3);
			}
			return true;
		}
		Entity entity = level.getEntity(this.entityId);
		if (!(entity instanceof ItemFrame frame)) {
			return false;
		}
		frame.setItem(stack.copyWithCount(1));
		frame.setRotation(rotation);
		frame.setInvisible(true);
		return true;
	}

	public void clear(Level level) {
		if (this.isBlock()) {
			BlockEntity blockEntity = level.getBlockEntity(this.pos);
			if (blockEntity instanceof net.minecraft.world.Container container && container.getContainerSize() > 0) {
				container.setItem(0, ItemStack.EMPTY);
				blockEntity.setChanged();
			}
			BlockState state = level.getBlockState(this.pos);
			state = setBool(state, "invisible", false);
			level.setBlock(this.pos, state, 3);
			if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
				serverLevel.sendBlockUpdated(this.pos, state, state, 3);
			}
			return;
		}
		Entity entity = level.getEntity(this.entityId);
		if (entity instanceof ItemFrame frame) {
			frame.setItem(ItemStack.EMPTY, true);
			frame.setInvisible(false);
		}
	}

	public boolean isGlowing(Level level) {
		if (this.isBlock()) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.pos).getBlock());
			return FAST_GLOW_ITEM_FRAME.equals(id);
		}
		return level.getEntity(this.entityId) instanceof GlowItemFrame;
	}

	private static BlockState setBool(BlockState state, String name, boolean value) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name) && property instanceof BooleanProperty bool) {
				return state.setValue(bool, value);
			}
		}
		return state;
	}

	@SuppressWarnings("unchecked")
	private static BlockState setInt(BlockState state, String name, int value) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)
				&& property instanceof net.minecraft.world.level.block.state.properties.IntegerProperty integerProperty) {
				if (integerProperty.getPossibleValues().contains(value)) {
					return state.setValue((net.minecraft.world.level.block.state.properties.IntegerProperty) property, value);
				}
			}
		}
		return state;
	}

	/**
	 * Works out which way is right and which way is down for a wall, given the clicked frame and
	 * where the player is standing. Matches vanilla rotation so ceiling walls still read right.
	 */
	public static WallAxes axes(FrameRef origin, Direction playerFacing) {
		Direction facing = origin.facing();
		Direction right;
		Direction down;
		int rotation;
		if (facing.getAxis() != Direction.Axis.Y) {
			right = facing.getCounterClockWise();
			down = Direction.DOWN;
			rotation = 0;
		} else {
			right = playerFacing.getClockWise();
			if (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
				down = right.getClockWise();
				rotation = playerFacing.getOpposite().get2DDataValue();
			} else {
				down = right.getCounterClockWise();
				rotation = (right.getAxis() == Direction.Axis.Z ? playerFacing : playerFacing.getOpposite()).get2DDataValue();
			}
		}
		return new WallAxes(right, down, rotation);
	}

	public record WallAxes(Direction right, Direction down, int rotation) {
	}

	/** Returns the positions covered by a wall. */
	public static List<BlockPos> wallPositions(BlockPos origin, WallAxes axes, int tilesX, int tilesY) {
		long count = (long) tilesX * tilesY;
		if (count > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Wall has too many frames to place");
		}
		List<BlockPos> positions = new ArrayList<>((int) count);
		BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
		for (int y = 0; y < tilesY; y++) {
			for (int x = 0; x < tilesX; x++) {
				mut.set(origin);
				mut.move(axes.right(), x);
				mut.move(axes.down(), y);
				positions.add(mut.immutable());
			}
		}
		return positions;
	}
}
