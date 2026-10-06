package dev.betteri2m.render;

/** Output dimensions and whole map layout. */
public record RenderSpec(int tilesX, int tilesY, int outputWidth, int outputHeight, Quantizer.Mode dither,
		int cropX, int cropY, int cropW, int cropH, Background background) {
	public static final int TILE = 128;

	public static RenderSpec of(int tilesX, int tilesY, Quantizer.Mode dither) {
		return of(tilesX, tilesY, dither, Background.WHITE);
	}

	public static RenderSpec of(int tilesX, int tilesY, Quantizer.Mode dither, Background background) {
		if (tilesX <= 0 || tilesY <= 0) {
			throw new IllegalArgumentException("Frame dimensions must be positive");
		}
		try {
			return ofPixels(Math.multiplyExact(tilesX, TILE), Math.multiplyExact(tilesY, TILE), dither, background);
		} catch (ArithmeticException error) {
			throw new IllegalArgumentException("Requested frame dimensions are too large", error);
		}
	}

	public static RenderSpec ofPixels(int width, int height, Quantizer.Mode dither, Background background) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Image dimensions must be positive");
		}
		try {
			int pixels = Math.multiplyExact(width, height);
			Math.multiplyExact(pixels, Resampler.STRIDE);
		} catch (ArithmeticException error) {
			throw new IllegalArgumentException("Requested image dimensions are too large", error);
		}
		long tilesXLong = ((long) width + TILE - 1) / TILE;
		long tilesYLong = ((long) height + TILE - 1) / TILE;
		if (tilesXLong * tilesYLong > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Requested wall has too many maps");
		}
		int tilesX = (int) tilesXLong;
		int tilesY = (int) tilesYLong;
		return new RenderSpec(tilesX, tilesY, width, height, dither, 0, 0, 0, 0, background);
	}

	public int pixelWidth() {
		return this.outputWidth;
	}

	public int pixelHeight() {
		return this.outputHeight;
	}

	public int framesWide() {
		return this.tilesX;
	}

	public int framesTall() {
		return this.tilesY;
	}

	/** Returns the number of item frames. */
	public int frameCount() {
		return Math.multiplyExact(this.tilesX, this.tilesY);
	}

	/** Formats the frame dimensions. */
	public String describeFrames() {
		if (this.frameCount() == 1) {
			return "1 frame";
		}
		return this.tilesX + " x " + this.tilesY + " frames (" + this.frameCount() + " total)";
	}

	public boolean hasCrop() {
		return this.cropW > 0 && this.cropH > 0;
	}

	public RenderSpec withNormalizedCrop(double x, double y, double width, double height, int sourceWidth, int sourceHeight) {
		int px = (int) Math.round(x * sourceWidth);
		int py = (int) Math.round(y * sourceHeight);
		int pw = (int) Math.round(width * sourceWidth);
		int ph = (int) Math.round(height * sourceHeight);
		if (pw <= 0 || ph <= 0) {
			return this;
		}
		return this.withCrop(px, py, pw, ph);
	}

	public RenderSpec withCrop(int x, int y, int width, int height) {
		return new RenderSpec(this.tilesX, this.tilesY, this.outputWidth, this.outputHeight,
			this.dither, x, y, width, height, this.background);
	}

	public RenderSpec withTiles(int tilesX, int tilesY) {
		return of(tilesX, tilesY, this.dither, this.background)
			.withCrop(this.cropX, this.cropY, this.cropW, this.cropH);
	}

	public RenderSpec withDither(Quantizer.Mode dither) {
		return new RenderSpec(this.tilesX, this.tilesY, this.outputWidth, this.outputHeight,
			dither, this.cropX, this.cropY, this.cropW, this.cropH, this.background);
	}

	public RenderSpec withBackground(Background background) {
		return new RenderSpec(this.tilesX, this.tilesY, this.outputWidth, this.outputHeight,
			this.dither, this.cropX, this.cropY, this.cropW, this.cropH, background);
	}
}
