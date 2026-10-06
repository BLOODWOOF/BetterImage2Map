package dev.betteri2m.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.betteri2m.Bi2m;
import dev.betteri2m.Job;
import dev.betteri2m.net.Bi2mNetworking;
import dev.betteri2m.render.Background;
import dev.betteri2m.render.Quantizer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class Bi2mClient implements ClientModInitializer {
	private static KeyMapping openKey;
	private static Bi2mScreen previewTarget;
	private static boolean warnedMissingMod;
	private static BufferedImage encodedImage;
	private static byte[] encodedBytes;

	@Override
	public void onInitializeClient() {
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.betterimage2map.open",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_M,
			KeyMapping.Category.register(Bi2m.id("general"))
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new Bi2mScreen());
				}
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> warnedMissingMod = false);

		ClientPlayNetworking.registerGlobalReceiver(Bi2mNetworking.StatusMessage.TYPE, (payload, context) ->
			context.client().execute(() -> {
				Bi2mScreen screen = previewTarget;
				if (screen != null) {
					screen.setStatus(payload.text(), payload.error());
				} else if (context.client().player != null) {
					// The screen can be closed by then - Done closes it - so the outcome goes to chat.
					context.client().player.sendSystemMessage(Component.literal(payload.text()));
				}
			}));

		ClientPlayNetworking.registerGlobalReceiver(Bi2mNetworking.PreviewData.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (previewTarget != null) {
					previewTarget.acceptPreview(payload);
				}
			}));
	}

	public static void setPreviewTarget(Bi2mScreen screen) {
		previewTarget = screen;
	}

	/** Where a request's picture comes from. */
	public enum Source { URL, SERVER_FILE, LOCAL_IMAGE }

	/**
	 * Everything the screen asks for, in the screen's own terms. One record for previews and real
	 * builds alike means a preview can't disagree with the wall that follows it.
	 */
	public record Request(Source source, String text, BufferedImage image, String name,
			double framesX, double framesY, int copies, Quantizer.Mode dither,
			int cropX, int cropY, int cropW, int cropH, Background background) {
		public boolean hasCrop() {
			return this.cropW > 0 && this.cropH > 0;
		}
	}

	/**
	 * Sends the request. A preview only asks what the wall would be; anything else asks for the
	 * maps to be made. False means nothing left the client, so the caller shouldn't wait for an
	 * answer that isn't coming.
	 */
	public static boolean send(Request request, boolean preview) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return false;
		}
		if (!hasServerMod(minecraft)) {
			return false;
		}
		return switch (request.source()) {
			case URL, SERVER_FILE -> {
				minecraft.player.connection.sendCommand(command(request, preview));
				yield true;
			}
			case LOCAL_IMAGE -> upload(request, preview);
		};
	}

	/**
	 * The screen's options in the order the command takes them. Sizes are always spelled out, zero
	 * meaning auto, so the wall is built from exactly what was on screen rather than from the
	 * server's defaults.
	 */
	private static String command(Request request, boolean preview) {
		StringBuilder command = new StringBuilder("bi2m ");
		if (preview) {
			command.append("preview ");
		}
		command.append("size ").append(number(request.framesX())).append(' ')
			.append(number(request.framesY())).append(' ');
		command.append("copies ").append(clamp(request.copies(), 1, Job.MAX_COPIES)).append(' ');
		command.append("dither ").append(request.dither().key()).append(' ');
		if (request.hasCrop()) {
			command.append("crop");
			for (int value : new int[] {request.cropX(), request.cropY(), request.cropW(), request.cropH()}) {
				command.append(' ').append(clamp(value, 0, Job.CROP_SCALE));
			}
			command.append(' ');
		}
		command.append("background ").append(request.background().key()).append(' ');
		command.append(request.source() == Source.URL ? "url " : "file ").append(request.text().trim());
		return command.toString();
	}

	/** Keeps a number inside what the command will accept, so a typo can't become a syntax error. */
	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String number(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
	}

	private static boolean hasServerMod(Minecraft minecraft) {
		if (ClientPlayNetworking.canSend(Bi2mNetworking.UploadStart.TYPE)) {
			return true;
		}
		if (!warnedMissingMod) {
			warnedMissingMod = true;
			minecraft.player.sendSystemMessage(Component.literal(
				"This server doesn't have BetterImage2Map installed"));
		}
		return false;
	}

	/**
	 * Uploads a picture the client already has, in chunks, since a screenshot is far bigger than a
	 * single packet.
	 */
	private static boolean upload(Request request, boolean preview) {
		Minecraft minecraft = Minecraft.getInstance();
		byte[] encoded;
		try {
			encoded = encoded(request.image());
		} catch (IOException e) {
			minecraft.player.sendSystemMessage(Component.literal("Could not encode that image"));
			return false;
		}
		List<byte[]> chunks = Bi2mNetworking.chunk(encoded);
		ClientPlayNetworking.send(new Bi2mNetworking.UploadStart(
			request.name(), chunks.size(),
			request.framesX(), request.framesY(),
			request.dither().key(), clamp(request.copies(), 1, Job.MAX_COPIES), preview, request.background().key(),
			request.cropX(), request.cropY(), request.cropW(), request.cropH()));
		for (int i = 0; i < chunks.size(); i++) {
			ClientPlayNetworking.send(new Bi2mNetworking.UploadChunk(i, chunks.get(i)));
		}
		return true;
	}

	/**
	 * Encodes a picture once. A large screenshot takes long enough to feel, and the same picture is
	 * sent again when a preview is turned into a real wall.
	 */
	private static byte[] encoded(BufferedImage image) throws IOException {
		synchronized (Bi2mClient.class) {
			if (image == encodedImage && encodedBytes != null) {
				return encodedBytes;
			}
			byte[] bytes = Bi2mNetworking.encodePng(image);
			encodedImage = image;
			encodedBytes = bytes;
			return bytes;
		}
	}

	public static boolean isPasteCombo(net.minecraft.client.input.KeyEvent event) {
		return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
			|| InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	/** Reads an image off the system clipboard, if there is one. */
	public static BufferedImage readClipboardImage() {
		try {
			java.awt.datatransfer.Transferable contents = java.awt.Toolkit.getDefaultToolkit()
				.getSystemClipboard().getContents(null);
			if (contents == null || !contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
				return null;
			}
			Object data = contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
			if (data instanceof java.awt.Image awtImage) {
				return toBuffered(awtImage);
			}
		} catch (Exception ignored) {
			// A headless or restricted clipboard just means no image, not an error worth showing.
		}
		return null;
	}

	private static BufferedImage toBuffered(java.awt.Image image) {
		int width = image.getWidth(null);
		int height = image.getHeight(null);
		if (width <= 0 || height <= 0) {
			return null;
		}
		BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D graphics = buffered.createGraphics();
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return buffered;
	}
}
