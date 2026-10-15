package com.javahost.installer;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.javahost.util.UpdateManifest;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

/**
 * Starts the installer window, or works from the command line:
 * <pre>
 *   --install [minecraftDir] [--no-performance] [--no-relay] [--shaders] [--no-import] [--worlds]
 *   --uninstall [minecraftDir]
 *   --screenshot outputDir      (renders each page to PNG without opening a window)
 * </pre>
 */
public final class InstallerApp {
	private InstallerApp() {
	}

	public static void main(String[] args) throws Exception {
		List<String> list = Arrays.asList(args);
		if (list.contains("--screenshot")) {
			System.setProperty("java.awt.headless", "true");
			setupLookAndFeel();
			screenshots(Path.of(list.get(list.indexOf("--screenshot") + 1)));
			return;
		}
		if (list.contains("--install") || list.contains("--uninstall")) {
			System.exit(commandLine(list));
			return;
		}

		System.setProperty("apple.awt.application.name", "JavaHost Installer");
		System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
		setupLookAndFeel();
		SwingUtilities.invokeLater(() -> {
			JFrame frame = new JFrame("JavaHost Installer");
			frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
			frame.setContentPane(new InstallerWindow(frame::dispose));
			List<Image> icons = new ArrayList<>();
			for (int size : new int[] {16, 32, 64, 128, 256}) {
				Image icon = InstallerWindow.logo(size);
				if (icon != null) {
					icons.add(icon);
				}
			}
			frame.setIconImages(icons);
			if (!icons.isEmpty() && java.awt.Taskbar.isTaskbarSupported()) {
				try {
					java.awt.Taskbar.getTaskbar().setIconImage(icons.getLast());
				} catch (UnsupportedOperationException | SecurityException ignored) {
					// Not every platform lets us set the dock icon.
				}
			}
			frame.pack();
			frame.setResizable(false);
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);
		});
	}

	static void setupLookAndFeel() {
		FlatLaf.setGlobalExtraDefaults(Map.of(
				"@accentColor", "#3DDC84",
				"@background", "#161B22",
				"@foreground", "#E8EDF2"));
		FlatDarkLaf.setup();
		UIManager.put("Button.arc", 12);
		UIManager.put("Component.arc", 10);
		UIManager.put("CheckBox.arc", 6);
		UIManager.put("ProgressBar.arc", 8);
		UIManager.put("Component.focusWidth", 1);
	}

	private static int commandLine(List<String> args) {
		Path mc = MinecraftDirs.defaultMinecraftDir();
		for (int i = 0; i < args.size() - 1; i++) {
			if ((args.get(i).equals("--install") || args.get(i).equals("--uninstall")) && !args.get(i + 1).startsWith("--")) {
				mc = Path.of(args.get(i + 1));
			}
		}
		// --minecraft 1.21.1 picks the Minecraft version (default: the newest one JavaHost is made for).
		int at = args.indexOf("--minecraft");
		String minecraft = at >= 0 && at + 1 < args.size() ? args.get(at + 1) : null;
		Installer.Progress progress = (fraction, step) -> System.out.printf("[%3d%%] %s%n", Math.round(fraction * 100), step);
		try {
			if (args.contains("--uninstall")) {
				Installer.uninstall(mc, new Edition(minecraft != null ? minecraft : Edition.ORIGINAL), args.contains("--delete-folder"), progress);
			} else {
				progress.update(0, "Checking the latest JavaHost release...");
				UpdateManifest release = Releases.latest();
				String wanted = minecraft != null ? minecraft : release.minecraftVersions().getFirst();
				UpdateManifest build = release.forMinecraft(wanted);
				if (build == null) {
					throw new IllegalArgumentException("JavaHost " + release.version() + " isn't made for Minecraft " + wanted + ". It's for "
							+ String.join(", ", release.minecraftVersions()) + ".");
				}
				boolean imports = !args.contains("--no-import");
				Installer.install(new Installer.Options(mc, build, !args.contains("--no-performance"), !args.contains("--no-relay"),
						args.contains("--shaders"), imports, imports, args.contains("--worlds")), progress);
			}
			return 0;
		} catch (Exception e) {
			System.err.println("Failed: " + e.getMessage());
			return 1;
		}
	}

	private static void screenshots(Path outDir) throws Exception {
		Files.createDirectories(outDir);
		for (String page : List.of("options", "progress", "done", "error")) {
			InstallerWindow.onEdt(() -> {
				try {
					InstallerWindow window = new InstallerWindow(() -> {
					});
					window.previewState(page);
					window.setSize(window.getPreferredSize());
					InstallerWindow.layoutTree(window);
					int scale = 2;
					BufferedImage image = new BufferedImage(window.getWidth() * scale, window.getHeight() * scale, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g = image.createGraphics();
					g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
					g.scale(scale, scale);
					window.printAll(g);
					g.dispose();
					ImageIO.write(image, "png", outDir.resolve("installer-" + page + ".png").toFile());
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
		}
		System.out.println("Screenshots written to " + outDir.toAbsolutePath());
	}
}
