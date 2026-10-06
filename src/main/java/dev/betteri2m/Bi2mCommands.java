package dev.betteri2m;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.betteri2m.store.ImageFetcher;
import dev.betteri2m.store.ImageSources;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;

/**
 * The command side of the mod, which is the whole thing when only the server has it installed.
 * <p>
 * Carries the same options as the screen - size, copies, dither, crop, background - all optional
 * and typed in that order, so {@code /bi2m url <link>} still works.
 */
public final class Bi2mCommands {
	private static final SimpleCommandExceptionType NO_FRAME =
		new SimpleCommandExceptionType(() -> "Look at an item frame to place or clear");
	private static final SimpleCommandExceptionType NO_BUNDLE =
		new SimpleCommandExceptionType(() -> "Hold a BetterImage2Map bundle first");
	private static final SimpleCommandExceptionType USAGE = new SimpleCommandExceptionType(() -> usageText());

	/** The lines /bi2m prints, kept in step with the options below. */
	private static String usageText() {
		return String.join("\n",
			"Turn an image into map items.",
			"/bi2m url <link> - convert an image from a link",
			"/bi2m file <path> - convert a file from the server's folder",
			"Options, in any order before the source:",
			"  size <wide> <tall> - frames; 0 or left out fits the image",
			"  copies <count> - bundles to give, 1 to 64",
			"  dither <mode> - sierra-lite, floyd, ordered, atkinson, knoll, family, none",
			"  crop <x> <y> <w> <h> - region of the image, in ten-thousandths",
			"  background <white|black|none> - behind transparent pixels",
			"Also: /bi2m preview ... shows the result without making maps,",
			"/bi2m place and /bi2m clear work on the wall you are looking at,",
			"/bi2m reload rereads the config.");
	}

	/** One optional prefix; whatever follows parses on from here. */
	@FunctionalInterface
	private interface Option {
		void attach(ArgumentBuilder<CommandSourceStack, ?> node, int index, boolean preview, Bi2mConfig config);
	}

