package com.javahost.installer;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.nio.file.Path;
import java.util.List;

import com.javahost.util.UpdateManifest;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** The installer's window: a branded sidebar on the left, and options → progress → done on the right. */
public final class InstallerWindow extends JPanel {
	static final Color BG = new Color(0x161B22);
	static final Color SIDEBAR = new Color(0x0F1318);
	static final Color CARD = new Color(0x1E252E);
	static final Color BORDER = new Color(0x2C3540);
	static final Color TEXT = new Color(0xE8EDF2);
	static final Color MUTED = new Color(0x8B96A3);
	static final Color ACCENT = new Color(0x3DDC84);
	static final Color WARNING = new Color(0xF2B33D);
	static final Color DANGER = new Color(0xEF5350);

	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final Runnable onClose;
	private Path minecraftDir = MinecraftDirs.defaultMinecraftDir();

	/** The latest release (its build for each Minecraft version), once it has loaded. */
	private UpdateManifest release;
	private String releaseProblem;

	// Options page
	private JLabel optionsTitle;
	private JComboBox<UpdateManifest.Target> versionBox;
	private JTextField folderField;
	private JLabel folderStatus;
	private JCheckBox perf;
	private JCheckBox relay;
	private JCheckBox shaders;
	private JCheckBox importSettings;
	private JCheckBox importPacks;
	private JCheckBox importWorlds;
	private JLabel worldsHint;
	private JLabel launcherWarning;
	private JButton installButton;
	private JButton uninstallButton;

	// Progress page
	private JLabel progressTitle;
	private JProgressBar progressBar;
	private JLabel progressStep;

	// Done and error pages
	private JLabel doneTitle;
	private JLabel doneText;
	private JButton openLauncher;
	private JLabel errorText;

	public InstallerWindow(Runnable onClose) {
		super(new BorderLayout());
		this.onClose = onClose;
		setBackground(BG);
		add(new Sidebar(), BorderLayout.WEST);
		content.setOpaque(false);
		content.add(optionsPage(), "options");
		content.add(progressPage(), "progress");
		content.add(donePage(), "done");
		content.add(errorPage(), "error");
		add(content, BorderLayout.CENTER);
		setPreferredSize(new Dimension(780, 560));
		refreshOptions();
		loadRelease();
	}

	/** Fetches the latest release's list of builds (one per Minecraft version) in the background. */
	private void loadRelease() {
		new SwingWorker<UpdateManifest, Void>() {
			@Override
			protected UpdateManifest doInBackground() throws Exception {
				return Releases.latest();
			}

			@Override
			protected void done() {
				try {
					showRelease(get());
				} catch (Exception e) {
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					releaseProblem = "Couldn't reach GitHub to see the Minecraft versions. Check your connection and reopen the installer.";
					System.err.println("Couldn't load the release: " + cause);
					refreshOptions();
				}
			}
		}.execute();
	}

	private void showRelease(UpdateManifest manifest) {
		release = manifest;
		releaseProblem = null;
		versionBox.removeAllItems();
		List<UpdateManifest.Target> targets = manifest.targets().isEmpty()
				? List.of(new UpdateManifest.Target(manifest.minecraft(), List.of(manifest.minecraft()), manifest.files()))
				: manifest.targets();
		for (UpdateManifest.Target target : targets) {
			versionBox.addItem(target);
		}
		versionBox.setSelectedIndex(0);
		refreshOptions();
	}

	private UpdateManifest.Target selectedTarget() {
		return (UpdateManifest.Target) versionBox.getSelectedItem();
	}

	private Edition selectedEdition() {
		UpdateManifest.Target target = selectedTarget();
		return new Edition(target != null ? target.minecraft() : Edition.ORIGINAL);
	}

	public void show(String page) {
		cards.show(content, page);
	}

	// ================================================================ options

