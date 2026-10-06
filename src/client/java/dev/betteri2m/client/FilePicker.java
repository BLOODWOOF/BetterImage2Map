package dev.betteri2m.client;

import java.awt.FileDialog;
import java.awt.Frame;
import dev.betteri2m.Bi2mConfig;
import dev.betteri2m.store.ImageFetcher;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Opens the file picker that belongs to the desktop the game is running on: the common dialog on
 * Windows, the file panel on macOS, or zenity/kdialog on Linux. AWT's file dialog is the fallback
 * when a platform picker can't be reached.
 * <p>
 * The dialog belongs to the desktop, not the game, so a picture can be picked without leaving
 * Minecraft. Everything here - the dialog, the read, the decode - happens on a worker thread, and
 * only the finished picture is handed back to the game thread.
 */
final class FilePicker {
	/** Image types the pickers offer. */
	private static final String[] EXTENSIONS = {"png", "jpg", "jpeg", "webp", "bmp", "gif"};
	private static final String TITLE = "Pick an image";
	/** A picker left open this long is treated as abandoned. */
	private static final long TIMEOUT_SECONDS = 600L;
	/** Exit code for a picker this side had to kill, as opposed to one that failed on its own. */
	private static final int ABANDONED = -1;

	private static Path lastFolder;
	private static boolean showing;

	private FilePicker() {
	}

	/**
	 * Opens the picker. The callback runs on the game thread with the decoded picture,
	 * {@link Chosen#CANCELED} if the player backed out, or a reason if it failed. Further calls
	 * while a picker is open are ignored, so a double click can't stack two.
	 */
	static void openImage(Consumer<Chosen> callback) {
		synchronized (FilePicker.class) {
			if (showing) {
				return;
			}
			showing = true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Thread thread = new Thread(() -> {
			Chosen chosen = show();
			synchronized (FilePicker.class) {
				showing = false;
			}
			minecraft.execute(() -> callback.accept(chosen));
		}, "BetterImage2Map file picker");
		thread.setDaemon(true);
		thread.start();
	}

	/** Null means the platform's own picker wasn't available, so AWT takes over. */
	private static Chosen show() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		Chosen chosen;
		if (os.startsWith("windows")) {
			chosen = windowsPicker();
		} else if (os.startsWith("mac")) {
			chosen = macPicker();
		} else {
			chosen = linuxPicker();
		}
		return chosen != null ? chosen : awtPicker();
	}

	/** The Windows common dialog, driven through PowerShell. */
	private static Chosen windowsPicker() {
		Attempt attempt = run(windowsCommand());
		if (attempt == null) {
			return null;
		}
		if (attempt.exit() == ABANDONED) {
			return Chosen.CANCELED;
		}
		return attempt.exit() == 0 ? read(attempt.output()) : null;
	}

	/** The macOS file panel, through the system's own script runner. */
	private static Chosen macPicker() {
		Attempt attempt = run(macCommand());
		if (attempt == null) {
			return null;
		}
		if (attempt.exit() == ABANDONED) {
			return Chosen.CANCELED;
		}
		return attempt.exit() == 0 ? read(attempt.output()) : null;
	}

	/** zenity first, since it's the common one; kdialog if that isn't installed. */
	private static Chosen linuxPicker() {
		Chosen zenity = linuxAttempt(run(zenityCommand()));
		if (zenity != null) {
			return zenity;
		}
		return linuxAttempt(run(kdialogCommand()));
	}

	/** Null when this tool wasn't usable, so the next one gets a turn. */
	private static Chosen linuxAttempt(Attempt attempt) {
		if (attempt == null) {
			return null;
		}
		if (attempt.exit() == ABANDONED || attempt.exit() == 1) {
			return Chosen.CANCELED;
		}
		return attempt.exit() == 0 ? read(attempt.output()) : null;
	}

	/** The last resort: AWT's dialog, which is native on Windows and macOS but dated elsewhere. */
	private static Chosen awtPicker() {
		FileDialog dialog;
		try {
			dialog = new FileDialog((Frame) null, TITLE, FileDialog.LOAD);
		} catch (Throwable t) {
			// A runtime without a usable AWT has no dialog to offer; the text box still works.
			return Chosen.failed("No file picker available here");
		}
		try {
			dialog.setAlwaysOnTop(true);
			dialog.setMultipleMode(false);
			dialog.setFilenameFilter((folder, name) -> isImageName(name));
			Path start = startFolder();
			if (start != null) {
				dialog.setDirectory(start.toString());
			}
			dialog.setVisible(true);
			return read(resolve(dialog.getDirectory(), dialog.getFile()));
		} catch (Throwable t) {
			return Chosen.failed("The file picker didn't work");
		} finally {
			dialog.dispose();
		}
	}

