package dev.betteri2m.store;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Cleans up a pasted URL before it is fetched. The important one is Discord:
 * {@code media.discordapp.net} links carry size and format parameters and point at a resized,
 * re-encoded thumbnail, while the same file is available untouched at
 * {@code cdn.discordapp.com} with those parameters stripped. Everything else is left alone
 * apart from obvious cruft.
 */
public final class ImageSources {
	private ImageSources() {
	}

	public static String normalize(String raw, boolean normalizeDiscord) {
		if (raw == null) {
			return "";
		}
		String url = raw.trim();
		if (url.isEmpty()) {
			return url;
		}
		if (url.startsWith("<") && url.endsWith(">") && url.length() > 2) {
			// Angle brackets are what Discord and Slack wrap links in when you copy them.
			url = url.substring(1, url.length() - 1).trim();
		}
		if (!normalizeDiscord) {
			return url;
		}
		return normalizeDiscordUrl(url);
	}

	private static String normalizeDiscordUrl(String url) {
		int schemeEnd = url.indexOf("://");
		if (schemeEnd < 0) {
			return url;
		}
		int hostStart = schemeEnd + 3;
		int pathStart = url.indexOf('/', hostStart);
		String host = pathStart < 0 ? url.substring(hostStart) : url.substring(hostStart, pathStart);
		if (!host.equalsIgnoreCase("media.discordapp.net")) {
			return url;
		}

		String remainder = pathStart < 0 ? "" : url.substring(pathStart);
		int queryStart = remainder.indexOf('?');
		String path = queryStart < 0 ? remainder : remainder.substring(0, queryStart);
		String query = queryStart < 0 ? "" : remainder.substring(queryStart + 1);

		StringBuilder kept = new StringBuilder();
		for (String pair : query.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			String key = pair;
			int equals = pair.indexOf('=');
			if (equals >= 0) {
				key = pair.substring(0, equals);
			}
			// format, width, height, quality all describe a resized derivative.
			if (key.equalsIgnoreCase("format") || key.equalsIgnoreCase("width")
				|| key.equalsIgnoreCase("height") || key.equalsIgnoreCase("quality")) {
				continue;
			}
			if (kept.length() > 0) {
				kept.append('&');
			}
			kept.append(pair);
		}

		StringBuilder out = new StringBuilder(url.length());
		out.append(url, 0, hostStart).append("cdn.discordapp.com").append(path);
		if (kept.length() > 0) {
			out.append('?').append(kept);
		}
		return out.toString();
	}

	/** Pulls the filename out of a URL for display and for the default title. */
	public static String fileName(String url) {
		if (url == null || url.isEmpty()) {
			return "image";
		}
		String trimmed = url;
		int query = trimmed.indexOf('?');
		if (query >= 0) {
			trimmed = trimmed.substring(0, query);
		}
		int slash = trimmed.lastIndexOf('/');
		String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
		try {
			name = URLDecoder.decode(name, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ignored) {
			// Keep the raw name if it isn't valid percent-encoding.
		}
		return name.isEmpty() ? "image" : name;
	}

	public static boolean isHttpUrl(String value) {
		try {
			URI uri = URI.create(value.trim());
			String scheme = uri.getScheme();
			return scheme != null
				&& (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
				&& uri.getHost() != null;
		} catch (RuntimeException e) {
			return false;
		}
	}
}
