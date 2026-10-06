package dev.betteri2m.store;

import dev.betteri2m.Bi2mConfig;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Fetches and decodes images.
 * <p>
 * A URL is attacker-controlled, so the resolved address is checked against loopback, link-local
 * and private ranges before the request goes out, which stops the command being used to probe
 * the host's own network. Image headers are also attacker-controlled, so the declared dimensions
 * are rejected before anything is decoded - a 40KB PNG can claim 50000x50000 and cost tens of
 * gigabytes of heap if you decode first and check after.
 * <p>
 * A link fetched a moment ago is handed back rather than downloaded again, so the screen's live
 * preview doesn't re-request the picture on every option change.
 */
public final class ImageFetcher {
	private static final int REDIRECTS = 3;
	/** How long a downloaded image can be handed back, and how many are kept at once. */
	private static final long REUSE_MILLIS = 10_000L;
	private static final int REUSE_ENTRIES = 8;
	private static final Map<String, Reused> REUSED = new ConcurrentHashMap<>();
	private static volatile boolean imageReadersScanned;

	private ImageFetcher() {
	}

	private record Reused(BufferedImage image, long at) {
	}

	public static CompletableFuture<BufferedImage> fetch(String url, Bi2mConfig config, Executor executor) {
		BufferedImage reused = reuse(url);
		if (reused != null) {
			return CompletableFuture.completedFuture(reused);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				BufferedImage image = fetchBlocking(url, config);
				remember(url, image);
				return image;
			} catch (IOException | RuntimeException e) {
				throw new CompletionException(e);
			}
		}, executor);
	}

	/** The picture for a link, if it was downloaded a moment ago and hasn't gone stale. */
	private static BufferedImage reuse(String url) {
		Reused reused = REUSED.get(url);
		if (reused == null) {
			return null;
		}
		if (System.currentTimeMillis() - reused.at() > REUSE_MILLIS) {
			REUSED.remove(url, reused);
			return null;
		}
		return reused.image();
	}

	private static void remember(String url, BufferedImage image) {
		if (REUSED.size() >= REUSE_ENTRIES) {
			// Bounded, since a server shouldn't grow a picture library. The oldest was about to
			// expire anyway.
			String oldestKey = null;
			long oldestAt = Long.MAX_VALUE;
			for (Map.Entry<String, Reused> entry : REUSED.entrySet()) {
				if (entry.getValue().at() < oldestAt) {
					oldestAt = entry.getValue().at();
					oldestKey = entry.getKey();
				}
			}
			if (oldestKey != null) {
				REUSED.remove(oldestKey);
			}
		}
		REUSED.put(url, new Reused(image, System.currentTimeMillis()));
	}

	private static BufferedImage fetchBlocking(String url, Bi2mConfig config) throws IOException {
		byte[] bytes = download(url, config);
		return decode(bytes, config);
	}

	private static byte[] download(String url, Bi2mConfig config) throws IOException {
		if (!config.allowUrlFetch) {
			throw new IOException("URL fetching is disabled in the config");
		}
		if (!ImageSources.isHttpUrl(url)) {
			throw new IOException("Not a usable http(s) URL");
		}

		String current = url;
		try (HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(config.fetchTimeoutSeconds))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build()) {
			for (int hop = 0; hop <= REDIRECTS; hop++) {
				URI uri = URI.create(current);
				checkHost(uri, config);
				HttpRequest request = HttpRequest.newBuilder()
					.GET()
					.uri(uri)
					.timeout(Duration.ofSeconds(config.fetchTimeoutSeconds))
					.header("User-Agent", "BetterImage2Map/" + dev.betteri2m.Bi2m.version())
					.header("Accept", "image/*")
					.build();
				HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
				int status = response.statusCode();
				if (status >= 300 && status < 400) {
					String location = response.headers().firstValue("location").orElse(null);
					response.body().close();
					if (location == null) {
						throw new IOException("Redirect without a location header");
					}
					current = URI.create(current).resolve(location).toString();
					continue;
				}
				if (status != 200) {
					response.body().close();
					throw new IOException("Server returned HTTP " + status);
				}
				try (InputStream body = response.body()) {
					return readCapped(body, config.maxImageBytes);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while downloading", e);
		}
		throw new IOException("Too many redirects");
	}

	private static byte[] readCapped(InputStream stream, int limit) throws IOException {
		byte[] buffer = new byte[8192];
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int total = 0;
		int read;
		while ((read = stream.read(buffer)) > 0) {
			total += read;
			if (total > limit) {
				throw new IOException("Image is larger than the " + (limit / 1024 / 1024) + "MB limit");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private static void checkHost(URI uri, Bi2mConfig config) throws IOException {
		String host = uri.getHost();
		if (host == null) {
			throw new IOException("URL has no host");
		}
		for (String blocked : config.blockedHosts) {
			if (blocked != null && !blocked.isBlank() && host.toLowerCase(Locale.ROOT).endsWith(blocked.toLowerCase(Locale.ROOT))) {
				throw new IOException("That host is blocked on this server");
			}
		}
		if (config.allowPrivateAddresses) {
			return;
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new IOException("Could not resolve " + host);
		}
		for (InetAddress address : addresses) {
			if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || isUniqueLocal(address)) {
				throw new IOException("That address is not reachable from this server");
			}
		}
	}

	/** IPv6 unique-local (fc00::/7), which InetAddress doesn't cover. */
	private static boolean isUniqueLocal(InetAddress address) {
		byte[] bytes = address.getAddress();
		return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
	}

	/** Reads the header first so the declared size is rejected before decoding. */
	public static BufferedImage decode(byte[] bytes, Bi2mConfig config) throws IOException {
		ensureImageReadersScanned();
		try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (stream == null) {
				throw new IOException("Not a readable image");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				throw new IOException("Unsupported image format");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(stream, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				long pixels = (long) width * height;
				if (pixels > config.maxImagePixels) {
					throw new IOException("Image is " + width + "x" + height + ", which is over the pixel limit");
				}
				BufferedImage image = reader.read(0);
				if (image == null) {
					throw new IOException("Image could not be decoded");
				}
				return image;
			} finally {
				reader.dispose();
			}
		}
	}

	/** Makes packaged ImageIO providers visible once, including loaders that don't scan SPIs on startup. */
	private static synchronized void ensureImageReadersScanned() {
		if (!imageReadersScanned) {
			ImageIO.scanForPlugins();
			imageReadersScanned = true;
		}
	}

	/** Turns a fetch failure into something worth showing a player. */
	public static Component describe(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		if (message == null || message.isBlank()) {
			message = cause.getClass().getSimpleName();
		}
		return Component.literal(message);
	}

	public static Executor executorFor(MinecraftServer server) {
		return server;
	}
}
