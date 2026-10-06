package dev.betteri2m.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import dev.betteri2m.Bi2m;
import dev.betteri2m.net.Bi2mNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;

/**
 * Draws the converted image as the game will show it, using the same packed colors that ended up
 * in the map data, so the palette's limits are visible before a wall gets built.
 */
public final class Bi2mPreview implements AutoCloseable {
	private final Identifier textureId;
	private final DynamicTexture texture;
	private final GpuSampler nearestSampler;
	private final GpuSampler linearSampler;
	private final int width;
	private final int height;
	private final int tilesX;
	private final int tilesY;

	private Bi2mPreview(Identifier textureId, DynamicTexture texture, GpuSampler nearestSampler,
			GpuSampler linearSampler, int width, int height, int tilesX, int tilesY) {
		this.textureId = textureId;
		this.texture = texture;
		this.nearestSampler = nearestSampler;
		this.linearSampler = linearSampler;
		this.width = width;
		this.height = height;
		this.tilesX = tilesX;
		this.tilesY = tilesY;
	}

	public static Bi2mPreview of(Bi2mNetworking.PreviewData data) {
		NativeImage image = new NativeImage(data.width(), data.height(), false);
		for (int y = 0; y < data.height(); y++) {
			for (int x = 0; x < data.width(); x++) {
				int index = y * data.width() + x;
				int packed = index < data.colors().length ? data.colors()[index] & 0xFF : 0;
				image.setPixel(x, y, MapColor.getColorFromPackedId(packed));
			}
		}
		Identifier id = Bi2m.id("preview/" + System.nanoTime());
		DynamicTexture texture = new DynamicTexture(() -> "BI2M preview", image);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		// Nearest filtering, otherwise a 256px preview stretched into the panel turns to mush.
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		return new Bi2mPreview(id, texture, nearest, linear,
			data.width(), data.height(), data.tilesX(), data.tilesY());
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	public int tilesX() {
		return this.tilesX;
	}

	public int tilesY() {
		return this.tilesY;
	}

	/** The scale this preview will be drawn at inside the given box. */
	public float scaleFor(int maxWidth, int maxHeight) {
		float fit = Math.min((float) maxWidth / this.width, (float) maxHeight / this.height);
		if (fit < 1.0F) {
			return fit;
		}
		float integerScale = (float) Math.floor(fit);
		return Math.min(integerScale, 4.0F);
	}

	public void render(GuiGraphicsExtractor graphics, int x, int y, int maxWidth, int maxHeight) {
		float scale = this.scaleFor(maxWidth, maxHeight);
		int drawWidth = Math.max(1, Mth.floor(this.width * scale));
		int drawHeight = Math.max(1, Mth.floor(this.height * scale));
		int drawX = x + (maxWidth - drawWidth) / 2;
		int drawY = y + (maxHeight - drawHeight) / 2;

		// This overload takes corner coordinates and UV bounds, not a width/height pair.
		GpuSampler sampler = scale < 1.0F ? this.linearSampler : this.nearestSampler;
		graphics.blit(this.texture.getTextureView(), sampler,
			drawX, drawY, drawX + drawWidth, drawY + drawHeight,
			0.0F, 1.0F, 0.0F, 1.0F);

		// Tile seams, so it's obvious how many maps this will take.
		for (int tile = 1; tile < this.tilesX; tile++) {
			int lineX = drawX + Mth.floor(tile * 128 * scale);
			graphics.fill(lineX, drawY, lineX + 1, drawY + drawHeight, 0x60FF0000);
		}
		for (int tile = 1; tile < this.tilesY; tile++) {
			int lineY = drawY + Mth.floor(tile * 128 * scale);
			graphics.fill(drawX, lineY, drawX + drawWidth, lineY + 1, 0x60FF0000);
		}
	}

	@Override
	public void close() {
		Minecraft.getInstance().getTextureManager().release(this.textureId);
		this.texture.close();
	}
}
