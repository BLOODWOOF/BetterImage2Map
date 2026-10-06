package dev.betteri2m.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Builds the filled maps from a converted image. Each tile gets its own map id, since map ids
 * are the unit of saving and syncing, and records its position in the wall.
 */
public final class MapTiler {
	private static final ResourceKey<Level> GENERATED_DIMENSION =
		ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("betterimage2map", "generated"));

	private MapTiler() {
	}

	public static List<ItemStackTemplate> build(TileSet tiles, ServerLevel level, String source) {
		List<ItemStackTemplate> items = new ArrayList<>(tiles.tileCount());
		for (int tileY = 0; tileY < tiles.tilesY(); tileY++) {
			for (int tileX = 0; tileX < tiles.tilesX(); tileX++) {
				items.add(buildTile(tiles, level, tileX, tileY, source));
			}
		}
		return items;
	}

	private static ItemStackTemplate buildTile(TileSet tiles, ServerLevel level, int tileX, int tileY, String source) {
		MapId id = level.getFreeMapId();
		MapItemSavedData data = MapItemSavedData.createFresh(0.0, 0.0, (byte) 0, false, false, GENERATED_DIMENSION);
		byte[] colors = data.colors;
		for (int localY = 0; localY < 128; localY++) {
			for (int localX = 0; localX < 128; localX++) {
				int packed = tiles.tilePixel(tileX, tileY, localX, localY);
				colors[localX + localY * 128] = packed < 0 ? 0 : (byte) packed;
			}
		}
		level.setMapData(id, data);

		Bi2mData tile = Bi2mData.ofTile(tileX, tileY, tiles.tilesX(), tiles.tilesY(), source);
		DataComponentPatch patch = DataComponentPatch.builder()
			.set(DataComponents.MAP_ID, id)
			.set(DataComponents.CUSTOM_DATA, customData(tile))
			.set(DataComponents.ITEM_NAME, Component.literal(displayName(source)).withStyle(ChatFormatting.GOLD))
			.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal((tileX + 1) + " / " + (tileY + 1) + "  of  " + tiles.tilesX() + " x " + tiles.tilesY())
					.withStyle(ChatFormatting.GRAY),
				Component.literal(shortSource(source)).withStyle(ChatFormatting.DARK_GRAY)
			)))
			.build();
		return new ItemStackTemplate(Items.FILLED_MAP, patch);
	}

	public static CustomData customData(Bi2mData data) {
		CompoundTag tag = Bi2mData.CODEC.codec()
			.encodeStart(NbtOps.INSTANCE, data)
			.result()
			.flatMap(net.minecraft.nbt.Tag::asCompound)
			.orElseGet(CompoundTag::new);
		return CustomData.of(tag);
	}

	/** Reads this mod's data off a stack, or null when it didn't come from here. */
	public static Bi2mData read(ItemStackTemplate stack) {
		return read(stack.get(DataComponents.CUSTOM_DATA));
	}

	public static Bi2mData read(CustomData data) {
		if (data == null || data.isEmpty()) {
			return null;
		}
		return data.copyTag().read(Bi2mData.CODEC).orElse(null);
	}

	private static String displayName(String source) {
		if (source == null || source.isBlank()) {
			return "Map";
		}
		String name = dev.betteri2m.store.ImageSources.fileName(source);
		return name.length() > 32 ? name.substring(0, 31) + "\u2026" : name;
	}

	private static String shortSource(String source) {
		if (source == null || source.isBlank()) {
			return "";
		}
		return source.length() > 48 ? source.substring(0, 47) + "\u2026" : source;
	}
}