	/** Typed in this order; a list is easier to follow than a tree. */
	private static final List<Option> OPTIONS = List.of(
		(node, index, preview, config) -> {
			ArgumentBuilder<CommandSourceStack, ?> height = Commands.argument("height",
				DoubleArgumentType.doubleArg(0.0D, Double.MAX_VALUE));
			options(height, index + 1, preview, config);
			node.then(Commands.literal("size").then(Commands.argument("width",
				DoubleArgumentType.doubleArg(0.0D, Double.MAX_VALUE)).then(height)));
		},
		(node, index, preview, config) -> {
			ArgumentBuilder<CommandSourceStack, ?> count = Commands.argument("copies",
				IntegerArgumentType.integer(1, Job.MAX_COPIES));
			options(count, index + 1, preview, config);
			node.then(Commands.literal("copies").then(count));
		},
		(node, index, preview, config) -> {
			ArgumentBuilder<CommandSourceStack, ?> mode = Commands.argument("dither", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
					new String[] {"sierra-lite", "floyd", "ordered", "atkinson", "knoll", "family", "none"}, builder));
			options(mode, index + 1, preview, config);
			node.then(Commands.literal("dither").then(mode));
		},
		(node, index, preview, config) -> {
			ArgumentBuilder<CommandSourceStack, ?> bottom = Commands.argument("cropH", cropArgument());
			options(bottom, index + 1, preview, config);
			ArgumentBuilder<CommandSourceStack, ?> height = Commands.argument("cropW", cropArgument()).then(bottom);
			ArgumentBuilder<CommandSourceStack, ?> top = Commands.argument("cropY", cropArgument()).then(height);
			node.then(Commands.literal("crop").then(Commands.argument("cropX", cropArgument()).then(top)));
		},
		// background <white|black|none>: what goes behind transparent pixels.
		(node, index, preview, config) -> {
			ArgumentBuilder<CommandSourceStack, ?> behind = Commands.argument("background", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
					new String[] {"white", "black", "none"}, builder));
			options(behind, index + 1, preview, config);
			node.then(Commands.literal("background").then(behind));
		}
	);

	private Bi2mCommands() {
	}

	private static IntegerArgumentType cropArgument() {
		return IntegerArgumentType.integer(0, Job.CROP_SCALE);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Bi2mConfig config) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bi2m")
			.requires(source -> source.permissions().hasPermission(
				new Permission.HasCommandLevel(PermissionLevel.byId(config.minPermLevel))))
			.executes(ctx -> {
				throw USAGE.create();
			});
		// A named help spelling alongside the bare command; Brigadier matches the longest literal.
		root.then(Commands.literal("help").executes(ctx -> {
			ctx.getSource().sendSuccess(() -> Component.literal(usageText()), false);
			return 1;
		}));
		// The plain and preview trees carry the same options; only the end result differs.
		options(root, 0, false, config);

		LiteralArgumentBuilder<CommandSourceStack> preview = Commands.literal("preview");
		options(preview, 0, true, config);
		root.then(preview);

		root.then(Commands.literal("place").executes(Bi2mCommands::placeLookedAt));
		root.then(Commands.literal("clear").executes(Bi2mCommands::clearLookedAt));
		root.then(Commands.literal("reload")
			.requires(source -> source.permissions().hasPermission(
				new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)))
			.executes(ctx -> {
				Bi2m.reloadConfig();
				ctx.getSource().sendSuccess(() -> Component.literal("BetterImage2Map config reloaded"), false);
				return 1;
			}));

		dispatcher.register(root);
	}

	/**
	 * Attaches the sources and every option from {@code index} onward. Called once per option in
	 * the chain, which is what lets a request leave any of them out.
	 */
	private static void options(ArgumentBuilder<CommandSourceStack, ?> node, int index, boolean preview, Bi2mConfig config) {
		node.then(Commands.literal("url")
			.then(Commands.argument("url", StringArgumentType.greedyString())
				.executes(ctx -> fromUrl(ctx, preview))));
		node.then(Commands.literal("file")
			.requires(source -> config.allowLocalFiles)
			.then(Commands.argument("path", StringArgumentType.greedyString())
				.executes(ctx -> fromFile(ctx, preview))));
		for (int i = index; i < OPTIONS.size(); i++) {
			OPTIONS.get(i).attach(node, i, preview, config);
		}
	}

	private static Consumer<Component> reporter(CommandSourceStack source) {
		return text -> source.sendSuccess(() -> text.copy().withStyle(ChatFormatting.GRAY), false);
	}

	private static Consumer<Component> failure(CommandSourceStack source) {
		return error -> source.sendSuccess(() -> error.copy().withStyle(ChatFormatting.RED), false);
	}

	private static int fromUrl(CommandContext<CommandSourceStack> ctx, boolean preview) throws CommandSyntaxException {
		Bi2mConfig config = Bi2m.config();
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Job job = Job.from(ctx, preview, config);
		String raw = StringArgumentType.getString(ctx, "url");
		String url = ImageSources.normalize(raw, config.normalizeDiscordLinks);
		if (!url.equals(raw)) {
			ctx.getSource().sendSuccess(() -> Component.literal("Using the original upload rather than the resized link")
				.withStyle(ChatFormatting.GRAY), false);
		}
		CommandSourceStack source = ctx.getSource();
		source.sendSuccess(() -> Component.literal("Fetching image..."), false);
		ImageFetcher.fetch(url, config, ImageFetcher.executorFor(source.getServer()))
			.whenComplete((image, error) -> source.getServer().execute(() -> {
				if (error != null) {
					failure(source).accept(ImageFetcher.describe(error));
					return;
				}
				job.run(player, image, url, reporter(source), failure(source));
			}));
		return 1;
	}

	/** Reads a file from the server's game directory. */
	private static int fromFile(CommandContext<CommandSourceStack> ctx, boolean preview) throws CommandSyntaxException {
		Bi2mConfig config = Bi2m.config();
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Job job = Job.from(ctx, preview, config);
		String raw = StringArgumentType.getString(ctx, "path");
		Path path = FabricLoader.getInstance().getGameDir().resolve(raw).normalize();
		if (!Files.isRegularFile(path)) {
			throw new SimpleCommandExceptionType(() -> "No file at " + raw
				+ " (paths are relative to the server's folder)").create();
		}
		CommandSourceStack source = ctx.getSource();
		source.sendSuccess(() -> Component.literal("Reading " + path.getFileName() + "..."), false);
		ImageFetcher.executorFor(source.getServer()).execute(() -> {
			BufferedImage image;
			try {
				image = ImageFetcher.decode(Files.readAllBytes(path), config);
			} catch (Exception e) {
				source.getServer().execute(() -> failure(source).accept(ImageFetcher.describe(e)));
				return;
			}
			source.getServer().execute(() ->
				job.run(player, image, path.getFileName().toString(), reporter(source), failure(source)));
		});
		return 1;
	}

	private static int placeLookedAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		FrameRef frame = lookedAtFrame(player);
		if (frame == null) {
			throw NO_FRAME.create();
		}
		ItemStack held = player.getMainHandItem();
		if (!Placement.isQuickPlaceBundle(held)) {
			throw NO_BUNDLE.create();
		}
		Placement.Result result = Placement.place(player, held, player.level(), frame);
		ctx.getSource().sendSuccess(() -> Placement.describe(result), false);
		return result.placed();
	}

	private static int clearLookedAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		FrameRef frame = lookedAtFrame(player);
		if (frame == null) {
			throw NO_FRAME.create();
		}
		int cleared = Placement.clearWall(player.level(), frame);
		ctx.getSource().sendSuccess(() -> Component.literal("Cleared " + cleared + " maps"), false);
		return cleared;
	}

	/** The frame the player is looking at, within reach. */
	public static FrameRef lookedAtFrame(ServerPlayer player) {
		var hit = player.pick(Bi2m.config().maxPlacementDistance, 1.0F, false);
		var level = player.level();
		if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit
			&& entityHit.getEntity() instanceof net.minecraft.world.entity.decoration.ItemFrame frame) {
			return FrameRef.ofEntity(frame);
		}
		if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit && FrameRef.isFastFrame(level, blockHit.getBlockPos())) {
			return FrameRef.find(level, blockHit.getBlockPos(), facingOfBlock(level, blockHit.getBlockPos()));
		}
		return null;
	}

	private static net.minecraft.core.Direction facingOfBlock(net.minecraft.server.level.ServerLevel level,
			net.minecraft.core.BlockPos pos) {
		var state = level.getBlockState(pos);
		for (var property : state.getProperties()) {
			if (property.getName().equals("facing")
				&& property instanceof net.minecraft.world.level.block.state.properties.EnumProperty<?> enumProperty) {
				Object value = state.getValue(enumProperty);
				if (value instanceof net.minecraft.core.Direction direction) {
					return direction;
				}
			}
		}
		return net.minecraft.core.Direction.NORTH;
	}
}
