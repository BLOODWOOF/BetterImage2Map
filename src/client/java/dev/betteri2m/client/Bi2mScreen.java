package dev.betteri2m.client;

import dev.betteri2m.Job;
import dev.betteri2m.net.Bi2mNetworking;
import dev.betteri2m.render.Background;
import dev.betteri2m.render.Quantizer;
import dev.betteri2m.store.ImageFetcher;
import dev.betteri2m.Bi2mConfig;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class Bi2mScreen extends Screen {
	private static final int LABEL = 0xFFA0A0A0;
	private static final int MUTED = 0xFFA0A0A0;
	private static final int OK = 0xFF7FE07F;
	private static final int BAD = 0xFFFF5555;

	private static final int CONTENT_W = 320;
	private static final int TOP = 34;

	private static final long REFRESH_DELAY_MS = 300L;

	private static final long ANSWER_TIMEOUT_MS = 60_000L;

	private static final class State {
		private String url = "";
		private String path = "";
		private String framesX = "";
		private String framesY = "";
		private String copies = "1";
		private Quantizer.Mode dither = Quantizer.Mode.SIERRA_LITE;
		private Background background = Background.WHITE;
		private boolean cropEnabled;
		private int cropX;
		private int cropY;
		private int cropW;
		private int cropH;
		private Bi2mClient.Source source = Bi2mClient.Source.URL;
		private BufferedImage image;
		private String imageName = "";
		private Bi2mNetworking.PreviewData preview;
	}

	private static final State STATE = new State();

	private EditBox urlField;
	private EditBox pathField;
	private EditBox widthField;
	private EditBox heightField;
	private EditBox copiesField;
	private Button autoButton;
	private Button ditherButton;
	private Button cropButton;
	private Button backgroundButton;

	private int left;
	private int top;
	private int contentWidth;
	private int linkLabelY;
	private int fileLabelY;
	private int sizeLabelY;
	private int settingsLabelY;
	private int settingsControlY;
	private boolean compactLayout;
	private boolean narrowLayout;
	/** Guards initialization from edit listeners. */
	private boolean loading;
	private String status = "";
	private boolean statusError;
	private Bi2mPreview preview;
	private boolean busy;
	/** Tracks request timeout. */
	private long busyAt;

	private boolean dragging;
	private int dragFromX;
	private int dragFromY;
	/** Crop selection in preview pixels. */
	private int dragX;
	private int dragY;
	private int dragW;
	private int dragH;
	/** Tracks pending preview refresh. */
	private boolean dirty;
	private long dirtyAt;

	private int previewX;
	private int previewY;
	private int previewW;
	private int previewH;
	private int previewDrawX;
	private int previewDrawY;
	private int previewDrawW;
	private int previewDrawH;

	public Bi2mScreen() {
		super(Component.literal("BetterImage2Map"));
	}

	@Override
	protected void init() {
		this.loading = true;
		this.contentWidth = Math.max(1, Math.min(CONTENT_W, this.width - 16));
		this.compactLayout = this.contentWidth < 280;
		this.narrowLayout = this.contentWidth < 256;
		int contentWidth = this.contentWidth;
		int left = Math.max(8, (this.width - contentWidth) / 2);
		int top = this.height < 220 ? 24 : Math.max(30, Math.min(TOP, this.height / 12));
		int rowStep = this.height < 230 ? (this.narrowLayout ? 18 : 20) : 30;
		int linkY = top + 12;
		int fileY = linkY + rowStep;
		int sizeY = fileY + rowStep;
		int settingsY = sizeY + rowStep;
		this.settingsControlY = settingsY + (this.narrowLayout ? 20 : 0);
		this.linkLabelY = linkY - 10;
		this.fileLabelY = fileY - 10;
		this.sizeLabelY = sizeY - 10;
		this.settingsLabelY = settingsY - 10;
		this.left = left;
		this.top = top;
		this.status = "";

		int sourceLabelWidth = this.narrowLayout ? 24 : 42;
		int sourceActionWidth = this.narrowLayout ? Math.min(48, Math.max(1, contentWidth / 4)) : 58;
		int sourceFieldWidth = Math.max(1, contentWidth - sourceLabelWidth - sourceActionWidth - 8);
		this.urlField = new EditBox(this.font, this.left + sourceLabelWidth, linkY, sourceFieldWidth, 18, Component.literal("Image link"));
		this.urlField.setMaxLength(2048);
		this.urlField.setHint(Component.literal("https://... image link"));
		this.urlField.setValue(STATE.url);
		this.urlField.setResponder(text -> {
			STATE.url = text;
			if (!this.loading && !text.isBlank()) {
				STATE.source = Bi2mClient.Source.URL;
			}
		});
		this.addRenderableWidget(this.urlField);

		this.addRenderableWidget(Button.builder(Component.literal(this.narrowLayout ? "Get" : "Fetch"), b -> this.useUrl())
			.bounds(this.left + contentWidth - sourceActionWidth, linkY, sourceActionWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Fetch this link and show what it would build")))
			.build());

		int pathLabelWidth = this.narrowLayout ? 24 : 42;
		int narrowButtonWidth = Math.min(40, Math.max(1, contentWidth / 5));
		int pathFieldWidth = this.narrowLayout
			? Math.max(1, contentWidth - pathLabelWidth - narrowButtonWidth * 2 - 12)
			: Math.max(1, contentWidth - 186);
		int pickerX = this.left + (this.narrowLayout ? pathLabelWidth + pathFieldWidth + 4
			: Math.max(pathLabelWidth + pathFieldWidth + 4, contentWidth - 120));
		this.pathField = new EditBox(this.font, this.left + pathLabelWidth, fileY, pathFieldWidth, 18, Component.literal("Server file"));
		this.pathField.setMaxLength(512);
		this.pathField.setHint(Component.literal("path on the server"));
		this.pathField.setValue(STATE.path);
		this.pathField.setResponder(text -> {
			STATE.path = text;
			if (!this.loading && !text.isBlank()) {
				STATE.source = Bi2mClient.Source.SERVER_FILE;
			}
		});
		this.addRenderableWidget(this.pathField);

		int pickerWidth = this.narrowLayout ? narrowButtonWidth : 58;
		int useFileWidth = this.narrowLayout ? narrowButtonWidth : 58;
		int useFileX = this.narrowLayout ? pickerX + pickerWidth + 4 : this.left + contentWidth - 58;
		this.addRenderableWidget(Button.builder(Component.literal(this.narrowLayout ? "Pick" : "Browse..."), b -> this.browse())
			.bounds(pickerX, fileY, pickerWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Pick a picture with your computer's own file picker")))
			.build());

		this.addRenderableWidget(Button.builder(Component.literal(this.narrowLayout ? "Read" : "Use file"), b -> this.usePath())
			.bounds(useFileX, fileY, useFileWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Read this path on the server and preview it")))
			.build());

		int sizeLabelWidth = this.narrowLayout ? 32 : 8;
		int sizeAutoWidth = this.narrowLayout ? Math.max(1, Math.min(32, contentWidth / 5)) : 44;
		int sizeWidth = this.narrowLayout
			? Math.max(1, Math.min(56, (contentWidth - sizeLabelWidth - sizeAutoWidth - 8) / 2))
			: Math.max(38, Math.min(56, (contentWidth - 208) / 2));
		int sizeGap = 4;
		int sizeX = this.left + (this.narrowLayout ? sizeLabelWidth
			: Math.max(8, contentWidth - (sizeWidth * 2 + sizeGap + sizeAutoWidth + 8)));
		this.widthField = new EditBox(this.font, sizeX, sizeY, sizeWidth, 18, Component.literal("Frames wide"));
		this.widthField.setMaxLength(32);
		this.widthField.setHint(Component.literal("auto"));
		this.widthField.setValue(STATE.framesX);
		this.widthField.setResponder(text -> {
			STATE.framesX = text;
			this.refreshLabels();
			this.markDirty();
		});
		this.addRenderableWidget(this.widthField);

		this.heightField = new EditBox(this.font, sizeX + sizeWidth + sizeGap, sizeY, sizeWidth, 18, Component.literal("Frames tall"));
		this.heightField.setMaxLength(32);
		this.heightField.setHint(Component.literal("auto"));
		this.heightField.setValue(STATE.framesY);
		this.heightField.setResponder(text -> {
			STATE.framesY = text;
			this.refreshLabels();
			this.markDirty();
		});
		this.addRenderableWidget(this.heightField);

		this.autoButton = Button.builder(this.narrowLayout ? narrowAutoLabel() : autoLabel(), b -> {
			this.widthField.setValue("");
			this.heightField.setValue("");
			this.refreshLabels();
			this.markDirty();
		}).bounds(sizeX + sizeWidth * 2 + sizeGap * 2, sizeY, sizeAutoWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Clear the size boxes and fit the image")))
			.build();
		this.addRenderableWidget(this.autoButton);

		this.copiesField = new EditBox(this.font, this.left + (this.narrowLayout ? 42 : this.compactLayout ? 46 : 50), settingsY,
			this.narrowLayout ? 24 : this.compactLayout ? 24 : 28, 18, Component.literal("Copies"));
		this.copiesField.setMaxLength(2);
		this.copiesField.setHint(Component.literal("1"));
		this.copiesField.setValue(STATE.copies);
		this.copiesField.setResponder(text -> STATE.copies = text);
		this.addRenderableWidget(this.copiesField);

		int narrowSettingWidth = (contentWidth - 8) / 3;
		int ditherWidth = this.narrowLayout ? narrowSettingWidth : this.compactLayout ? 46 : 56;
		int cropWidth = this.narrowLayout ? narrowSettingWidth : this.compactLayout ? 46 : 56;
		int settingY = settingsY + (this.narrowLayout ? 20 : 0);
		int ditherX = this.left + (this.narrowLayout ? 0 : this.compactLayout ? 74 : 82);
		int cropX = this.left + (this.narrowLayout ? narrowSettingWidth + 4 : this.compactLayout ? 124 : 142);
		int backgroundX = this.left + (this.narrowLayout ? 2 * (narrowSettingWidth + 4)
			: this.compactLayout ? 174 : 202);
		int backgroundWidth = this.narrowLayout ? narrowSettingWidth
			: this.compactLayout ? contentWidth - 174 : 64;
		this.ditherButton = Button.builder(this.narrowLayout ? narrowDitherLabel() : ditherLabel(), b -> {
			STATE.dither = switch (STATE.dither) {
				case SIERRA_LITE -> Quantizer.Mode.FLOYD;
				case FLOYD -> Quantizer.Mode.ORDERED;
				case ORDERED -> Quantizer.Mode.FAMILY;
				case FAMILY -> Quantizer.Mode.KNOLL;
				case KNOLL -> Quantizer.Mode.ATKINSON;
				case ATKINSON -> Quantizer.Mode.NONE;
				case NONE -> Quantizer.Mode.SIERRA_LITE;
			};
			b.setMessage(this.narrowLayout ? narrowDitherLabel() : ditherLabel());
			this.markDirty();
		}).bounds(ditherX, settingY, ditherWidth, 18)
			.tooltip(Tooltip.create(Component.literal(
				"Cycle dithering: Sierra Lite, Floyd-Steinberg, ordered, map-family, Knoll, Atkinson, or off")))
			.build();
		this.addRenderableWidget(this.ditherButton);

		this.cropButton = Button.builder(this.narrowLayout ? narrowCropLabel() : cropLabel(), b -> {
			STATE.cropEnabled = !STATE.cropEnabled;
			b.setMessage(this.narrowLayout ? narrowCropLabel() : cropLabel());
			if (STATE.cropW > 0 && STATE.cropH > 0) {
				this.markDirty();
			}
		}).bounds(cropX, settingY, cropWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Drag on the preview to pick a region")))
			.build();
		this.addRenderableWidget(this.cropButton);

		this.backgroundButton = Button.builder(backgroundLabel(), b -> {
			STATE.background = switch (STATE.background) {
				case WHITE -> Background.BLACK;
				case BLACK -> Background.NONE;
				case NONE -> Background.WHITE;
			};
			b.setMessage(backgroundLabel());
			this.markDirty();
		}).bounds(backgroundX, settingY, backgroundWidth, 18)
			.tooltip(Tooltip.create(Component.literal("What goes behind the see-through parts of a PNG")))
			.build();
		this.addRenderableWidget(this.backgroundButton);

		int bottom = this.height - 28;
		int footerX = this.left;
		int footerWidth = this.narrowLayout ? (contentWidth - 12) / 4 : this.compactLayout ? 48 : 60;
		int resetWidth = this.narrowLayout ? footerWidth : this.compactLayout ? 44 : 70;
		int actionWidth = this.narrowLayout ? footerWidth : this.compactLayout ? 44 : 56;
		int footerGap = 4;
		int footerSpan = footerWidth + resetWidth + actionWidth * 2 + footerGap * 3;
		int footerStart = this.narrowLayout ? footerX : footerX + Math.max(0, (contentWidth - footerSpan) / 2);
		int firstFooterY = bottom;
		int resetX = footerStart + footerWidth + footerGap;
		int cancelX = resetX + resetWidth + footerGap;
		int doneX = cancelX + actionWidth + footerGap;
		this.addRenderableWidget(Button.builder(Component.literal(this.narrowLayout ? "Prev" : "Preview"), b -> this.previewSource())
			.bounds(footerStart, firstFooterY, footerWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Show what the current settings would build")))
			.build());

		this.addRenderableWidget(Button.builder(Component.literal(this.compactLayout ? "Reset" : "Reset crop"), b -> this.resetCrop())
			.bounds(resetX, firstFooterY, resetWidth, 18).build());

		this.addRenderableWidget(Button.builder(Component.literal(this.narrowLayout ? "Back" : "Cancel"), b -> this.onClose())
			.bounds(cancelX, bottom, actionWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Close and keep these settings")))
			.build());

		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.done())
			.bounds(doneX, bottom, actionWidth, 18)
			.tooltip(Tooltip.create(Component.literal("Create the maps and close")))
			.build());

		this.loading = false;
		this.refreshLabels();
		restorePreview();
		Bi2mClient.setPreviewTarget(this);
	}

	/** Called by the client when the server sends back what it produced. */
	public void acceptPreview(Bi2mNetworking.PreviewData data) {
		if (this.preview != null) {
			this.preview.close();
		}
		this.preview = Bi2mPreview.of(data);
		STATE.preview = data;
		this.previewDrawX = this.left + this.contentWidth / 2;
		this.previewDrawY = this.height / 2;
		this.previewDrawW = 0;
		this.previewDrawH = 0;
		this.dragW = 0;
		this.dragH = 0;
		this.busy = false;
	}

	public void setStatus(String text, boolean error) {
		this.status = text;
		this.statusError = error;
		this.busyAt = System.currentTimeMillis();
		if (error) {
			this.busy = false;
		}
	}

	/** Restores the last preview. */
	private void restorePreview() {
		if (this.preview != null) {
			this.preview.close();
			this.preview = null;
		}
		if (STATE.preview != null) {
			this.preview = Bi2mPreview.of(STATE.preview);
		}
		this.previewDrawX = this.left + this.contentWidth / 2;
		this.previewDrawY = this.height / 2;
		this.previewDrawW = 0;
		this.previewDrawH = 0;
	}

	/** Previews the selected link. */
	private void useUrl() {
		String url = this.urlField.getValue().trim();
		if (url.isEmpty()) {
			setStatus("Enter an image link first", true);
			return;
		}
		STATE.source = Bi2mClient.Source.URL;
		previewSource();
	}

	/** Previews the selected server file. */
	private void usePath() {
		String path = this.pathField.getValue().trim();
		if (path.isEmpty()) {
			setStatus("Enter a file path first", true);
			return;
		}
		STATE.source = Bi2mClient.Source.SERVER_FILE;
		previewSource();
	}

	/** Opens the system image picker. */
	private void browse() {
		setStatus("Opening your file picker...", false);
		FilePicker.openImage(chosen -> {
			if (chosen.failure() != null) {
				setStatus(chosen.failure(), true);
			} else if (chosen.image() == null) {
				setStatus(STATE.image != null ? "Using " + STATE.imageName : "", false);
			} else if (this.minecraft != null && this.minecraft.gui.screen() == this) {
				adoptLocalImage(chosen.image(), chosen.name());
			} else {
				STATE.image = chosen.image();
				STATE.imageName = chosen.name();
				STATE.source = Bi2mClient.Source.LOCAL_IMAGE;
			}
		});
	}

	private void resetCrop() {
		boolean hadSelection = STATE.cropW > 0 && STATE.cropH > 0;
		STATE.cropEnabled = false;
		STATE.cropW = 0;
		STATE.cropH = 0;
		if (this.cropButton != null) {
			this.cropButton.setMessage(cropLabel());
		}
		if (hadSelection) {
			this.markDirty();
		}
	}

	/** Uses an image from this computer. */
	private void adoptLocalImage(BufferedImage image, String name) {
		STATE.image = image;
		STATE.imageName = name;
		STATE.source = Bi2mClient.Source.LOCAL_IMAGE;
		previewSource();
	}

	/** Previews the current settings. */
	private void previewSource() {
		previewSource(false);
	}

	/** Requests a preview with optional status updates. */
	private void previewSource(boolean quiet) {
		Bi2mClient.Request request = request();
		if (request == null) {
			if (!quiet) {
				setStatus(STATE.image != null ? "" : "Pick an image first", STATE.image == null);
			}
			return;
		}
		submit(request, true, quiet);
	}

	/** Creates maps for the current request. */
	private void done() {
		Bi2mClient.Request request = request();
		if (request == null) {
			setStatus(STATE.image != null ? "" : "Pick an image first", STATE.image == null);
			return;
		}
		submit(request, false);
		this.onClose();
	}

	private void submit(Bi2mClient.Request request, boolean previewOnly) {
		submit(request, previewOnly, false);
	}

	private void submit(Bi2mClient.Request request, boolean previewOnly, boolean quiet) {
		if (!Bi2mClient.send(request, previewOnly)) {
			this.dirty = false;
			setStatus("That request didn't go through", true);
			return;
		}
		if (!quiet) {
			setStatus(previewOnly ? "Previewing..." : "Creating...", false);
		}
		this.busy = true;
		this.busyAt = System.currentTimeMillis();
	}

	/** Refreshes the preview after settings stop changing. */
	@Override
	public void tick() {
		super.tick();
		if (this.busy && System.currentTimeMillis() - this.busyAt > ANSWER_TIMEOUT_MS) {
			setStatus("The server didn't answer", true);
		}
		if (!this.dirty || this.busy || this.preview == null
				|| System.currentTimeMillis() - this.dirtyAt < REFRESH_DELAY_MS) {
			return;
		}
		this.dirty = false;
		previewSource(true);
	}

	/** Marks the preview as out of date. */
	private void markDirty() {
		if (this.loading) {
			return;
		}
		this.dirty = true;
		this.dirtyAt = System.currentTimeMillis();
	}

	/** Builds the current request. */
	private Bi2mClient.Request request() {
		int cropX = 0;
		int cropY = 0;
		int cropW = 0;
		int cropH = 0;
		int[] crop = crop();
		if (crop != null) {
			cropX = crop[0];
			cropY = crop[1];
			cropW = crop[2];
			cropH = crop[3];
		}
		double framesX = sizeX();
		double framesY = sizeY();
		int copies = copies();
		return switch (STATE.source) {
			case URL -> this.urlField.getValue().trim().isEmpty() ? null
				: new Bi2mClient.Request(Bi2mClient.Source.URL, this.urlField.getValue(), null, "",
					framesX, framesY, copies, STATE.dither, cropX, cropY, cropW, cropH, STATE.background);
			case SERVER_FILE -> this.pathField.getValue().trim().isEmpty() ? null
				: new Bi2mClient.Request(Bi2mClient.Source.SERVER_FILE, this.pathField.getValue(), null, "",
					framesX, framesY, copies, STATE.dither, cropX, cropY, cropW, cropH, STATE.background);
			case LOCAL_IMAGE -> STATE.image == null ? null
				: new Bi2mClient.Request(Bi2mClient.Source.LOCAL_IMAGE, "", STATE.image, STATE.imageName,
					framesX, framesY, copies, STATE.dither, cropX, cropY, cropW, cropH, STATE.background);
		};
	}

	private void refreshLabels() {
		if (this.autoButton != null) {
			this.autoButton.setMessage(this.narrowLayout ? narrowAutoLabel() : autoLabel());
		}
	}

	/** Returns the active crop. */
	private int[] crop() {
		if (!STATE.cropEnabled || STATE.cropW <= 0 || STATE.cropH <= 0) {
			return null;
		}
		return new int[] {STATE.cropX, STATE.cropY, STATE.cropW, STATE.cropH};
	}

	private double sizeX() {
		return parse(this.widthField);
	}

	private double sizeY() {
		return parse(this.heightField);
	}

	private int copies() {
		if (this.copiesField == null) {
			return 1;
		}
		try {
			return Math.max(1, Math.min(Job.MAX_COPIES, Integer.parseInt(this.copiesField.getValue().trim())));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static double parse(EditBox box) {
		if (box == null || box.getValue().isBlank()) {
			return 0.0D;
		}
		try {
			double value = Double.parseDouble(box.getValue().trim());
			return Double.isFinite(value) && value > 0.0D ? value : 0.0D;
		} catch (NumberFormatException e) {
			return 0.0D;
		}
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (paths.isEmpty()) {
			return;
		}
		Path path = paths.get(0);
		try {
			BufferedImage image = ImageFetcher.decode(Files.readAllBytes(path), new Bi2mConfig());
			adoptLocalImage(image, path.getFileName().toString());
		} catch (IOException e) {
			setStatus(e.getMessage() == null ? "Could not read that file" : e.getMessage(), true);
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		boolean typing = this.urlField != null && this.urlField.isFocused()
			|| this.pathField != null && this.pathField.isFocused()
			|| this.widthField != null && this.widthField.isFocused()
			|| this.heightField != null && this.heightField.isFocused()
			|| this.copiesField != null && this.copiesField.isFocused();
		if (Bi2mClient.isPasteCombo(event) && !typing) {
			BufferedImage image = Bi2mClient.readClipboardImage();
			if (image != null) {
				adoptLocalImage(image, "clipboard.png");
				return true;
			}
		}
		return super.keyPressed(event);
	}

	/** Draws the labels and image preview. */
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		separator(graphics, Screen.INWORLD_HEADER_SEPARATOR, 26);

		graphics.text(this.font, Component.literal("Link"), this.left, this.linkLabelY, LABEL, false);
		graphics.text(this.font, Component.literal("File"), this.left, this.fileLabelY, LABEL, false);
		graphics.text(this.font, Component.literal(this.narrowLayout ? "Size" : "Frames"), this.left, this.sizeLabelY, LABEL, false);
		graphics.text(this.font, Component.literal(this.narrowLayout ? "Qty" : "Bundles"), this.left, this.settingsLabelY, LABEL, false);
		graphics.text(this.font, Component.literal(this.narrowLayout ? "BG" : this.compactLayout ? "BG" : "Background"),
			this.left + (this.narrowLayout ? 2 * (((this.contentWidth - 8) / 3) + 4)
				: this.compactLayout ? 174 : 202), this.settingsLabelY + (this.narrowLayout ? 20 : 0), LABEL, false);

		int summaryY = this.settingsControlY + 24;
		if (this.height >= 240 && !this.narrowLayout) {
			graphics.text(this.font, this.sizeSummary(), this.left, summaryY, MUTED, false);
		}
		boolean showStatus = !this.status.isEmpty() && this.height >= 240 && summaryY + 22 < this.height - 28;
		if (showStatus) {
			graphics.text(this.font, Component.literal(this.status), this.left, summaryY + 12,
				this.statusError ? BAD : OK, false);
		}

		int previewTop = summaryY + (this.narrowLayout ? (showStatus ? 20 : 4) : 24);
		int previewHeight = this.height - (this.narrowLayout ? 40 : 66) - previewTop;
		int previewWidth = this.contentWidth;
		int previewLeft = this.left;
		if (this.preview != null && previewHeight > 20) {
			float scale = this.preview.scaleFor(previewWidth, previewHeight);
			this.previewDrawW = Math.max(1, Mth.floor(this.preview.width() * scale));
			this.previewDrawH = Math.max(1, Mth.floor(this.preview.height() * scale));
			this.previewDrawX = previewLeft + (previewWidth - this.previewDrawW) / 2;
			this.previewDrawY = previewTop + (previewHeight - this.previewDrawH) / 2;
			this.preview.render(graphics, previewLeft, previewTop, previewWidth, previewHeight);
			this.previewX = this.previewDrawX;
			this.previewY = this.previewDrawY;
			this.previewW = this.previewDrawW;
			this.previewH = this.previewDrawH;
			this.drawCropBox(graphics);
			if (this.busy) {
				graphics.text(this.font, Component.literal("updating..."), previewLeft + 4, previewTop + 4,
					0xFFFFFFFF, true);
			}
		} else if (previewTop + 16 < this.height - 40) {
			graphics.text(this.font, Component.literal(this.busy ? "Working..." : "No preview yet"),
				previewLeft, previewTop + 4, MUTED, false);
		}

		separator(graphics, Screen.INWORLD_FOOTER_SEPARATOR, this.height - 36);
	}

	/** Draws a screen separator. */
	private void separator(GuiGraphicsExtractor graphics, net.minecraft.resources.Identifier texture, int y) {
		int width = this.contentWidth;
		int left = this.left;
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture,
			left - 6, y, 0.0F, 0.0F, width + 12, 2, 32, 2);
	}

	/** Describes the current output size. */
	private Component sizeSummary() {
		if (STATE.preview != null) {
			StringBuilder text = new StringBuilder("Source " + STATE.preview.sourceWidth() + "x"
				+ STATE.preview.sourceHeight() + "  ->  " + STATE.preview.tilesX() + " x "
				+ STATE.preview.tilesY() + " frames");
			if (this.copies() > 1) {
				text.append("  x").append(this.copies());
			}
			return Component.literal(text.toString());
		}
		String local = STATE.image != null ? STATE.imageName + "  " : "";
		return Component.literal(local + "Frames " + sizeLabel(sizeX()) + " x "
			+ sizeLabel(sizeY()) + "   bundles " + this.copies());
	}

	/** Draws the crop selection. */
	private void drawCropBox(GuiGraphicsExtractor graphics) {
		if (this.dragW <= 0 || this.dragH <= 0 || this.preview == null) {
			return;
		}
		float scale = previewScale();
		int boxLeft = this.previewDrawX + Math.round(this.dragX * scale);
		int boxTop = this.previewDrawY + Math.round(this.dragY * scale);
		int boxW = Math.max(1, Math.round(this.dragW * scale));
		int boxH = Math.max(1, Math.round(this.dragH * scale));
		graphics.outline(boxLeft, boxTop, boxW, boxH, 0xFFFFFFFF);
	}

	private float previewScale() {
		if (this.preview == null) {
			return 1.0F;
		}
		return this.preview.scaleFor(this.previewW, this.previewH);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
		if (STATE.cropEnabled && this.preview != null && insidePreview(event.x(), event.y())) {
			this.dragging = true;
			this.dragFromX = previewPixelX(event.x());
			this.dragFromY = previewPixelY(event.y());
			this.dragX = this.dragFromX;
			this.dragY = this.dragFromY;
			this.dragW = 0;
			this.dragH = 0;
			return true;
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
		if (this.dragging && this.preview != null) {
			int currentX = previewPixelX(event.x());
			int currentY = previewPixelY(event.y());
			this.dragX = Math.min(this.dragFromX, currentX);
			this.dragY = Math.min(this.dragFromY, currentY);
			this.dragW = Math.abs(currentX - this.dragFromX);
			this.dragH = Math.abs(currentY - this.dragFromY);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (this.dragging) {
			this.dragging = false;
			applySelection();
		}
		return super.mouseReleased(event);
	}

	/** Applies the selected preview crop. */
	private void applySelection() {
		if (this.preview == null || this.dragW <= 0 || this.dragH <= 0) {
			return;
		}
		float width = this.preview.width();
		float height = this.preview.height();
		int[] crop = Job.cropWithin(STATE.cropX, STATE.cropY, STATE.cropW, STATE.cropH,
			this.dragX / width, this.dragY / height, this.dragW / width, this.dragH / height);
		STATE.cropX = crop[0];
		STATE.cropY = crop[1];
		STATE.cropW = crop[2];
		STATE.cropH = crop[3];
		this.markDirty();
	}

	/** Converts a screen x coordinate to preview pixels. */
	private int previewPixelX(double mouseX) {
		return Mth.clamp(Mth.floor((mouseX - this.previewDrawX) / previewScale()), 0, this.preview.width());
	}

	private int previewPixelY(double mouseY) {
		return Mth.clamp(Mth.floor((mouseY - this.previewDrawY) / previewScale()), 0, this.preview.height());
	}

	private boolean insidePreview(double mouseX, double mouseY) {
		if (this.preview == null) {
			return false;
		}
		return mouseX >= this.previewDrawX && mouseX < this.previewDrawX + this.previewDrawW
			&& mouseY >= this.previewDrawY && mouseY < this.previewDrawY + this.previewDrawH;
	}

	/** Returns whether the screen is in-game. */
	@Override
	public boolean isInGameUi() {
		return this.minecraft != null && this.minecraft.level != null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Bi2mClient.setPreviewTarget(null);
		if (this.preview != null) {
			this.preview.close();
			this.preview = null;
		}
		super.onClose();
	}

	private static String sizeLabel(double size) {
		return size > 0.0D ? Double.toString(size) : "auto";
	}

	private Component autoLabel() {
		return Component.literal(sizeX() > 0 || sizeY() > 0 ? "Custom" : "Auto");
	}

	private Component backgroundLabel() {
		String name = switch (STATE.background) {
			case WHITE -> "White";
			case BLACK -> "Black";
			case NONE -> "None";
		};
		return Component.literal(name).withStyle(ChatFormatting.WHITE);
	}

	private Component ditherLabel() {
		String label = switch (STATE.dither) {
			case SIERRA_LITE -> "Sierra";
			case FLOYD -> "Floyd";
			case ORDERED -> "Ordered";
			case FAMILY -> "Family";
			case KNOLL -> "Knoll";
			case ATKINSON -> "Atkinson";
			case NONE -> "Off";
		};
		return Component.literal(label).withStyle(ChatFormatting.WHITE);
	}

	private Component cropLabel() {
		return Component.literal(STATE.cropEnabled ? "Crop on" : "Crop off").withStyle(ChatFormatting.WHITE);
	}

	private Component narrowDitherLabel() {
		String label = switch (STATE.dither) {
			case SIERRA_LITE -> "S";
			case FLOYD -> "F";
			case ORDERED -> "O";
			case FAMILY -> "M";
			case KNOLL -> "K";
			case ATKINSON -> "A";
			case NONE -> "-";
		};
		return Component.literal(label).withStyle(ChatFormatting.WHITE);
	}

	private Component narrowCropLabel() {
		return Component.literal(STATE.cropEnabled ? "C+" : "C-").withStyle(ChatFormatting.WHITE);
	}

	private Component narrowAutoLabel() {
		return Component.literal(sizeX() > 0 || sizeY() > 0 ? "C" : "A");
	}
}