	/** The Windows dialog: PowerShell loads WinForms and shows the same box Explorer offers. */
	static List<String> windowsCommand() {
		StringBuilder script = new StringBuilder();
		script.append("Add-Type -AssemblyName System.Windows.Forms;");
		script.append("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8;");
		script.append("$d = New-Object System.Windows.Forms.OpenFileDialog;");
		script.append("$d.Title = '").append(powerShellQuoted(TITLE)).append("';");
		script.append("$d.Filter = 'Images|").append(patterns(';')).append("|All files|*.*';");
		script.append("$d.Multiselect = $false;");
		Path start = startFolder();
		if (start != null) {
			script.append("$d.InitialDirectory = '").append(powerShellQuoted(start.toString())).append("';");
		}
		script.append("if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {");
		script.append("[Console]::Out.WriteLine($d.FileName)}");
		return List.of("powershell.exe", "-NoProfile", "-STA", "-WindowStyle", "Hidden", "-Command", script.toString());
	}

	/** The macOS panel. A cancel is AppleScript's error -128, turned into an empty result. */
	static List<String> macCommand() {
		StringBuilder script = new StringBuilder();
		script.append("try\n");
		script.append("set picked to choose file with prompt \"").append(appleScriptQuoted(TITLE)).append("\" of type {");
		for (int i = 0; i < EXTENSIONS.length; i++) {
			if (i > 0) {
				script.append(", ");
			}
			script.append('"').append(EXTENSIONS[i]).append('"');
		}
		script.append("}\n");
		script.append("return POSIX path of picked\n");
		script.append("on error number -128\n");
		script.append("return \"\"\n");
		script.append("end try");
		return List.of("osascript", "-e", script.toString());
	}

	static List<String> zenityCommand() {
		List<String> command = new ArrayList<>();
		command.add("zenity");
		command.add("--file-selection");
		command.add("--title=" + TITLE);
		Path start = startFolder();
		if (start != null) {
			command.add("--filename=" + start + "/");
		}
		command.add("--file-filter=Images | " + patterns(' '));
		return command;
	}

	static List<String> kdialogCommand() {
		Path start = startFolder();
		return List.of("kdialog", "--getopenfilename", start == null ? "." : start.toString(),
			"Images (" + patterns(' ') + ")");
	}

	/** The image types as "*.png;*.jpg" or "*.png *.jpg", whichever the picker wants. */
	private static String patterns(char separator) {
		StringBuilder text = new StringBuilder();
		for (String extension : EXTENSIONS) {
			if (text.length() > 0) {
				text.append(separator);
			}
			text.append("*.").append(extension);
		}
		return text.toString();
	}

	private static String powerShellQuoted(String value) {
		return value.replace("'", "''");
	}

	private static String appleScriptQuoted(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private record Attempt(int exit, String output) {
	}

	/** Runs a picker command and collects what it printed. Null when it couldn't even start. */
	private static Attempt run(List<String> command) {
		Process process;
		try {
			process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			return null;
		}
		try {
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return new Attempt(ABANDONED, "");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return new Attempt(ABANDONED, "");
		}
		String output = "";
		try {
			output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			// Nothing to read; the exit code is all that's left to go on.
		}
		return new Attempt(process.exitValue(), output);
	}

	/** Decodes whatever a picker handed back. */
	static Chosen read(String raw) {
		if (raw == null || raw.isBlank()) {
			return Chosen.CANCELED;
		}
		try {
			return read(Path.of(raw));
		} catch (InvalidPathException e) {
			return Chosen.CANCELED;
		}
	}

	static Chosen read(Path chosen) {
		if (chosen == null || !Files.isRegularFile(chosen)) {
			return Chosen.CANCELED;
		}
		lastFolder = chosen.getParent();
		try {
			BufferedImage image = ImageFetcher.decode(Files.readAllBytes(chosen), new Bi2mConfig());
			// The name travels with the picture so the screen can say which file this is; the
			// path never leaves the computer.
			return new Chosen(image, chosen.getFileName().toString(), null);
		} catch (IOException e) {
			return Chosen.failed("Could not read that file");
		}
	}

	/** True when the name looks like one of the extensions the pickers offer. */
	static boolean isImageName(String name) {
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String extension : EXTENSIONS) {
			if (lower.endsWith("." + extension)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Turns the two halves the dialog reports into the picked file. Dismissing the window any
	 * other way leaves the filter pattern sitting in the name box, so anything that isn't an
	 * existing file counts as no choice.
	 */
	static Path resolve(String folder, String name) {
		if (folder == null || name == null || name.indexOf('*') >= 0 || name.indexOf('?') >= 0) {
			return null;
		}
		try {
			Path chosen = Path.of(folder, name);
			return Files.isRegularFile(chosen) ? chosen : null;
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** Where the dialog opens: wherever it was last time, otherwise somewhere pictures live. */
	private static Path startFolder() {
		if (lastFolder != null && Files.isDirectory(lastFolder)) {
			return lastFolder;
		}
		Path home = Path.of(System.getProperty("user.home", "."));
		for (String name : new String[] {"Pictures", "Desktop", "Downloads"}) {
			Path candidate = home.resolve(name);
			if (Files.isDirectory(candidate)) {
				return candidate;
			}
		}
		return Files.isDirectory(home) ? home : null;
	}

	/** What came back from the dialog: a decoded picture, or why there isn't one. */
	record Chosen(BufferedImage image, String name, String failure) {
		static final Chosen CANCELED = new Chosen(null, null, null);

		static Chosen failed(String reason) {
			return new Chosen(null, null, reason);
		}
	}
}
