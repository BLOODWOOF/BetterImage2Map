package dev.betteri2m.render;

import dev.betteri2m.Bi2mConfig;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** Converts an image into map tiles. */
public final class Conversion {
	private static final AtomicLong RESERVED_BYTES = new AtomicLong();

	private Conversion() {
	}

	public static CompletableFuture<TileSet> convert(BufferedImage image, RenderSpec spec, Bi2mConfig config, Executor executor) {
		if (image == null || spec == null || spec.dither() == null || spec.background() == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Image conversion needs an image and render settings"));
		}
		long reservation;
		try {
			reservation = reserve(image, spec);
		} catch (IllegalArgumentException error) {
			return CompletableFuture.failedFuture(error);
		}
		try {
			return CompletableFuture.supplyAsync(() -> {
				try {
					BufferedImage source = spec.hasCrop() ? crop(image, spec) : image;
					int width = spec.pixelWidth();
					int height = spec.pixelHeight();
					float[] pixels = Resampler.resample(source, width, height);
					int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
					byte[] colors = Quantizer.quantize(pixels, width, height, spec.dither(), workers, spec.background());
					return TileSet.of(colors, width, height);
				} finally {
					RESERVED_BYTES.addAndGet(-reservation);
				}
			}, executor);
		} catch (RuntimeException error) {
			RESERVED_BYTES.addAndGet(-reservation);
			return CompletableFuture.failedFuture(error);
		}
	}

	private static long reserve(BufferedImage image, RenderSpec spec) {
		long outputPixels = (long) spec.pixelWidth() * spec.pixelHeight();
		long sourcePixels = (long) image.getWidth() * image.getHeight();
		long bytes;
		try {
			bytes = Math.addExact(Math.multiplyExact(outputPixels, 40L), Math.multiplyExact(sourcePixels, 16L));
		} catch (ArithmeticException error) {
			throw new IllegalArgumentException("Requested image is too large to convert", error);
		}
		Runtime runtime = Runtime.getRuntime();
		long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
		long budget = Math.max(0L, Math.min(runtime.maxMemory() / 2, available / 2));
		while (true) {
			long reserved = RESERVED_BYTES.get();
			if (reserved > budget || bytes > budget - reserved) {
				throw new IllegalArgumentException("Requested image is too large for available memory");
			}
			if (RESERVED_BYTES.compareAndSet(reserved, reserved + bytes)) {
				return bytes;
			}
		}
	}

	private static BufferedImage crop(BufferedImage image, RenderSpec spec) {
		int x = Math.max(0, Math.min(image.getWidth() - 1, spec.cropX()));
		int y = Math.max(0, Math.min(image.getHeight() - 1, spec.cropY()));
		int width = Math.max(1, Math.min(image.getWidth() - x, spec.cropW()));
		int height = Math.max(1, Math.min(image.getHeight() - y, spec.cropH()));
		return image.getSubimage(x, y, width, height);
	}

	/** Fits the source dimensions to a whole-frame grid. */
	public static RenderSpec fit(BufferedImage image, Bi2mConfig config, Quantizer.Mode dither) {
		return RenderSpec.ofPixels(image.getWidth(), image.getHeight(), dither, Background.WHITE);
	}

	/** Resolves frame-unit dimensions, with zero meaning auto. */
	public static RenderSpec resolve(BufferedImage image, double framesX, double framesY, Bi2mConfig config,
			Quantizer.Mode dither) {
		if (!Double.isFinite(framesX) || !Double.isFinite(framesY) || framesX < 0.0 || framesY < 0.0) {
			throw new IllegalArgumentException("Frame sizes must be finite non-negative numbers");
		}
		boolean autoX = framesX == 0.0;
		boolean autoY = framesY == 0.0;
		if (autoX && autoY) {
			return fit(image, config, dither);
		}
		if (autoX) {
			int height = pixelsForFrames(framesY);
			int width = scaledPixels(height, (double) image.getWidth() / image.getHeight());
			return RenderSpec.ofPixels(width, height, dither, Background.WHITE);
		}
		if (autoY) {
			int width = pixelsForFrames(framesX);
			int height = scaledPixels(width, (double) image.getHeight() / image.getWidth());
			return RenderSpec.ofPixels(width, height, dither, Background.WHITE);
		}
		return RenderSpec.ofPixels(pixelsForFrames(framesX), pixelsForFrames(framesY), dither, Background.WHITE);
	}

	public static int pixelsForFrames(double frames) {
		if (!Double.isFinite(frames) || frames < 0.0) {
			throw new IllegalArgumentException("Frame size must be a finite non-negative number");
		}
		if (frames == 0.0) {
			return 0;
		}
		double pixels = frames * RenderSpec.TILE;
		if (!Double.isFinite(pixels) || pixels > Integer.MAX_VALUE - RenderSpec.TILE) {
			throw new IllegalArgumentException("Requested image dimension is too large");
		}
		return Math.max(1, (int) Math.round(pixels));
	}

	private static int scaledPixels(int pinnedPixels, double ratio) {
		double pixels = pinnedPixels * ratio;
		if (!Double.isFinite(pixels) || pixels > Integer.MAX_VALUE - RenderSpec.TILE) {
			throw new IllegalArgumentException("Requested image dimension is too large");
		}
		return Math.max(1, (int) Math.round(pixels));
	}
}
