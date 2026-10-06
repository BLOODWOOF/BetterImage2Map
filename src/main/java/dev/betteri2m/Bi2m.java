package dev.betteri2m;

import dev.betteri2m.net.Bi2mNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Bi2m implements ModInitializer {
	public static final String MOD_ID = "betterimage2map";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static Bi2mConfig config = new Bi2mConfig();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
			.orElse("0");
	}

	public static Bi2mConfig config() {
		return config;
	}

	public static void reloadConfig() {
		config = Bi2mConfig.load();
	}

	@Override
	public void onInitialize() {
		reloadConfig();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			Bi2mCommands.register(dispatcher, config));
		Bi2mNetworking.registerPayloads();
		Bi2mInteractions.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (FrameRef.isFastItemFramesLoaded()) {
				LOGGER.info("FastItemFrames detected, block based frames are supported");
			}
		});
	}
}