package dev.betteri2m;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.betteri2m.net.Bi2mNetworking;
import dev.betteri2m.render.Background;
import dev.betteri2m.render.Bi2mData;
import dev.betteri2m.render.Conversion;
import dev.betteri2m.render.MapTiler;
import dev.betteri2m.render.Quantizer;
import dev.betteri2m.render.RenderSpec;
import dev.betteri2m.render.TileSet;
import dev.betteri2m.store.ImageFetcher;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemLore;

/** Holds one conversion request. */
public record Job(double tilesX, double tilesY, int copies, Quantizer.Mode dither,
		int cropX, int cropY, int cropW, int cropH, Background background, boolean preview) {
	public static final int MAX_COPIES = 64;
	/** Scale used for crop fractions. */
	public static final int CROP_SCALE = 10000;

	private static final SimpleCommandExceptionType BAD_DITHER =
		new SimpleCommandExceptionType(() -> "Pick sierra-lite, floyd, ordered, atkinson, knoll, family or none");

	public Job {
		copies = Math.max(1, Math.min(MAX_COPIES, copies));
	}

	/** Reads a conversion request from command arguments. */
	public static Job from(CommandContext<CommandSourceStack> ctx, boolean preview, Bi2mConfig config)
			throws CommandSyntaxException {
		return new Job(
			optionalDouble(ctx, "width", 0.0D),
			optionalDouble(ctx, "height", 0.0D),
			optionalInt(ctx, "copies", 1),
			dither(ctx, config),
			optionalInt(ctx, "cropX", 0),
			optionalInt(ctx, "cropY", 0),
			optionalInt(ctx, "cropW", 0),
			optionalInt(ctx, "cropH", 0),
			background(ctx, config),
			preview);
	}

	public boolean hasCrop() {
		return this.cropW > 0 && this.cropH > 0;
	}

	/** Combines a preview selection with the existing crop. */
	public static int[] cropWithin(int cropX, int cropY, int cropW, int cropH,
			double x, double y, double width, double height) {
		int frameX = cropW > 0 ? cropX : 0;
		int frameY = cropH > 0 ? cropY : 0;
		int frameW = cropW > 0 ? cropW : CROP_SCALE;
		int frameH = cropH > 0 ? cropH : CROP_SCALE;
		int left = Math.max(0, Math.min(CROP_SCALE - 1, frameX + (int) Math.round(frameW * fraction(x))));
		int top = Math.max(0, Math.min(CROP_SCALE - 1, frameY + (int) Math.round(frameH * fraction(y))));
		int wide = Math.max(1, (int) Math.round(frameW * fraction(width)));
		int tall = Math.max(1, (int) Math.round(frameH * fraction(height)));
		return new int[] {left, top, Math.min(wide, CROP_SCALE - left), Math.min(tall, CROP_SCALE - top)};
	}

	private static double fraction(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	/** Resolves output dimensions from the image and requested frame sizes. */
	public RenderSpec specFor(BufferedImage image, Bi2mConfig config) {
		RenderSpec spec = Conversion.resolve(image, this.tilesX, this.tilesY, config, this.dither)
			.withBackground(this.background);
		if (this.hasCrop()) {
			spec = spec.withNormalizedCrop(
				this.cropX / (double) CROP_SCALE, this.cropY / (double) CROP_SCALE,
				this.cropW / (double) CROP_SCALE, this.cropH / (double) CROP_SCALE,
				image.getWidth(), image.getHeight());
		}
		return spec;
	}

	/** Converts the image and returns the result to the player. */
	public void run(ServerPlayer player, BufferedImage image, String label,
			Consumer<Component> report, Consumer<Component> fail) {
		Bi2mConfig config = Bi2m.config();
		RenderSpec spec;
		try {
			spec = this.specFor(image, config);
		} catch (IllegalArgumentException error) {
			fail.accept(Component.literal(error.getMessage()));
			return;
		}
		MinecraftServer server = player.level().getServer();
		report.accept(Component.literal("Converting to " + spec.describeFrames() + "..."));
		Conversion.convert(image, spec, config, ImageFetcher.executorFor(server))
			.whenComplete((tiles, error) -> server.execute(() -> {
				if (error != null) {
					fail.accept(ImageFetcher.describe(error));
					return;
				}
				Bi2mNetworking.sendPreview(player, tiles, label, image.getWidth(), image.getHeight());
				if (this.preview) {
					report.accept(previewMessage(spec));
					return;
				}
				ItemStack stack = buildBundle(tiles, player.level(), label);
				giveCopies(player, stack, this.copies);
				report.accept(addedMessage(stack.getHoverName(), this.copies, spec));
			}));
	}

	/** Creates the preview result message. */
	public static Component previewMessage(RenderSpec spec) {
		String shape = spec.frameCount() == 1 ? "one frame" : spec.describeFrames();
		return Component.literal("That would take " + shape + ", nothing created yet");
	}

	/** Creates the success message. */
	public static Component addedMessage(Component name, int copies, RenderSpec spec) {
		Component amount = copies > 1 ? Component.literal(copies + " x ") : Component.empty();
		String place = spec.frameCount() == 1 ? " for one frame" : " for a " + spec.describeFrames() + " wall";
		return Component.literal("Added ").append(amount).append(name).append(Component.literal(place));
	}

	/** Builds a map or bundle from the tiles. */
	public static ItemStack buildBundle(TileSet tiles, ServerLevel level, String source) {
		List<ItemStackTemplate> items = MapTiler.build(tiles, level, source);
		if (items.size() == 1) {
			ItemStack single = items.get(0).create();
			single.set(DataComponents.ITEM_NAME, Component.literal("Map").withStyle(ChatFormatting.GOLD));
			return single;
		}
		BundleContents contents = new BundleContents(items);
		DataComponentPatch patch = DataComponentPatch.builder()
			.set(DataComponents.BUNDLE_CONTENTS, contents)
			.set(DataComponents.CUSTOM_DATA, MapTiler.customData(Bi2mData.ofBundle(tiles.tilesX(), tiles.tilesY(), source)))
			.set(DataComponents.ITEM_NAME, Component.literal("Maps").withStyle(ChatFormatting.GOLD))
			.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal(tiles.tilesX() + " x " + tiles.tilesY() + " frames  ("
					+ tiles.tileCount() + " maps)")
					.withStyle(ChatFormatting.GRAY),
				Component.literal("Right click a frame to place").withStyle(ChatFormatting.DARK_GRAY)
			)))
			.build();
		ItemStack bundle = new ItemStack(Items.BUNDLE, 1);
		bundle.applyComponents(patch);
		return bundle;
	}

	private static void giveCopies(ServerPlayer player, ItemStack stack, int copies) {
		for (int i = 0; i < copies; i++) {
			// A fresh stack each time; adding one instance twice would share it between slots.
			ItemStack copy = stack.copy();
			if (!player.getInventory().add(copy)) {
				player.drop(copy, false);
			}
		}
	}

	/** The player's background choice, or the server's when they didn't pick one. */
	private static Background background(CommandContext<CommandSourceStack> ctx, Bi2mConfig config) {
		try {
			return Background.parse(StringArgumentType.getString(ctx, "background"));
		} catch (IllegalArgumentException absent) {
			return Background.parse(config.background);
		}
	}

	private static double optionalDouble(CommandContext<CommandSourceStack> ctx, String name, double fallback) {
		try {
			return DoubleArgumentType.getDouble(ctx, name);
		} catch (IllegalArgumentException absent) {
			return fallback;
		}
	}

	private static int optionalInt(CommandContext<CommandSourceStack> ctx, String name, int fallback) {
		try {
			return IntegerArgumentType.getInteger(ctx, name);
		} catch (IllegalArgumentException absent) {
			return fallback;
		}
	}

	/** Reads the requested mode or the server default. */
	private static Quantizer.Mode dither(CommandContext<CommandSourceStack> ctx, Bi2mConfig config)
			throws CommandSyntaxException {
		String raw;
		try {
			raw = StringArgumentType.getString(ctx, "dither");
		} catch (IllegalArgumentException absent) {
			return Quantizer.Mode.parse(config.dither);
		}
		return switch (raw.toLowerCase(Locale.ROOT)) {
			case "sierra-lite", "sierralite", "sierra" -> Quantizer.Mode.SIERRA_LITE;
			case "floyd", "floyd-steinberg", "on", "true" -> Quantizer.Mode.FLOYD;
			case "ordered", "bayer", "pattern" -> Quantizer.Mode.ORDERED;
			case "atkinson" -> Quantizer.Mode.ATKINSON;
			case "knoll" -> Quantizer.Mode.KNOLL;
			case "family", "family-ordered", "staircase" -> Quantizer.Mode.FAMILY;
			case "none", "off", "false", "plain" -> Quantizer.Mode.NONE;
			default -> throw BAD_DITHER.create();
		};
	}
}
