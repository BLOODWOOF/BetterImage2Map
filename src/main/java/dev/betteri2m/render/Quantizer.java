package dev.betteri2m.render;

/**
 * Turns a linear-light image into map bytes.
 * <p>
 * Modes are error diffusion (Floyd-Steinberg, Sierra Lite, Atkinson), ordered Bayer, Knoll's
 * candidate mixing, and Family, which mixes only brightness levels of one map color family so a
 * dithered patch never jumps between unrelated hues. Sierra Lite is the config default.
 * <p>
 * Pixels arrive premultiplied with their coverage, which is resolved here rather than earlier: a
 * picture with soft transparency gets its background mixed in at full resolution, where
 * compositing before the resize would drag the background into every partially covered edge.
 */
public final class Quantizer {
	/** Coverage below this counts as see-through; half is the usual line. */
	private static final float VISIBLE = 0.5F;
	private static final float EDGE_STRENGTH = 0.08F;

	public enum Mode {
		NONE,
		ORDERED,
		FLOYD,
		SIERRA_LITE,
		ATKINSON,
		KNOLL,
		FAMILY;

		public static Mode parse(String name) {
			if (name == null) {
				return SIERRA_LITE;
			}
			return switch (name.toLowerCase(java.util.Locale.ROOT)) {
				case "none", "off" -> NONE;
				case "ordered", "bayer", "pattern" -> ORDERED;
				case "sierra-lite", "sierralite", "sierra" -> SIERRA_LITE;
				case "floyd", "floyd-steinberg", "on", "true" -> FLOYD;
				case "atkinson" -> ATKINSON;
				case "knoll" -> KNOLL;
				case "family", "family-ordered", "staircase" -> FAMILY;
				default -> SIERRA_LITE;
			};
		}

		public String key() {
			return switch (this) {
				case NONE -> "none";
				case ORDERED -> "ordered";
				case FLOYD -> "floyd";
				case SIERRA_LITE -> "sierra-lite";
				case ATKINSON -> "atkinson";
				case KNOLL -> "knoll";
				case FAMILY -> "family";
			};
		}
	}

	private static final int[] BAYER_8 = buildBayer();

	private Quantizer() {
	}

	private static int[] buildBayer() {
		int[] matrix = {0};
		int size = 1;
		while (size < 8) {
			int nextSize = size * 2;
			int[] next = new int[nextSize * nextSize];
			for (int y = 0; y < nextSize; y++) {
				for (int x = 0; x < nextSize; x++) {
					int base = matrix[(y % size) * size + x % size] * 4;
					next[y * nextSize + x] = base + (y < size ? (x < size ? 0 : 3) : (x < size ? 2 : 1));
				}
			}
			matrix = next;
			size = nextSize;
		}
		return matrix;
	}

	/**
	 * Quantizes {@code linear} (row-major linear RGB) into packed map color bytes.
	 *
	 * @param threads how many workers may be used; only honored by tile-parallel modes
	 */
	public static byte[] quantize(float[] premultiplied, int width, int height, Mode mode, int threads) {
		return quantize(premultiplied, width, height, mode, threads, Background.WHITE);
	}

	/**
	 * @param background what to put behind see-through pixels, or {@link Background#NONE} to leave
	 *                   them as the map's blank color
	 */
	public static byte[] quantize(float[] premultiplied, int width, int height, Mode mode, int threads,
			Background background) {
		int pixels = width * height;
		boolean[] blank = background.keepsTransparency() ? blankMask(premultiplied, pixels) : null;
		float[] linear = flatten(premultiplied, pixels, background);
		return switch (mode) {
			case NONE -> quantizePlain(linear, width, height, blank);
			case ORDERED -> quantizeOrdered(linear, width, height, threads, blank);
			case FLOYD -> quantizeFloyd(linear, width, height, blank);
			case SIERRA_LITE -> quantizeSierraLite(linear, width, height, blank);
			case ATKINSON -> quantizeAtkinson(linear, width, height, blank);
			case KNOLL -> quantizeKnoll(linear, width, height, threads, blank);
			case FAMILY -> quantizeFamily(linear, width, height, threads, blank);
		};
	}

