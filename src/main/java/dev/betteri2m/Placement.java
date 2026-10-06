package dev.betteri2m;

import dev.betteri2m.render.Bi2mData;
import dev.betteri2m.render.MapTiler;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;

/**
 * Placing a wall from a bundle and picking it back up. The bundle records the wall size and
 * each map records its own column and row, so placing is a lookup from map slot to frame; a
 * frame that's already occupied or missing is skipped rather than blocking the whole wall.
 */
public final class Placement {
	private Placement() {
	}

	public record Result(int placed, int skipped, int missing, int total) {
		public boolean complete() {
			return this.placed == this.total && this.missing == 0 && this.skipped == 0;
		}
	}

	/** True when this stack is one of our bundles that hasn't been placed yet. */
	public static boolean isQuickPlaceBundle(ItemStack stack) {
		if (!stack.is(net.minecraft.world.item.Items.BUNDLE)) {
			return false;
		}
		Bi2mData data = MapTiler.read(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
		return data != null && data.quickPlace() && data.isTile();
	}

	public static Result place(Player player, ItemStack bundle, ServerLevel level, FrameRef origin) {
		Bi2mData bundleData = MapTiler.read(bundle.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
		if (bundleData == null || !bundleData.isTile()) {
			return new Result(0, 0, 0, 0);
		}
		int tilesX = bundleData.width();
		int tilesY = bundleData.height();
		BundleContents contents = bundle.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
		int total = contents.size();

		FrameRef.WallAxes axes = FrameRef.axes(origin, player.getDirection());
		List<BlockPos> positions = FrameRef.wallPositions(origin.pos(), axes, tilesX, tilesY);

		int placed = 0;
		int skipped = 0;
		int missing = 0;
		for (int slot = 0; slot < positions.size(); slot++) {
			BlockPos pos = positions.get(slot);
			FrameRef target = FrameRef.find(level, pos, origin.facing());
			if (target == null) {
				missing++;
				continue;
			}
			if (!target.isEmpty(level)) {
				skipped++;
				continue;
			}
			ItemStack map = mapForSlot(contents, slot);
			if (map.isEmpty()) {
				missing++;
				continue;
			}
			Bi2mData tile = MapTiler.read(map.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
			if (tile == null) {
				missing++;
				continue;
			}
			Bi2mData stamped = tile.withFrame(axes.right(), axes.down(), origin.facing());
			ItemStack toPlace = map.copyWithCount(1);
			toPlace.set(DataComponents.CUSTOM_DATA, MapTiler.customData(stamped));
			if (target.place(level, toPlace, axes.rotation())) {
				placed++;
			} else {
				missing++;
			}
		}

		if (placed > 0 && !player.hasInfiniteMaterials()) {
			bundle.shrink(1);
		}
		return new Result(placed, skipped, missing, total);
	}

	/** Bundle contents are in the order the tiler wrote them, so the slot maps straight onto the wall. */
	private static ItemStack mapForSlot(BundleContents contents, int slot) {
		List<net.minecraft.world.item.ItemStackTemplate> items = contents.items();
		if (slot < 0 || slot >= items.size()) {
			return ItemStack.EMPTY;
		}
		return items.get(slot).create();
	}

	/** Clears the whole wall that the given frame belongs to. */
	public static int clearWall(ServerLevel level, FrameRef frame) {
		ItemStack item = frame.item(level);
		Bi2mData data = MapTiler.read(item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
		if (data == null || !data.isTile() || !data.isPlaced()) {
			return 0;
		}
		int tilesX = data.width();
		int tilesY = data.height();
		FrameRef.WallAxes axes = new FrameRef.WallAxes(data.right().get(), data.down().get(), 0);
		BlockPos origin = frame.pos()
			.relative(data.right().get(), -data.x())
			.relative(data.down().get(), -data.y());
		List<BlockPos> positions = FrameRef.wallPositions(origin, axes, tilesX, tilesY);
		int cleared = 0;
		for (BlockPos pos : positions) {
			FrameRef target = FrameRef.find(level, pos, data.facing().get());
			if (target == null) {
				continue;
			}
			ItemStack targetItem = target.item(level);
			Bi2mData targetData = MapTiler.read(targetItem.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY));
			if (targetData != null && targetData.isTile()) {
				target.clear(level);
				cleared++;
			}
		}
		return cleared;
	}

	public static Component describe(Result result) {
		if (result.total() == 0) {
			return Component.literal("Nothing to place");
		}
		StringBuilder text = new StringBuilder("Placed ").append(result.placed()).append(" of ").append(result.total());
		if (result.skipped() > 0) {
			text.append(", ").append(result.skipped()).append(" frames already had something in them");
		}
		if (result.missing() > 0) {
			text.append(", ").append(result.missing()).append(" frames were missing");
		}
		return Component.literal(text.toString());
	}
}
