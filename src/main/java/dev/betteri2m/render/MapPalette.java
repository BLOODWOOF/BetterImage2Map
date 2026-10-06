package dev.betteri2m.render;

import net.minecraft.world.level.material.MapColor;

/**
 * The 244 colors a filled map can display, as ARGB plus OKLab.
 * <p>
 * Matching happens in OKLab with equal lightness and chroma weights; against vanilla's raw RGB
 * match that lowers error on saturated content (measured by VerifyAccuracy).
 */
public final class MapPalette {
	/** Lightness weight. Kept at 1.0 as the reference. */
	public static final float L_WEIGHT = 1.0F;
	/**
	 * Chroma weight in the perceptual palette distance.
	 */
	public static final float C_WEIGHT = 1.0F;

	private static final byte[] PACKED = new byte[256];
	private static final int[] ARGB = new int[256];
	private static final float[] OKLAB = new float[256 * 3];
	/** Base map color id of each entry; every family has four brightness levels. */
	private static final int[] FAMILY = new int[256];
	private static final int[] SHADE = new int[256];
	private static final int[][] MEMBERS = new int[64][];
	private static final int COUNT;
	private static final int FAMILY_COUNT;

	static {
		int count = 0;
		for (int id = 0; id < 64; id++) {
			MapColor color = MapColor.byId(id);
			// NONE is transparent, and ids 62/63 map back to it.
			if (color == MapColor.NONE) {
				continue;
			}
			int shade = 0;
			for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
				int argb = color.calculateARGBColor(brightness);
				PACKED[count] = color.getPackedId(brightness);
				ARGB[count] = argb;
				FAMILY[count] = id;
				SHADE[count] = shade++;
				float[] lab = ColorSpace.linearToOklab(
					ColorSpace.srgbToLinear(argb >> 16 & 0xFF),
					ColorSpace.srgbToLinear(argb >> 8 & 0xFF),
					ColorSpace.srgbToLinear(argb & 0xFF)
				);
				OKLAB[count * 3] = lab[0];
				OKLAB[count * 3 + 1] = lab[1];
				OKLAB[count * 3 + 2] = lab[2];
				count++;
			}
		}
		COUNT = count;
		int[] sizes = new int[64];
		for (int i = 0; i < count; i++) {
			sizes[FAMILY[i]]++;
		}
		int families = 0;
		for (int id = 0; id < 64; id++) {
			MEMBERS[id] = new int[sizes[id]];
			if (sizes[id] > 0) {
				families++;
			}
		}
		int[] at = new int[64];
		for (int i = 0; i < count; i++) {
			MEMBERS[FAMILY[i]][at[FAMILY[i]]++] = i;
		}
		FAMILY_COUNT = families;
	}

	private MapPalette() {
	}

	public static int count() {
		return COUNT;
	}

	public static byte packed(int index) {
		return PACKED[index];
	}

	public static int argb(int index) {
		return ARGB[index];
	}

	/** Weighted squared OKLab distance. */
	public static float distance(int index, float l, float a, float b) {
		float dl = (OKLAB[index * 3] - l) * L_WEIGHT;
		float da = (OKLAB[index * 3 + 1] - a) * C_WEIGHT;
		float db = (OKLAB[index * 3 + 2] - b) * C_WEIGHT;
		return dl * dl + da * da + db * db;
	}

	public static int nearest(float l, float a, float b) {
		int best = 0;
		float bestDistance = Float.MAX_VALUE;
		for (int i = 0; i < COUNT; i++) {
			float distance = distance(i, l, a, b);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	public static float[] oklabOf(int index) {
		return new float[] {OKLAB[index * 3], OKLAB[index * 3 + 1], OKLAB[index * 3 + 2]};
	}

	/** Direct OKLab storage, {L, a, b} per entry; do not modify. */
	public static float[] oklabTable() {
		return OKLAB;
	}

	/** Base map color id of an entry, grouping its four brightness levels. */
	public static int familyOf(int index) {
		return FAMILY[index];
	}

	/** Brightness ordinal of an entry inside its family. */
	public static int shadeOf(int index) {
		return SHADE[index];
	}

	/** How many base map colors actually carry a usable color. */
	public static int familyCount() {
		return FAMILY_COUNT;
	}

	/** Entry indices of one family, empty for unused ids. */
	public static int[] familyMembers(int family) {
		return MEMBERS[family];
	}

	/** Palette index for a packed map byte, or -1 if the byte isn't a real color. */
	public static int indexOf(byte packed) {
		for (int i = 0; i < COUNT; i++) {
			if (PACKED[i] == packed) {
				return i;
			}
		}
		return -1;
	}
}
