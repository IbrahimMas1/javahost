import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Draws the JavaHost logo (a green tile with a pixel "J") at every size we ship.
 * Run once from the project root:  java installer/tools/MakeIcons.java
 * Writes the mod icon, installer icons, and a Windows .ico; the macOS .icns is built with iconutil.
 */
public class MakeIcons {
	private static final String[] J = {
			"....#",
			"....#",
			"....#",
			"....#",
			"#...#",
			"#...#",
			".###."};

	public static void main(String[] args) throws IOException {
		Path modIcon = Path.of("src/main/resources/assets/javahost/icon.png");
		Path installerRes = Path.of("installer/src/main/resources/com/javahost/installer");
		Path packaging = Path.of("installer/packaging");
		Path iconset = packaging.resolve("JavaHost.iconset");
		Files.createDirectories(installerRes);
		Files.createDirectories(iconset);

		ImageIO.write(draw(128), "png", modIcon.toFile());
		for (int size : List.of(16, 32, 64, 128, 256)) {
			ImageIO.write(draw(size), "png", installerRes.resolve("icon-" + size + ".png").toFile());
		}
		// macOS iconset layout expected by `iconutil -c icns`.
		for (int size : List.of(16, 32, 128, 256, 512)) {
			ImageIO.write(draw(size), "png", iconset.resolve("icon_" + size + "x" + size + ".png").toFile());
			ImageIO.write(draw(size * 2), "png", iconset.resolve("icon_" + size + "x" + size + "@2x.png").toFile());
		}
		writeIco(packaging.resolve("JavaHost.ico"), List.of(16, 24, 32, 48, 64, 128, 256));
		System.out.println("Icons written.");
	}

	static BufferedImage draw(int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		float inset = size * 0.04f;
		float tile = size - inset * 2;
		float radius = tile * 0.46f;
		RoundRectangle2D shape = new RoundRectangle2D.Float(inset, inset, tile, tile, radius, radius);

		g.setPaint(new GradientPaint(0, inset, new Color(0x5AE89A), 0, inset + tile, new Color(0x1FAE5E)));
		g.fill(shape);
		// Soft top highlight.
		g.setClip(shape);
		g.setPaint(new GradientPaint(0, inset, new Color(255, 255, 255, 70), 0, inset + tile * 0.55f, new Color(255, 255, 255, 0)));
		g.fillRect(0, 0, size, size);
		g.setClip(null);
		if (size >= 32) {
			g.setStroke(new BasicStroke(Math.max(1f, size / 128f)));
			g.setColor(new Color(0, 0, 0, 40));
			g.draw(shape);
		}

		// Pixel "J", the Minecraft font's shape, in dark green.
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		float cell = tile * 0.085f;
		float glyphW = cell * 5;
		float glyphH = cell * 7;
		float gx = inset + (tile - glyphW) / 2f;
		float gy = inset + (tile - glyphH) / 2f;
		g.setColor(new Color(0x0B1F14));
		for (int row = 0; row < J.length; row++) {
			for (int col = 0; col < 5; col++) {
				if (J[row].charAt(col) == '#') {
					int x0 = Math.round(gx + col * cell);
					int y0 = Math.round(gy + row * cell);
					int x1 = Math.round(gx + (col + 1) * cell);
					int y1 = Math.round(gy + (row + 1) * cell);
					g.fillRect(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
				}
			}
		}
		g.dispose();
		return image;
	}

	/** An .ico with PNG-compressed images (supported since Windows Vista). */
	static void writeIco(Path target, List<Integer> sizes) throws IOException {
		byte[][] pngs = new byte[sizes.size()][];
		for (int i = 0; i < sizes.size(); i++) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(draw(sizes.get(i)), "png", out);
			pngs[i] = out.toByteArray();
		}
		int headerSize = 6 + 16 * sizes.size();
		int total = headerSize;
		for (byte[] png : pngs) {
			total += png.length;
		}
		ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
		buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.size());
		int offset = headerSize;
		for (int i = 0; i < sizes.size(); i++) {
			int s = sizes.get(i);
			buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
			buf.putShort((short) 1).putShort((short) 32).putInt(pngs[i].length).putInt(offset);
			offset += pngs[i].length;
		}
		for (byte[] png : pngs) {
			buf.put(png);
		}
		Files.write(target, buf.array());
	}
}
