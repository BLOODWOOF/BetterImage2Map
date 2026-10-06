package dev.betteri2m;

import dev.betteri2m.render.Bi2mData;
import dev.betteri2m.render.MapTiler;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Right click a frame with a bundle to place the whole wall, and shift-punch one to clear it.
 * A plain punch is left to vanilla so a single map can still be knocked out on its own.
 */
public final class Bi2mInteractions {
	private Bi2mInteractions() {
	}

	public static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof ItemFrame frame) || hand != InteractionHand.MAIN_HAND) {
				return InteractionResult.PASS;
			}
			return tryPlace(player, level, FrameRef.ofEntity(frame));
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || !FrameRef.isFastFrame(level, hit.getBlockPos())) {
				return InteractionResult.PASS;
			}
			FrameRef ref = FrameRef.find(level, hit.getBlockPos(), facingOf(level, hit.getBlockPos()));
			return ref == null ? InteractionResult.PASS : tryPlace(player, level, ref);
		});

		// Shift-punch clears the wall; bare punches stay with vanilla.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof ItemFrame frame) || !player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
			return tryClearWall(player, level, FrameRef.ofEntity(frame));
		});

		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!player.isShiftKeyDown() || !FrameRef.isFastFrame(level, pos)) {
				return InteractionResult.PASS;
			}
			FrameRef ref = FrameRef.find(level, pos, facingOf(level, pos));
			return ref == null ? InteractionResult.PASS : tryClearWall(player, level, ref);
		});
	}

	private static InteractionResult tryClearWall(Player player, Level level, FrameRef ref) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!holdsOurMap(level, ref) || !inRange(player, ref)) {
			return InteractionResult.PASS;
		}
		int cleared = Placement.clearWall(serverLevel, ref);
		if (cleared == 0) {
			return InteractionResult.PASS;
		}
		serverPlayer.sendSystemMessage(Component.literal("Cleared " + cleared + " maps"), true);
		return InteractionResult.SUCCESS_SERVER;
	}

	private static InteractionResult tryPlace(Player player, Level level, FrameRef ref) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getMainHandItem();
		if (!Placement.isQuickPlaceBundle(held)) {
			return InteractionResult.PASS;
		}
		if (!inRange(player, ref)) {
			return InteractionResult.PASS;
		}
		Placement.Result result = Placement.place(player, held, serverLevel, ref);
		if (result.total() == 0) {
			return InteractionResult.PASS;
		}
		serverPlayer.sendSystemMessage(Placement.describe(result), true);
		return InteractionResult.SUCCESS_SERVER;
	}

	/** True when the frame holds a placed map from this mod. */
	public static boolean holdsOurMap(Level level, FrameRef ref) {
		ItemStack item = ref.item(level);
		Bi2mData data = MapTiler.read(item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
		return data != null && data.isTile() && data.isPlaced();
	}

	private static boolean inRange(Player player, FrameRef ref) {
		double max = Bi2m.config().maxPlacementDistance;
		return player.distanceToSqr(Vec3.atCenterOf(ref.pos())) <= max * max + 1.0;
	}

	private static Direction facingOf(Level level, BlockPos pos) {
		var state = level.getBlockState(pos);
		for (var property : state.getProperties()) {
			if (property.getName().equals("facing")
				&& property instanceof net.minecraft.world.level.block.state.properties.EnumProperty<?> enumProperty) {
				Object value = state.getValue(enumProperty);
				if (value instanceof Direction direction) {
					return direction;
				}
			}
		}
		return Direction.NORTH;
	}
}