	/**
	 * Resolves coverage against the background, giving opaque linear colors to match against the
	 * palette. A flat background needs no division; keeping transparency means undoing the
	 * premultiplication the resampler stored.
	 */
	private static float[] flatten(float[] premultiplied, int pixels, Background background) {
		float[] out = new float[pixels * 3];
		for (int i = 0; i < pixels; i++) {
			int source = i * Resampler.STRIDE;
			int target = i * 3;
			float coverage = Resampler.coverage(premultiplied[source + 3]);
			if (background.keepsTransparency()) {
				float scale = coverage > 0.0F ? 1.0F / coverage : 0.0F;
				out[target] = premultiplied[source] * scale;
				out[target + 1] = premultiplied[source + 1] * scale;
				out[target + 2] = premultiplied[source + 2] * scale;
			} else {
				float behind = 1.0F - coverage;
				out[target] = premultiplied[source] + background.red() * behind;
				out[target + 1] = premultiplied[source + 1] + background.green() * behind;
				out[target + 2] = premultiplied[source + 2] + background.blue() * behind;
			}
		}
		return out;
	}

	private static boolean[] blankMask(float[] premultiplied, int pixels) {
		boolean[] blank = new boolean[pixels];
		for (int i = 0; i < pixels; i++) {
			blank[i] = Resampler.coverage(premultiplied[i * Resampler.STRIDE + 3]) < VISIBLE;
		}
		return blank;
	}

	private static byte[] quantizePlain(float[] linear, int width, int height, boolean[] blank) {
		byte[] out = new byte[width * height];
		for (int i = 0; i < out.length; i++) {
			if (blank != null && blank[i]) {
				continue;
			}
			int index = i * 3;
			float[] lab = ColorSpace.linearToOklab(linear[index], linear[index + 1], linear[index + 2]);
			out[i] = MapPalette.packed(MapPalette.nearest(lab[0], lab[1], lab[2]));
		}
		return out;
	}

	private static byte[] quantizeOrdered(float[] linear, int width, int height, int threads, boolean[] blank) {
		byte[] out = new byte[width * height];
		int bands = Math.max(1, Math.min(height, threads));
		int bandHeight = (height + bands - 1) / bands;
		Thread[] workers = new Thread[bands];
		for (int band = 0; band < bands; band++) {
			int startY = band * bandHeight;
			int endY = Math.min(height, startY + bandHeight);
			if (startY >= endY) {
				continue;
			}
			workers[band] = new Thread(() -> {
				for (int y = startY; y < endY; y++) {
					for (int x = 0; x < width; x++) {
						int i = y * width + x;
						if (blank != null && blank[i]) {
							continue;
						}
						int index = i * 3;
						float threshold = (BAYER_8[(y & 7) * 8 + (x & 7)] / 64.0F - 0.5F) * 0.055F;
						float[] lab = ColorSpace.linearToOklab(
							linear[index] + threshold,
							linear[index + 1] + threshold,
							linear[index + 2] + threshold
						);
						out[i] = MapPalette.packed(MapPalette.nearest(lab[0], lab[1], lab[2]));
					}
				}
			}, "bi2m-quantize-" + band);
			workers[band].start();
		}
		join(workers);
		return out;
	}

	/** Error-diffuses in OKLab with serpentine, edge-limited weights. */
	private static byte[] quantizeFloyd(float[] linear, int width, int height, boolean[] blank) {
		byte[] out = new byte[width * height];
		float[] field = new float[width * height * 3];
		for (int i = 0; i < out.length; i++) {
			int index = i * 3;
			float[] lab = ColorSpace.linearToOklab(linear[index], linear[index + 1], linear[index + 2]);
			field[index] = lab[0];
			field[index + 1] = lab[1];
			field[index + 2] = lab[2];
		}

		for (int y = 0; y < height; y++) {
			int direction = (y & 1) == 0 ? 1 : -1;
			int x = direction > 0 ? 0 : width - 1;
			for (int step = 0; step < width; step++, x += direction) {
				int i = y * width + x;
				if (blank != null && blank[i]) {
					continue;
				}
				int index = i * 3;
				float l = field[index];
				float a = field[index + 1];
				float b = field[index + 2];
				int pick = MapPalette.nearest(l, a, b);
				out[i] = MapPalette.packed(pick);

				float[] target = MapPalette.oklabOf(pick);
				spreadFloydError(field, linear, blank, width, height, x, y, direction,
					l - target[0], a - target[1], b - target[2]);
			}
		}
		return out;
	}

