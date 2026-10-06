package dev.betteri2m.render;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.Direction;

/**
 * Placement data carried by every converted map and by the bundle holding a wall. Fields are
 * namespaced because they share the item's custom data with whatever else is on the stack. On
 * a map, {@code width}/{@code height} are the tile grid size; on a bundle they describe the
 * wall, and {@code quickPlace} marks it as placeable by this mod.
 */
public record Bi2mData(
	int x,
	int y,
	int width,
	int height,
	boolean quickPlace,
	Optional<Direction> right,
	Optional<Direction> down,
	Optional<Direction> facing,
	Optional<String> source
) {
	public static final MapCodec<Bi2mData> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Codec.INT.optionalFieldOf("betterimage2map:x", 0).forGetter(Bi2mData::x),
		Codec.INT.optionalFieldOf("betterimage2map:y", 0).forGetter(Bi2mData::y),
		Codec.INT.optionalFieldOf("betterimage2map:width", 0).forGetter(Bi2mData::width),
		Codec.INT.optionalFieldOf("betterimage2map:height", 0).forGetter(Bi2mData::height),
		Codec.BOOL.optionalFieldOf("betterimage2map:quick_place", false).forGetter(Bi2mData::quickPlace),
		Direction.CODEC.optionalFieldOf("betterimage2map:right").forGetter(Bi2mData::right),
		Direction.CODEC.optionalFieldOf("betterimage2map:down").forGetter(Bi2mData::down),
		Direction.CODEC.optionalFieldOf("betterimage2map:facing").forGetter(Bi2mData::facing),
		Codec.STRING.optionalFieldOf("betterimage2map:source").forGetter(Bi2mData::source)
	).apply(instance, Bi2mData::new));

	public static Bi2mData ofTile(int x, int y, int tilesX, int tilesY, String source) {
		return new Bi2mData(x, y, tilesX, tilesY, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(source));
	}

	public static Bi2mData ofBundle(int tilesX, int tilesY, String source) {
		return new Bi2mData(0, 0, tilesX, tilesY, true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(source));
	}

	public Bi2mData withFrame(Direction right, Direction down, Direction facing) {
		return new Bi2mData(this.x, this.y, this.width, this.height, this.quickPlace,
			Optional.of(right), Optional.of(down), Optional.of(facing), this.source);
	}

	public boolean isTile() {
		return this.width > 0 && this.height > 0;
	}

	public boolean isPlaced() {
		return this.right.isPresent() && this.down.isPresent() && this.facing.isPresent();
	}
}
