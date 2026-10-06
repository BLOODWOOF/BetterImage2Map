package dev.betteri2m.render;

/**
 * Conversions the palette search and the resampler need. Everything downstream works in linear
 * light: averaging gamma-encoded pixels darkens the result and flattens fine detail, which is a
 * common reason a converted map looks worse than the picture that went in.
 */
public final class ColorSpace {
	private ColorSpace() {
	}

	public static float srgbToLinear(float channel) {
		return channel <= 0.04045F ? channel / 12.92F : (float) Math.pow((channel + 0.055F) / 1.055F, 2.4D);
	}

	public static float linearToSrgb(float channel) {
		float c = Math.max(0.0F, channel);
		return c <= 0.0031308F ? c * 12.92F : 1.055F * (float) Math.pow(c, 1.0D / 2.4D) - 0.055F;
	}

	public static float srgbToLinear(int byteValue) {
		return srgbToLinear(byteValue / 255.0F);
	}

	/** Returns {L, a, b} from linear sRGB. */
	public static float[] linearToOklab(float r, float g, float b) {
		float l = 0.4122214708F * r + 0.5363325363F * g + 0.0514459929F * b;
		float m = 0.2119034982F * r + 0.6806995451F * g + 0.1073969566F * b;
		float s = 0.0883024619F * r + 0.2817188376F * g + 0.6299787005F * b;
		float lc = cbrt(l);
		float mc = cbrt(m);
		float sc = cbrt(s);
		return new float[] {
			0.2104542553F * lc + 0.7936177850F * mc - 0.0040720468F * sc,
			1.9779984951F * lc - 2.4285922050F * mc + 0.4505937099F * sc,
			0.0259040371F * lc + 0.7827717662F * mc - 0.8086757660F * sc
		};
	}

	private static float cbrt(float value) {
		return value < 0.0F ? -(float) Math.cbrt(-value) : (float) Math.cbrt(value);
	}
}