	/** Sierra Lite: half to the next pixel, then a quarter to each of the next-row neighbors. */
	private static byte[] quantizeSierraLite(float[] linear, int width, int height, boolean[] blank) {
		byte[] out = new byte[width * height];
		float[] field = new float[width * height * 3];
		for (int i = 0; i < out.length; i++) {
			int index = i * 3;
			float[] lab = ColorSpace.linearToOklab(linear[index], linear[index + 1], linear[index + 2]);
			field[index] = lab[0];
			field[index + 1] = lab[1];
			field[index + 2] = lab[2];
		}
		for (int y = 0; y < height; y++) {
			int direction = (y & 1) == 0 ? 1 : -1;
			int x = direction > 0 ? 0 : width - 1;
			for (int step = 0; step < width; step++, x += direction) {
				int pixel = y * width + x;
				if (blank != null && blank[pixel]) continue;
				int at = pixel * 3;
				float l = field[at], a = field[at + 1], b = field[at + 2];
				int pick = MapPalette.nearest(l, a, b);
				out[pixel] = MapPalette.packed(pick);
				float[] target = MapPalette.oklabOf(pick);
				spreadSierraLiteError(field, linear, blank, width, height, x, y, direction,
					l - target[0], a - target[1], b - target[2]);
			}
		}
		return out;
	}

	/**
	 * Atkinson diffuses only six eighths of the error, which keeps flat regions quiet at the price of
	 * slightly compressed highlights. The kernel is plain diffusion; edge limiting is not applied.
	 */
	private static byte[] quantizeAtkinson(float[] linear, int width, int height, boolean[] blank) {
		byte[] out = new byte[width * height];
		float[] field = oklabField(linear, width * height);
		for (int y = 0; y < height; y++) {
			int direction = (y & 1) == 0 ? 1 : -1;
			int x = direction > 0 ? 0 : width - 1;
			for (int step = 0; step < width; step++, x += direction) {
				int pixel = y * width + x;
				if (blank != null && blank[pixel]) {
					continue;
				}
				int at = pixel * 3;
				float l = field[at];
				float a = field[at + 1];
				float b = field[at + 2];
				int pick = MapPalette.nearest(l, a, b);
				out[pixel] = MapPalette.packed(pick);
				float[] target = MapPalette.oklabOf(pick);
				float dl = l - target[0];
				float da = a - target[1];
				float db = b - target[2];
				for (int[] tap : ATKINSON_TAPS) {
					int tx = x + direction * tap[0];
					int ty = y + tap[1];
					if (tx < 0 || tx >= width || ty >= height) {
						continue;
					}
					if (blank != null && blank[ty * width + tx]) {
						continue;
					}
					addError(field, width, height, tx, ty, dl, da, db, tap[2] / 8.0F);
				}
			}
		}
		return out;
	}

