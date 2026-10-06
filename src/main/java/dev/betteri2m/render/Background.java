package dev.betteri2m.render;

import java.util.Locale;

/**
 * What sits behind the transparent parts of a picture. A map has no alpha, so a PNG's see-through
 * pixels either get composited onto white or black in linear light, or with NONE are left as the
 * map's blank color so the shape of the artwork stays empty in game.
 */
public enum Background {
	WHITE(1.0F, 1.0F, 1.0F),
	BLACK(0.0F, 0.0F, 0.0F),
	NONE(0.0F, 0.0F, 0.0F);

	private final float red;
	private final float green;
	private final float blue;

	Background(float red, float green, float blue) {
		this.red = red;
		this.green = green;
		this.blue = blue;
	}

	/** Linear-light color of the background, in the 0..1 range. */
	public float red() {
		return this.red;
	}

	public float green() {
		return this.green;
	}

	public float blue() {
		return this.blue;
	}

	/** True when see-through pixels are left blank instead of being painted over. */
	public boolean keepsTransparency() {
		return this == NONE;
	}

	/** The name a command or a config file would use. */
	public String key() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	/** Lenient, because it reads both config files and whatever a player typed. */
	public static Background parse(String name) {
		if (name == null) {
			return WHITE;
		}
		return switch (name.trim().toLowerCase(Locale.ROOT)) {
			case "black", "dark" -> BLACK;
			case "none", "transparent", "blank", "empty", "clear" -> NONE;
			default -> WHITE;
		};
	}
}
