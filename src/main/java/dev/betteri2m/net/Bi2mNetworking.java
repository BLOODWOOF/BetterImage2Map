package dev.betteri2m.net;

import dev.betteri2m.Bi2m;
import dev.betteri2m.Bi2mConfig;
import dev.betteri2m.Job;
import dev.betteri2m.store.ImageFetcher;
import dev.betteri2m.render.Background;
import dev.betteri2m.render.Quantizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * Client to server uploads and server to client previews. A 4K screenshot is well past what one
 * packet can carry, so uploads arrive in chunks and the running total is checked as they land,
 * which keeps the reassembly buffer inside the config's byte limit.
 */
public final class Bi2mNetworking {
	private static final int CHUNK_BYTES = 24 * 1024;
	private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

	private Bi2mNetworking() {
	}

	private static final class Upload {
		private final String name;
		private final int totalChunks;
		private final double tilesX;
		private final double tilesY;
		private final String dither;
		private final int copies;
		private final boolean preview;
		private final String background;
		private final int cropX;
		private final int cropY;
		private final int cropW;
		private final int cropH;
		private final ByteArrayOutputStream buffer;
		private int received;

		private Upload(String name, int totalChunks, double tilesX, double tilesY, String dither, int copies,
				boolean preview, String background, int cropX, int cropY, int cropW, int cropH) {
			this.name = name;
			this.totalChunks = totalChunks;
			this.tilesX = tilesX;
			this.tilesY = tilesY;
			this.dither = dither;
			this.copies = copies;
			this.preview = preview;
			this.background = background;
			this.cropX = cropX;
			this.cropY = cropY;
			this.cropW = cropW;
			this.cropH = cropH;
			this.buffer = new ByteArrayOutputStream();
		}
	}