	/**
	 * Knoll's error-compensated candidate mixing: each round picks the palette entry closest to a
	 * drifting goal, then the goal is pushed further from the accumulated error. Visit counts become
	 * weights, and one of them is chosen with the Bayer matrix.
	 */
	private static byte[] quantizeKnoll(float[] linear, int width, int height, int threads, boolean[] blank) {
		byte[] out = new byte[width * height];
		int bands = Math.max(1, Math.min(height, threads));
		int bandHeight = (height + bands - 1) / bands;
		Thread[] workers = new Thread[bands];
		for (int band = 0; band < bands; band++) {
			int startY = band * bandHeight;
			int endY = Math.min(height, startY + bandHeight);
			if (startY >= endY) {
				continue;
			}
			workers[band] = new Thread(() -> {
				float[] table = MapPalette.oklabTable();
				int[] order = lumaOrder();
				int[] weights = new int[MapPalette.count()];
				float[] goal = new float[3];
				for (int y = startY; y < endY; y++) {
					for (int x = 0; x < width; x++) {
						int pixel = y * width + x;
						if (blank != null && blank[pixel]) {
							continue;
						}
						int at = pixel * 3;
						float[] lab = ColorSpace.linearToOklab(linear[at], linear[at + 1], linear[at + 2]);
						java.util.Arrays.fill(weights, 0);
						goal[0] = lab[0];
						goal[1] = lab[1];
						goal[2] = lab[2];
						for (int round = 0; round < KNOLL_ROUNDS; round++) {
							int pick = MapPalette.nearest(goal[0], goal[1], goal[2]);
							weights[pick]++;
							goal[0] += lab[0] - table[pick * 3];
							goal[1] += lab[1] - table[pick * 3 + 1];
							goal[2] += lab[2] - table[pick * 3 + 2];
						}
						float threshold = (BAYER_8[(y & 7) * 8 + (x & 7)] + 0.5F) / 64.0F;
						float cumulative = 0.0F;
						int chosen = order[0];
						for (int candidate : order) {
							cumulative += weights[candidate] / (float) KNOLL_ROUNDS;
							chosen = candidate;
							if (cumulative > threshold) {
								break;
							}
						}
						out[pixel] = MapPalette.packed(chosen);
					}
				}
			}, "bi2m-quantize-knoll-" + band);
			workers[band].start();
		}
		join(workers);
		return out;
	}

/**
 * Family dithering: every pixel becomes a mix of two brightness levels of one base map color,
 * so a dithered patch never alternates between unrelated hues.
 */
	private static byte[] quantizeFamily(float[] linear, int width, int height, int threads, boolean[] blank) {
		byte[] out = new byte[width * height];
		int bands = Math.max(1, Math.min(height, threads));
		int bandHeight = (height + bands - 1) / bands;
		Thread[] workers = new Thread[bands];
		for (int band = 0; band < bands; band++) {
			int startY = band * bandHeight;
			int endY = Math.min(height, startY + bandHeight);
			if (startY >= endY) {
				continue;
			}
			workers[band] = new Thread(() -> {
				for (int y = startY; y < endY; y++) {
					for (int x = 0; x < width; x++) {
						int pixel = y * width + x;
						if (blank != null && blank[pixel]) {
							continue;
						}
						int at = pixel * 3;
						float[] lab = ColorSpace.linearToOklab(linear[at], linear[at + 1], linear[at + 2]);
						Plan plan = familyPlan(lab[0], lab[1], lab[2]);
						float threshold = (BAYER_8[(y & 7) * 8 + (x & 7)] + 0.5F) / 64.0F;
						out[pixel] = MapPalette.packed(threshold < plan.ratio ? plan.second : plan.first);
					}
				}
			}, "bi2m-quantize-family-" + band);
			workers[band].start();
		}
		join(workers);
		return out;
	}

	/** Two brightness levels of one family plus the mixing ratio between them. */
	private record Plan(int first, int second, float ratio) {
	}

	/** Best in-family pair for a target color, scored in OKLab. */
	private static Plan familyPlan(float l, float a, float b) {
		float[] table = MapPalette.oklabTable();
		Plan best = null;
		float bestError = Float.MAX_VALUE;
		for (int family = 0; family < 64; family++) {
			int[] members = MapPalette.familyMembers(family);
			for (int i = 0; i < members.length; i++) {
				int first = members[i];
				float f0 = table[first * 3] - l;
				float f1 = table[first * 3 + 1] - a;
				float f2 = table[first * 3 + 2] - b;
				float single = f0 * f0 + f1 * f1 + f2 * f2;
				if (single < bestError) {
					bestError = single;
					best = new Plan(first, first, 0.0F);
				}
				for (int j = i + 1; j < members.length; j++) {
					int second = members[j];
					float rx = table[second * 3] - table[first * 3];
					float ry = table[second * 3 + 1] - table[first * 3 + 1];
					float rz = table[second * 3 + 2] - table[first * 3 + 2];
					float length = rx * rx + ry * ry + rz * rz;
					if (length < 1.0E-12F) {
						continue;
					}
					float t = -(f0 * rx + f1 * ry + f2 * rz) / length;
					t = Math.max(0.0F, Math.min(1.0F, t));
					float mx = f0 + t * rx;
					float my = f1 + t * ry;
					float mz = f2 + t * rz;
					// Prefer pairs whose members are close, so the pattern stays visually calm.
					float error = mx * mx + my * my + mz * mz + 0.10F * length * t * (1.0F - t);
					if (error < bestError) {
						bestError = error;
						best = new Plan(first, second, t);
					}
				}
			}
		}
		return best;
	}

	/** OKLab field for diffusion modes. */
	private static float[] oklabField(float[] linear, int pixels) {
		float[] field = new float[pixels * 3];
		for (int i = 0; i < pixels; i++) {
			float[] lab = ColorSpace.linearToOklab(linear[i * 3], linear[i * 3 + 1], linear[i * 3 + 2]);
			field[i * 3] = lab[0];
			field[i * 3 + 1] = lab[1];
			field[i * 3 + 2] = lab[2];
		}
		return field;
	}