	private JPanel optionsPage() {
		JPanel page = page();
		optionsTitle = title("Install JavaHost");
		page.add(optionsTitle);
		page.add(gap(4));
		page.add(muted("<html><div style='width:340px'>Pick the Minecraft version your mods are made for. Each version gets its own "
				+ "launcher profile and folder, so your normal game stays untouched.</div></html>"));
		page.add(gap(18));

		// Version on the left, folder on the right.
		JPanel labels = row();
		JLabel versionLabel = section("MINECRAFT VERSION");
		versionLabel.setPreferredSize(new Dimension(150, versionLabel.getPreferredSize().height));
		labels.add(versionLabel, BorderLayout.WEST);
		labels.add(section("MINECRAFT FOLDER"), BorderLayout.CENTER);
		labels.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		page.add(labels);
		page.add(gap(6));
		JPanel folderRow = row();
		versionBox = new JComboBox<>();
		versionBox.setPreferredSize(new Dimension(150, 30));
		versionBox.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
				String text = "Loading…";
				if (value instanceof UpdateManifest.Target target) {
					List<String> also = target.releases().stream().filter(r -> !r.equals(target.minecraft())).toList();
					text = index < 0 || also.isEmpty() ? target.minecraft()
							: "<html>" + target.minecraft() + "  <font color='#8B96A3'>also " + String.join(", ", also) + "</font></html>";
				} else if (releaseProblem != null) {
					text = "Unavailable";
				}
				return super.getListCellRendererComponent(list, text, index, selected, focus);
			}
		});
		versionBox.addActionListener(e -> refreshOptions());
		folderField = new JTextField();
		folderField.setEditable(false);
		folderField.putClientProperty("JComponent.roundRect", true);
		JButton change = new JButton("Change…");
		change.addActionListener(e -> chooseFolder());
		JPanel folder = new JPanel(new BorderLayout(8, 0));
		folder.setOpaque(false);
		folder.add(folderField, BorderLayout.CENTER);
		folder.add(change, BorderLayout.EAST);
		folderRow.add(versionBox, BorderLayout.WEST);
		folderRow.add(folder, BorderLayout.CENTER);
		page.add(folderRow);
		page.add(gap(4));
		folderStatus = muted("");
		page.add(folderStatus);
		page.add(gap(14));

		page.add(section("ADD-ONS"));
		page.add(gap(4));
		perf = option(page, "Performance mods", "Sodium, Lithium and friends for much higher FPS", true);
		relay = option(page, "Play with anyone", "A free relay for when friends can't connect to you directly", true);
		shaders = option(page, "Shaders", "Iris, so you can see the host's shader", true);
		page.add(gap(10));

		page.add(section("BRING OVER FROM NORMAL MINECRAFT"));
		page.add(gap(4));
		JPanel imports = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		imports.setOpaque(false);
		imports.setAlignmentX(Component.LEFT_ALIGNMENT);
		importSettings = check("Settings", true);
		importPacks = check("Packs & shaders", true);
		importWorlds = check("Worlds", false);
		imports.add(importSettings);
		imports.add(Box.createHorizontalStrut(14));
		imports.add(importPacks);
		imports.add(Box.createHorizontalStrut(14));
		imports.add(importWorlds);
		page.add(imports);
		worldsHint = muted("");
		page.add(worldsHint);

		page.add(Box.createVerticalGlue());
		launcherWarning = new JLabel("Close the Minecraft Launcher first, or it may undo the new profile.");
		launcherWarning.setForeground(WARNING);
		launcherWarning.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(launcherWarning);
		page.add(gap(8));

		JPanel buttons = row();
		uninstallButton = new JButton("Uninstall");
		uninstallButton.setPreferredSize(new Dimension(110, 40));
		uninstallButton.putClientProperty("JButton.buttonType", "borderless");
		uninstallButton.setForeground(DANGER);
		uninstallButton.addActionListener(e -> uninstall());
		installButton = primary("Install");
		installButton.addActionListener(e -> install());
		buttons.add(uninstallButton, BorderLayout.WEST);
		buttons.add(installButton, BorderLayout.EAST);
		page.add(buttons);
		return page;
	}

	private void refreshOptions() {
		folderField.setText(minecraftDir.toString());
		boolean found = MinecraftDirs.hasLauncherProfiles(minecraftDir);
		if (releaseProblem != null) {
			folderStatus.setText("✕  " + releaseProblem);
		} else {
			folderStatus.setText(found ? "✓  Minecraft Launcher found" : "✕  Open the Minecraft Launcher once, then come back");
		}
		folderStatus.setForeground(found && releaseProblem == null ? ACCENT : DANGER);
		versionBox.setEnabled(release != null);
		boolean installed = release != null && Installer.isInstalled(minecraftDir, selectedEdition());
		optionsTitle.setText(installed ? "Update JavaHost" : "Install JavaHost");
		installButton.setText(installed ? "Update" : "Install");
		installButton.setEnabled(found && release != null && selectedTarget() != null);
		uninstallButton.setVisible(installed);
		int worlds = DataImporter.worldCount(minecraftDir);
		long size = DataImporter.worldsSize(minecraftDir);
		importWorlds.setText(worlds == 0 ? "Worlds" : "Worlds (" + worlds + ", " + human(size) + ")");
		importWorlds.setToolTipText("Copies your singleplayer worlds so you can host them right away");
		importWorlds.setEnabled(worlds > 0);
		worldsHint.setText("Copies only. Your normal Minecraft stays exactly as it is.");
		launcherWarning.setVisible(MinecraftDirs.launcherRunning());
	}

	private void chooseFolder() {
		JFileChooser chooser = new JFileChooser(minecraftDir.toFile());
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Choose your .minecraft folder");
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			minecraftDir = chooser.getSelectedFile().toPath();
			refreshOptions();
		}
	}

	private void install() {
		UpdateManifest build = release.forMinecraft(selectedTarget().minecraft());
		Installer.Options options = new Installer.Options(minecraftDir, build, perf.isSelected(), relay.isSelected(), shaders.isSelected(),
				importSettings.isSelected(), importPacks.isSelected(), importWorlds.isSelected());
		Edition edition = options.edition();
		boolean updating = Installer.isInstalled(minecraftDir, edition);
		run(updating ? "Updating JavaHost" : "Installing JavaHost", progress -> Installer.install(options, progress),
				updating ? "JavaHost is up to date!" : "You're all set!", doneSteps(edition), true);
	}

	private static String doneSteps(Edition edition) {
		return "<html>1. Open the Minecraft Launcher<br>2. Pick the <b>" + edition.profileName() + "</b> profile next to the Play button<br>"
				+ "3. Press Play, then click <b>JavaHost</b> on the title screen</html>";
	}

	private void uninstall() {
		Edition edition = selectedEdition();
		run("Removing JavaHost", progress -> Installer.uninstall(minecraftDir, edition, false, progress), "JavaHost was removed",
				"<html>The " + escape(edition.profileName()) + " profile is gone from the launcher. Its folder (with any worlds you<br>played there) was kept at "
						+ escape(edition.gameDir(minecraftDir).toString()) + ".</html>",
				false);
	}

	private interface Task {
		void run(Installer.Progress progress) throws Exception;
	}

	private void run(String title, Task task, String doneHeadline, String doneBody, boolean offerLauncher) {
		progressTitle.setText(title + "…");
		progressBar.setValue(0);
		progressStep.setText("Starting…");
		show("progress");
		new SwingWorker<Void, Object[]>() {
			@Override
			protected Void doInBackground() throws Exception {
				task.run((fraction, step) -> publish(new Object[] {fraction, step}));
				return null;
			}

			@Override
			protected void process(List<Object[]> chunks) {
				Object[] last = chunks.getLast();
				progressBar.setValue((int) Math.round((double) last[0] * 1000));
				progressStep.setText((String) last[1]);
			}

			@Override
			protected void done() {
				try {
					get();
					doneTitle.setText(doneHeadline);
					doneText.setText(doneBody);
					openLauncher.setVisible(offerLauncher);
					show("done");
				} catch (Exception e) {
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					errorText.setText("<html>" + escape(cause.getMessage() == null ? cause.toString() : cause.getMessage()) + "</html>");
					show("error");
				}
			}
		}.execute();
	}

	// ================================================================ other pages

	private JPanel progressPage() {
		JPanel page = page();
		page.add(Box.createVerticalGlue());
		progressTitle = title("Installing JavaHost…");
		page.add(progressTitle);
		page.add(gap(16));
		progressBar = new JProgressBar(0, 1000);
		progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
		progressBar.setForeground(ACCENT);
		page.add(progressBar);
		page.add(gap(10));
		progressStep = muted("Starting…");
		page.add(progressStep);
		page.add(Box.createVerticalGlue());
		return page;
	}

	private JPanel donePage() {
		JPanel page = page();
		page.add(Box.createVerticalGlue());
		JComponent check = new CheckBadge();
		check.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(check);
		page.add(gap(16));
		doneTitle = title("You're all set!");
		page.add(doneTitle);
		page.add(gap(10));
		doneText = new JLabel();
		doneText.setForeground(MUTED);
		doneText.setFont(doneText.getFont().deriveFont(14f));
		doneText.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(doneText);
		page.add(Box.createVerticalGlue());
		JPanel buttons = row();
		JButton close = new JButton("Close");
		close.setPreferredSize(new Dimension(100, 40));
		close.addActionListener(e -> onClose.run());
		openLauncher = primary("Open Minecraft Launcher");
		openLauncher.addActionListener(e -> {
			OpenLauncher.open();
			onClose.run();
		});
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
		right.setOpaque(false);
		right.add(close);
		right.add(openLauncher);
		buttons.add(right, BorderLayout.EAST);
		page.add(buttons);
		return page;
	}

	private JPanel errorPage() {
		JPanel page = page();
		page.add(Box.createVerticalGlue());
		JLabel title = title("Something went wrong");
		title.setForeground(DANGER);
		page.add(title);
		page.add(gap(10));
		errorText = new JLabel();
		errorText.setForeground(TEXT);
		errorText.setAlignmentX(Component.LEFT_ALIGNMENT);
		page.add(errorText);
		page.add(gap(8));
		page.add(muted("Nothing was half-installed in a way that breaks Minecraft. You can try again."));
		page.add(Box.createVerticalGlue());
		JPanel buttons = row();
		JButton back = primary("Back");
		back.addActionListener(e -> {
			refreshOptions();
			show("options");
		});
		buttons.add(back, BorderLayout.EAST);
		page.add(buttons);
		return page;
	}

	// ================================================================ building blocks

	private static JPanel page() {
		JPanel page = new JPanel();
		page.setOpaque(false);
		page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
		page.setBorder(BorderFactory.createEmptyBorder(34, 36, 28, 36));
		return page;
	}

	private static JPanel row() {
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		return row;
	}

	private static Component gap(int height) {
		return Box.createVerticalStrut(height);
	}

	private static JLabel title(String text) {
		JLabel label = new JLabel(text);
		label.setForeground(TEXT);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 24f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel muted(String text) {
		JLabel label = new JLabel(text);
		label.setForeground(MUTED);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel section(String text) {
		JLabel label = new JLabel(text);
		label.setForeground(new Color(0x5A6572));
		label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JButton primary(String text) {
		JButton button = new JButton(text);
		button.setBackground(ACCENT);
		button.setForeground(new Color(0x0B1F14));
		button.setFont(button.getFont().deriveFont(Font.BOLD, 14f));
		button.setPreferredSize(new Dimension(Math.max(140, button.getPreferredSize().width + 40), 40));
		button.putClientProperty("JButton.buttonType", "roundRect");
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return button;
	}

	private static JCheckBox check(String text, boolean selected) {
		JCheckBox box = new JCheckBox(text, selected);
		box.setOpaque(false);
		box.setForeground(TEXT);
		return box;
	}

	/** A checkbox with a bold title and a muted description underneath, inside a card. */
	private static JCheckBox option(JPanel page, String title, String description, boolean selected) {
		JPanel card = new JPanel(new BorderLayout(10, 0)) {
			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(CARD);
				g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
				g2.setColor(BORDER);
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
				g2.dispose();
			}
		};
		card.setOpaque(false);
		card.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
		JCheckBox box = new JCheckBox();
		box.setSelected(selected);
		box.setOpaque(false);
		JPanel text = new JPanel();
		text.setOpaque(false);
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		JLabel t = new JLabel(title);
		t.setForeground(TEXT);
		t.setFont(t.getFont().deriveFont(Font.BOLD));
		JLabel d = new JLabel(description);
		d.setForeground(MUTED);
		text.add(t);
		text.add(d);
		card.add(box, BorderLayout.WEST);
		card.add(text, BorderLayout.CENTER);
		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				box.doClick();
			}
		});
		page.add(card);
		page.add(gap(6));
		return box;
	}

	private static String human(long bytes) {
		if (bytes < 1024 * 1024) {
			return Math.max(1, bytes / 1024) + " KB";
		}
		if (bytes < 1024L * 1024 * 1024) {
			return bytes / (1024 * 1024) + " MB";
		}
		return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	static Image logo(int size) {
		try (var in = InstallerWindow.class.getResourceAsStream("icon-256.png")) {
			return ImageIO.read(in).getScaledInstance(size, size, Image.SCALE_SMOOTH);
		} catch (Exception e) {
			return null;
		}
	}

	/** The dark branded panel on the left. */
	private static final class Sidebar extends JPanel {
		Sidebar() {
			setPreferredSize(new Dimension(270, 0));
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBorder(BorderFactory.createEmptyBorder(36, 30, 28, 26));

			Image logo = logo(64);
			if (logo != null) {
				JLabel icon = new JLabel(new ImageIcon(logo));
				icon.setAlignmentX(Component.LEFT_ALIGNMENT);
				add(icon);
			}
			add(gap(18));
			JLabel name = new JLabel("JavaHost");
			name.setForeground(TEXT);
			name.setFont(name.getFont().deriveFont(Font.BOLD, 30f));
			name.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(name);
			add(gap(6));
			JLabel tagline = new JLabel("<html>Your world, your friends,<br>no server needed.</html>");
			tagline.setForeground(MUTED);
			tagline.setFont(tagline.getFont().deriveFont(15f));
			tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(tagline);
			add(gap(28));
			for (String feature : List.of("Host from your own PC", "Proximity voice chat", "Share packs & shaders", "One-click FPS boost")) {
				JLabel item = new JLabel("✓   " + feature);
				item.setForeground(TEXT);
				item.setFont(item.getFont().deriveFont(14f));
				item.setAlignmentX(Component.LEFT_ALIGNMENT);
				add(item);
				add(gap(10));
			}
			add(Box.createVerticalGlue());
			JLabel version = new JLabel("JavaHost " + Installer.MOD_VERSION + "  ·  Fabric");
			version.setForeground(new Color(0x5A6572));
			version.setFont(version.getFont().deriveFont(11f));
			version.setAlignmentX(Component.LEFT_ALIGNMENT);
			add(version);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setPaint(new GradientPaint(0, 0, SIDEBAR, 0, getHeight(), new Color(0x0B1A12)));
			g2.fillRect(0, 0, getWidth(), getHeight());
			// A soft green glow behind the logo.
			float radius = 260;
			g2.setPaint(new RadialGradientPaint(70, 70, radius, new float[] {0f, 1f},
					new Color[] {new Color(61, 220, 132, 55), new Color(61, 220, 132, 0)}));
			g2.fillRect(0, 0, getWidth(), getHeight());
			g2.setColor(BORDER);
			g2.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
			g2.dispose();
		}
	}

	/** A big green circle with a check mark, for the "done" page. */
	private static final class CheckBadge extends JComponent {
		CheckBadge() {
			setPreferredSize(new Dimension(64, 64));
			setMaximumSize(new Dimension(64, 64));
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(new Color(61, 220, 132, 40));
			g2.fill(new Ellipse2D.Float(0, 0, 64, 64));
			g2.setColor(ACCENT);
			g2.fill(new Ellipse2D.Float(8, 8, 48, 48));
			g2.setColor(new Color(0x0B1F14));
			g2.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D tick = new Path2D.Float();
			tick.moveTo(21, 33);
			tick.lineTo(29, 41);
			tick.lineTo(44, 25);
			g2.draw(tick);
			g2.dispose();
		}
	}

	/** Lays out a component tree without a window (for offscreen screenshots). */
	static void layoutTree(Component component) {
		component.doLayout();
		if (component instanceof java.awt.Container container) {
			for (Component child : container.getComponents()) {
				layoutTree(child);
			}
		}
	}

	/** For screenshots: put the done/error pages into a realistic state. */
	void previewState(String page) {
		List<UpdateManifest.Target> targets = new java.util.ArrayList<>();
		for (String[] v : new String[][] {{"26.3"}, {"26.2"}, {"26.1.2", "26.1", "26.1.1"}, {"1.21.11"}, {"1.21.10", "1.21.9"},
				{"1.21.8", "1.21.6", "1.21.7"}, {"1.21.4"}, {"1.21.1", "1.21"}}) {
			targets.add(new UpdateManifest.Target(v[0], List.of(v), List.of()));
		}
		showRelease(new UpdateManifest(Installer.MOD_VERSION, 3, "26.3", "", "", "0", List.of(), targets));
		switch (page) {
			case "progress" -> {
				progressTitle.setText("Installing JavaHost…");
				progressBar.setValue(560);
				progressStep.setText("Downloading sodium-fabric-0.9.2+mc26.3.jar…");
			}
			case "done" -> {
				doneTitle.setText("You're all set!");
				doneText.setText(doneSteps(new Edition("1.21.1")));
				openLauncher.setVisible(true);
			}
			case "error" -> errorText.setText("<html>Couldn't reach Modrinth (HTTP 503). Check your internet connection.</html>");
			default -> {
			}
		}
		show(page);
	}

	static void onEdt(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
		} else {
			try {
				SwingUtilities.invokeAndWait(runnable);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
