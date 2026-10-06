package dev.betteri2m.render;

import java.awt.image.BufferedImage;

/** Resamples images in linear light while preserving alpha coverage. */
public final class Resampler {
	/** Number of channels per pixel. */
	public static final int STRIDE = 4;

	private Resampler() {
	}

	/** Resamples an image and keeps its alpha channel. */
	public static BufferedImage scale(BufferedImage source, int width, int height) {
		return fromLinear(resample(source, width, height), width, height);
	}

	/** Scales an image to the requested dimensions. */
	public static float[] resample(BufferedImage source, int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Target size must be positive, got " + width + "x" + height);
		}
		int sourceWidth = source.getWidth();
		int sourceHeight = source.getHeight();
		float[] pixels = toPremultiplied(source);

		if (width != sourceWidth) {
			pixels = resizeHorizontal(pixels, sourceWidth, sourceHeight, width);
		}
		if (height != sourceHeight) {
			pixels = resizeVertical(pixels, width, sourceHeight, height);
		}
		double reduction = Math.max((double) sourceWidth / width, (double) sourceHeight / height);
		if (reduction > 1.0) {
			float strength = (float) Math.max(0.15, Math.min(0.25,
				0.30 - 0.03 * (Math.log(reduction) / Math.log(2.0))));
			pixels = sharpen(pixels, width, height, strength);
		}
		return pixels;
	}

	/** Reads pixels as premultiplied linear RGB and alpha. */
	public static float[] toPremultiplied(BufferedImage source) {
		int width = source.getWidth();
		int height = source.getHeight();
		float[] out = new float[width * height * STRIDE];
		int[] row = new int[width];
		for (int y = 0; y < height; y++) {
			source.getRGB(0, y, width, 1, row, 0, width);
			for (int x = 0; x < width; x++) {
				int argb = row[x];
				float coverage = (argb >>> 24) / 255.0F;
				int index = (y * width + x) * STRIDE;
				out[index] = ColorSpace.srgbToLinear((argb >> 16 & 0xFF) / 255.0F) * coverage;
				out[index + 1] = ColorSpace.srgbToLinear((argb >> 8 & 0xFF) / 255.0F) * coverage;
				out[index + 2] = ColorSpace.srgbToLinear((argb & 0xFF) / 255.0F) * coverage;
				out[index + 3] = coverage;
			}
		}
		return out;
	}

	public static BufferedImage fromLinear(float[] premultiplied, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] row = new int[width];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int index = (y * width + x) * STRIDE;
				float coverage = coverage(premultiplied[index + 3]);
				float scale = coverage > 0.0F ? 1.0F / coverage : 0.0F;
				row[x] = clampByte(coverage) << 24
					| clampByte(ColorSpace.linearToSrgb(premultiplied[index] * scale)) << 16
					| clampByte(ColorSpace.linearToSrgb(premultiplied[index + 1] * scale)) << 8
					| clampByte(ColorSpace.linearToSrgb(premultiplied[index + 2] * scale));
			}
			image.setRGB(0, y, width, 1, row, 0, width);
		}
		return image;
	}

	/** Keeps alpha coverage within its valid range. */
	static float coverage(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	private static int clampByte(float value) {
		return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
	}

	private static float[] resizeHorizontal(float[] source, int sourceWidth, int sourceHeight, int targetWidth) {
		float[] out = new float[targetWidth * sourceHeight * STRIDE];
		boolean shrink = targetWidth < sourceWidth;
		boolean whole = !shrink && targetWidth % sourceWidth == 0;
		double ratio = (double) sourceWidth / targetWidth;
		double filterScale = Math.max(1.0, ratio);
		for (int y = 0; y < sourceHeight; y++) {
			int sourceRow = y * sourceWidth * STRIDE;
			int targetRow = y * targetWidth * STRIDE;
			for (int x = 0; x < targetWidth; x++) {
				int target = targetRow + x * STRIDE;
				if (whole) {
					int sourceX = x / (targetWidth / sourceWidth);
					System.arraycopy(source, sourceRow + sourceX * STRIDE, out, target, STRIDE);
					continue;
				}
				double center = (x + 0.5) * ratio - 0.5;
				int first = shrink ? (int) Math.ceil(center - 3.0 * filterScale) : (int) Math.floor(center) - 1;
				int last = shrink ? (int) Math.floor(center + 3.0 * filterScale) : first + 3;
				double red = 0.0;
				double green = 0.0;
				double blue = 0.0;
				double alpha = 0.0;
				double weightSum = 0.0;
				for (int sample = first; sample <= last; sample++) {
					double distance = center - sample;
					double weight = shrink ? lanczos(distance / filterScale) : cubic(distance);
					int sourceX = Math.max(0, Math.min(sourceWidth - 1, sample));
					int index = sourceRow + sourceX * STRIDE;
					red += source[index] * weight;
					green += source[index + 1] * weight;
					blue += source[index + 2] * weight;
					alpha += source[index + 3] * weight;
					weightSum += weight;
				}
				writePixel(out, target, red, green, blue, alpha, weightSum);
			}
		}
		return out;
	}

	private static float[] resizeVertical(float[] source, int width, int sourceHeight, int targetHeight) {
		float[] out = new float[width * targetHeight * STRIDE];
		boolean shrink = targetHeight < sourceHeight;
		boolean whole = !shrink && targetHeight % sourceHeight == 0;
		double ratio = (double) sourceHeight / targetHeight;
		double filterScale = Math.max(1.0, ratio);
		for (int y = 0; y < targetHeight; y++) {
			int targetRow = y * width * STRIDE;
			if (whole) {
				int sourceY = y / (targetHeight / sourceHeight);
				System.arraycopy(source, sourceY * width * STRIDE, out, targetRow, width * STRIDE);
				continue;
			}
			double center = (y + 0.5) * ratio - 0.5;
			int first = shrink ? (int) Math.ceil(center - 3.0 * filterScale) : (int) Math.floor(center) - 1;
			int last = shrink ? (int) Math.floor(center + 3.0 * filterScale) : first + 3;
			for (int x = 0; x < width; x++) {
				int target = targetRow + x * STRIDE;
				double red = 0.0;
				double green = 0.0;
				double blue = 0.0;
				double alpha = 0.0;
				double weightSum = 0.0;
				for (int sample = first; sample <= last; sample++) {
					double distance = center - sample;
					double weight = shrink ? lanczos(distance / filterScale) : cubic(distance);
					int sourceY = Math.max(0, Math.min(sourceHeight - 1, sample));
					int index = (sourceY * width + x) * STRIDE;
					red += source[index] * weight;
					green += source[index + 1] * weight;
					blue += source[index + 2] * weight;
					alpha += source[index + 3] * weight;
					weightSum += weight;
				}
				writePixel(out, target, red, green, blue, alpha, weightSum);
			}
		}
		return out;
	}

	private static float[] sharpen(float[] source, int width, int height, float strength) {
		float[] out = source.clone();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int center = (y * width + x) * STRIDE;
				float alpha = source[center + 3];
				for (int channel = 0; channel < 3; channel++) {
					float sum = source[center + channel] * 4.0F;
					for (int dy = -1; dy <= 1; dy++) {
						int sy = Math.max(0, Math.min(height - 1, y + dy));
						for (int dx = -1; dx <= 1; dx++) {
							int sx = Math.max(0, Math.min(width - 1, x + dx));
							if (dx == 0 && dy == 0) {
								continue;
							}
							int weight = dx == 0 || dy == 0 ? 2 : 1;
						int sample = (sy * width + sx) * STRIDE + channel;
						sum += source[sample] * weight;
						}
					}
					float blur = sum / 16.0F;
					out[center + channel] = Math.max(0.0F,
						Math.min(alpha, source[center + channel] + strength * (source[center + channel] - blur)));
				}
			}
		}
		return out;
	}

	private static double lanczos(double value) {
		double x = Math.abs(value);
		if (x == 0.0) {
			return 1.0;
		}
		if (x >= 3.0) {
			return 0.0;
		}
		return sinc(x) * sinc(x / 3.0);
	}

	private static double sinc(double value) {
		return value == 0.0 ? 1.0 : Math.sin(Math.PI * value) / (Math.PI * value);
	}

	private static double cubic(double value) {
		double x = Math.abs(value);
		if (x < 1.0) {
			return 1.5 * x * x * x - 2.5 * x * x + 1.0;
		}
		if (x < 2.0) {
			return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
		}
		return 0.0;
	}

	private static void writePixel(float[] out, int index, double red, double green, double blue,
			double alpha, double weightSum) {
		if (Math.abs(weightSum) < 1.0E-12) {
			weightSum = 1.0;
		}
		float a = coverage((float) (alpha / weightSum));
		out[index] = Math.max(0.0F, Math.min(a, (float) (red / weightSum)));
		out[index + 1] = Math.max(0.0F, Math.min(a, (float) (green / weightSum)));
		out[index + 2] = Math.max(0.0F, Math.min(a, (float) (blue / weightSum)));
		out[index + 3] = a;
	}
}