	private static volatile int[] lumaOrder;

	/** Palette indices sorted by lightness, used to walk candidate weights consistently. */
	private static int[] lumaOrder() {
		int[] order = lumaOrder;
		if (order == null) {
			int count = MapPalette.count();
			float[] table = MapPalette.oklabTable();
			Integer[] indices = new Integer[count];
			for (int i = 0; i < count; i++) {
				indices[i] = i;
			}
			java.util.Arrays.sort(indices, (first, second) -> Float.compare(table[first * 3], table[second * 3]));
			order = new int[count];
			for (int i = 0; i < count; i++) {
				order[i] = indices[i];
			}
			lumaOrder = order;
		}
		return order;
	}

	private static final int KNOLL_ROUNDS = 16;
	private static final int[][] ATKINSON_TAPS = {{1, 0, 1}, {2, 0, 1}, {-1, 1, 1}, {0, 1, 1}, {1, 1, 1}, {0, 2, 1}};

	private static void spreadSierraLiteError(float[] field, float[] guide, boolean[] blank,
			int width, int height, int x, int y, int direction, float dl, float da, float db) {
		int forward = x + direction;
		int backward = x - direction;
		float forwardWeight = edgeWeight(guide, blank, width, height, x, y, forward, y, 0.5F);
		float backwardWeight = edgeWeight(guide, blank, width, height, x, y, backward, y + 1, 0.25F);
		float belowWeight = edgeWeight(guide, blank, width, height, x, y, x, y + 1, 0.25F);
		float total = forwardWeight + backwardWeight + belowWeight;
		if (total <= 0.0F) return;
		addError(field, width, height, forward, y, dl, da, db, forwardWeight / total);
		addError(field, width, height, backward, y + 1, dl, da, db, backwardWeight / total);
		addError(field, width, height, x, y + 1, dl, da, db, belowWeight / total);
	}

	private static void spreadFloydError(float[] field, float[] guide, boolean[] blank, int width, int height,
			int x, int y, int direction, float dl, float da, float db) {
		int x1 = x + direction;
		int x2 = x - direction;
		float w1 = edgeWeight(guide, blank, width, height, x, y, x1, y, 7.0F / 16.0F);
		float w2 = edgeWeight(guide, blank, width, height, x, y, x2, y + 1, 3.0F / 16.0F);
		float w3 = edgeWeight(guide, blank, width, height, x, y, x, y + 1, 5.0F / 16.0F);
		float w4 = edgeWeight(guide, blank, width, height, x, y, x1, y + 1, 1.0F / 16.0F);
		float total = w1 + w2 + w3 + w4;
		if (total <= 0.0F) {
			return;
		}
		addError(field, width, height, x1, y, dl, da, db, w1 / total);
		addError(field, width, height, x2, y + 1, dl, da, db, w2 / total);
		addError(field, width, height, x, y + 1, dl, da, db, w3 / total);
		addError(field, width, height, x1, y + 1, dl, da, db, w4 / total);
	}

	private static float edgeWeight(float[] guide, boolean[] blank, int width, int height,
			int x, int y, int targetX, int targetY, float baseWeight) {
		if (targetX < 0 || targetX >= width || targetY >= height) {
			return 0.0F;
		}
		int target = targetY * width + targetX;
		if (blank != null && blank[target]) {
			return 0.0F;
		}
		int source = (y * width + x) * 3;
		int neighbor = target * 3;
		float edge = Math.max(Math.abs(guide[source] - guide[neighbor]),
			Math.max(Math.abs(guide[source + 1] - guide[neighbor + 1]),
				Math.abs(guide[source + 2] - guide[neighbor + 2])));
		float normalizedEdge = edge * 255.0F;
		return baseWeight / (1.0F + EDGE_STRENGTH * normalizedEdge * normalizedEdge);
	}

	private static void addError(float[] field, int width, int height, int x, int y,
			float dl, float da, float db, float weight) {
		if (weight == 0.0F || x < 0 || x >= width || y >= height) {
			return;
		}
		int index = (y * width + x) * 3;
		field[index] += dl * weight;
		field[index + 1] += da * weight;
		field[index + 2] += db * weight;
	}

	private static void join(Thread[] workers) {
		for (Thread worker : workers) {
			if (worker == null) {
				continue;
			}
			try {
				worker.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Quantizer interrupted", e);
			}
		}
	}
}
