package dev.betteri2m.render;

/** A converted image split into map tiles. */
public record TileSet(byte[] colors, int width, int height, int tilesX, int tilesY, int padX, int padY) {
	/** Returns a pixel from a tile, or -1 when it falls outside the image. */
	public int tilePixel(int tileX, int tileY, int localX, int localY) {
		int x = tileX * 128 + localX - this.padX;
		int y = tileY * 128 + localY - this.padY;
		if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
			return -1;
		}
		return this.colors[y * this.width + x] & 0xFF;
	}

	/** Splits an image into whole map tiles. */
	public static TileSet of(byte[] colors, int width, int height) {
		int tilesX = (int) (((long) width + 127) / 128);
		int tilesY = (int) (((long) height + 127) / 128);
		int padX = (int) (((long) tilesX * 128 - width) / 2);
		int padY = (int) (((long) tilesY * 128 - height) / 2);
		Math.multiplyExact(tilesX, tilesY);
		return new TileSet(colors, width, height, tilesX, tilesY, padX, padY);
	}

	/** Returns the number of tiles. */
	public int tileCount() {
		return Math.multiplyExact(this.tilesX, this.tilesY);
	}
}