	/**
	 * Auto axes arrive as zero, so there's no separate flag to keep in step; the total size isn't
	 * sent either, since the running total is checked as the chunks land.
	 */
	public record UploadStart(String name, int totalChunks, double tilesX, double tilesY,
		String dither, int copies, boolean preview, String background, int cropX, int cropY, int cropW, int cropH)
		implements CustomPacketPayload {
		public static final Type<UploadStart> TYPE = new Type<>(Bi2m.id("upload_start"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadStart> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, UploadStart::name,
			ByteBufCodecs.VAR_INT, UploadStart::totalChunks,
			ByteBufCodecs.DOUBLE, UploadStart::tilesX,
			ByteBufCodecs.DOUBLE, UploadStart::tilesY,
			ByteBufCodecs.STRING_UTF8, UploadStart::dither,
			ByteBufCodecs.VAR_INT, UploadStart::copies,
			ByteBufCodecs.BOOL, UploadStart::preview,
			ByteBufCodecs.STRING_UTF8, UploadStart::background,
			ByteBufCodecs.VAR_INT, UploadStart::cropX,
			ByteBufCodecs.VAR_INT, UploadStart::cropY,
			ByteBufCodecs.VAR_INT, UploadStart::cropW,
			ByteBufCodecs.VAR_INT, UploadStart::cropH,
			UploadStart::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UploadChunk(int index, byte[] data) implements CustomPacketPayload {
		public static final Type<UploadChunk> TYPE = new Type<>(Bi2m.id("upload_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunk> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, UploadChunk::index,
			ByteBufCodecs.BYTE_ARRAY, UploadChunk::data,
			UploadChunk::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record StatusMessage(String text, boolean error) implements CustomPacketPayload {
		public static final Type<StatusMessage> TYPE = new Type<>(Bi2m.id("status"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StatusMessage> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, StatusMessage::text,
			ByteBufCodecs.BOOL, StatusMessage::error,
			StatusMessage::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Sent by the server so the client can draw a preview of what the wall will look like. */
	public record PreviewData(int width, int height, int tilesX, int tilesY, int sourceWidth, int sourceHeight,
		byte[] colors, String label) implements CustomPacketPayload {
		public static final Type<PreviewData> TYPE = new Type<>(Bi2m.id("preview"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PreviewData> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, PreviewData::width,
			ByteBufCodecs.VAR_INT, PreviewData::height,
			ByteBufCodecs.VAR_INT, PreviewData::tilesX,
			ByteBufCodecs.VAR_INT, PreviewData::tilesY,
			ByteBufCodecs.VAR_INT, PreviewData::sourceWidth,
			ByteBufCodecs.VAR_INT, PreviewData::sourceHeight,
			ByteBufCodecs.BYTE_ARRAY, PreviewData::colors,
			ByteBufCodecs.STRING_UTF8, PreviewData::label,
			PreviewData::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(UploadStart.TYPE, UploadStart.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UploadChunk.TYPE, UploadChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatusMessage.TYPE, StatusMessage.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PreviewData.TYPE, PreviewData.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(UploadStart.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (payload.totalChunks() <= 0 || payload.totalChunks() > 4096) {
				status(player, "Bad upload", true);
				return;
			}
			UPLOADS.put(player.getUUID(), new Upload(
				payload.name(), payload.totalChunks(), payload.tilesX(), payload.tilesY(),
				payload.dither(), payload.copies(), payload.preview(), payload.background(),
				payload.cropX(), payload.cropY(), payload.cropW(), payload.cropH()));
		});

		ServerPlayNetworking.registerGlobalReceiver(UploadChunk.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Upload upload = UPLOADS.get(player.getUUID());
			if (upload == null) {
				return;
			}
			Bi2mConfig config = Bi2m.config();
			if (upload.buffer.size() + payload.data().length > config.maxImageBytes) {
				UPLOADS.remove(player.getUUID());
				status(player, "That image is larger than this server allows", true);
				return;
			}
			upload.buffer.write(payload.data(), 0, payload.data().length);
			upload.received++;
			if (upload.received >= upload.totalChunks) {
				UPLOADS.remove(player.getUUID());
				finish(player, upload);
			}
		});
	}

	private static void finish(ServerPlayer player, Upload upload) {
		Bi2mConfig config = Bi2m.config();
		if (!player.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(config.minPermLevel)))) {
			status(player, "You don't have permission to import images here", true);
			return;
		}
		byte[] bytes = upload.buffer.toByteArray();
		BufferedImage image;
		try {
			image = ImageFetcher.decode(bytes, config);
		} catch (IOException e) {
			status(player, e.getMessage(), true);
			return;
		}

		Job job = new Job(upload.tilesX, upload.tilesY, upload.copies,
			Quantizer.Mode.parse(upload.dither),
			upload.cropX, upload.cropY, upload.cropW, upload.cropH,
			Background.parse(upload.background), upload.preview);
		job.run(player, image, upload.name,
			text -> status(player, text.getString(), false),
			error -> status(player, error.getString(), true));
	}

	/**
	 * Renders the wall back to the client for the UI. The source dimensions travel with it, since
	 * the screen reports them and the tile grid doesn't carry them.
	 */
	public static void sendPreview(ServerPlayer player, dev.betteri2m.render.TileSet tiles, String label,
			int sourceWidth, int sourceHeight) {
		if (!ServerPlayNetworking.canSend(player, PreviewData.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(player, new PreviewData(
			tiles.width(), tiles.height(), tiles.tilesX(), tiles.tilesY(),
			sourceWidth, sourceHeight, tiles.colors(), label == null ? "" : label));
	}

	public static void status(ServerPlayer player, String text, boolean error) {
		if (ServerPlayNetworking.canSend(player, StatusMessage.TYPE)) {
			ServerPlayNetworking.send(player, new StatusMessage(text, error));
		}
	}

	/** Splits an encoded image into chunks for upload. */
	public static java.util.List<byte[]> chunk(byte[] data) {
		java.util.List<byte[]> chunks = new java.util.ArrayList<>();
		for (int offset = 0; offset < data.length; offset += CHUNK_BYTES) {
			int length = Math.min(CHUNK_BYTES, data.length - offset);
			byte[] slice = new byte[length];
			System.arraycopy(data, offset, slice, 0, length);
			chunks.add(slice);
		}
		return chunks;
	}

	public static byte[] encodePng(BufferedImage image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", out)) {
			throw new IOException("PNG encoder is unavailable");
		}
		return out.toByteArray();
	}
}
