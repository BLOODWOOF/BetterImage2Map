package dev.betteri2m;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Bi2mConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger(Bi2m.MOD_ID + "/config");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	/** Permission level needed for the commands and for importing through the UI. */
	public int minPermLevel = 0;
	public boolean allowUrlFetch = true;
	public boolean allowLocalFiles = true;
	/** Off by default so a server can't be used to probe its own network. */
	public boolean allowPrivateAddresses = false;
	/** Always refused, checked before any address lookup. */
	public String[] blockedHosts = {};
	public int maxImageBytes = 16 * 1024 * 1024;
	public int maxImagePixels = 8192 * 8192;
	public int fetchTimeoutSeconds = 20;
	/** How far a player can be from a frame and still place or pick up a bundle. */
	public int maxPlacementDistance = 6;
	public String dither = "sierra-lite";
	/** White, black, or none for what goes behind the transparent parts of a PNG. */
	public String background = "white";
	/** Rewrite media.discordapp.net links to the original upload so the source isn't re-encoded. */
	public boolean normalizeDiscordLinks = true;

	public static Bi2mConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(Bi2m.MOD_ID + ".json");
		Bi2mConfig config = new Bi2mConfig();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Bi2mConfig loaded = GSON.fromJson(reader, Bi2mConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.error("Could not read {}, using defaults", file, e);
			}
		}
		config.clamp();
		config.save(file);
		return config;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Could not write {}", file, e);
		}
	}

	private void clamp() {
		this.minPermLevel = Math.max(0, Math.min(4, this.minPermLevel));
		this.maxImageBytes = Math.max(1024, this.maxImageBytes);
		this.maxImagePixels = Math.max(128 * 128, this.maxImagePixels);
		this.fetchTimeoutSeconds = Math.max(1, Math.min(120, this.fetchTimeoutSeconds));
		this.maxPlacementDistance = Math.max(1, Math.min(32, this.maxPlacementDistance));
		if (this.blockedHosts == null) {
			this.blockedHosts = new String[0];
		}
	}

}
