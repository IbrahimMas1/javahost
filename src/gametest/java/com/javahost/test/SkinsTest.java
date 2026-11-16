package com.javahost.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.javahost.JavaHost;
import com.javahost.client.compat.ClientCompat;
import com.javahost.client.friends.Identity;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.pages.SkinsPage;
import com.javahost.client.gui.ui.Faces;
import com.javahost.client.gui.ui.IdleSkin;
import com.javahost.client.skins.SkinLibrary;
import com.javahost.client.skins.SkinService;
import com.javahost.util.SkinFolder;
import com.javahost.util.SkinImage;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * The skin changer, against a pretend Minecraft profile service running inside this test (never the real one):
 * opening it from the models, adding skins (dropped files, a player's name), wearing one, errors, renaming and
 * deleting, and screenshots of every state at both laptop sizes.
 */
final class SkinsTest {
	private static final int[][] SIZES = {{1710, 1070, 4}, {1280, 800, 3}};

	private SkinsTest() {
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("[JavaHost test] FAILED: " + what);
		}
		JavaHost.LOGGER.info("[JavaHost test] ok: {}", what);
	}

	// ---------------------------------------------------------------- original test skins

	/** An original little character, different for each seed: hair, face, shirt, trousers, shoes, sometimes a cap. */
	static int[] character(int seed) {
		java.util.Random random = new java.util.Random(seed * 7919L);
		int[] tones = {0xFFF1C6A1, 0xFFD9A47E, 0xFFAE7651, 0xFF7B4B2E, 0xFFE8B98F};
		int skin = tones[Math.floorMod(seed, tones.length)];
		int hair = hsv(random.nextFloat(), 0.55f, 0.25f + random.nextFloat() * 0.4f);
		int shirt = hsv((seed * 0.137f) % 1f, 0.55f + random.nextFloat() * 0.3f, 0.65f + random.nextFloat() * 0.3f);
		int trousers = hsv(random.nextFloat(), 0.35f, 0.25f + random.nextFloat() * 0.35f);
		int shoes = 0xFF2B2622;
		int[] p = new int[64 * 64];
		// Head (all faces), then hair on top and a fringe.
		fill(p, 0, 8, 32, 8, skin);
		fill(p, 8, 0, 16, 8, skin);
		fill(p, 8, 0, 8, 8, hair);
		fill(p, 0, 8, 32, 2 + seed % 2, hair);
		fill(p, 24, 8, 8, 8, hair); // back of the head
		// Face: eyes and a mouth.
		p[12 * 64 + 9] = 0xFFFFFFFF;
		p[12 * 64 + 10] = 0xFF2E4B8F;
		p[12 * 64 + 13] = 0xFF2E4B8F;
		p[12 * 64 + 14] = 0xFFFFFFFF;
		fill(p, 11, 14, 2, 1, darker(skin));
		// Body and arms: a shirt with sleeves, hands of skin.
		fill(p, 16, 16, 24, 16, shirt);
		fill(p, 40, 16, 16, 16, shirt);
		fill(p, 40, 28, 16, 4, skin);
		fill(p, 32, 48, 16, 16, shirt);
		fill(p, 32, 60, 16, 4, skin);
		fill(p, 20, 20, 8, 1, darker(shirt)); // a collar
		// Legs: trousers and shoes.
		fill(p, 0, 16, 16, 16, trousers);
		fill(p, 0, 29, 16, 3, shoes);
		fill(p, 16, 48, 16, 16, trousers);
		fill(p, 16, 61, 16, 3, shoes);
		if (seed % 3 == 0) {
			// A cap on the outer layer.
			int cap = hsv((seed * 0.29f) % 1f, 0.7f, 0.85f);
			fill(p, 40, 0, 16, 8, cap);
			fill(p, 32, 8, 32, 2, cap);
		}
		return p;
	}

	private static void fill(int[] p, int x, int y, int w, int h, int color) {
		for (int row = y; row < y + h; row++) {
			for (int col = x; col < x + w; col++) {
				p[row * 64 + col] = color;
			}
		}
	}

	private static int darker(int color) {
		return 0xFF000000 | (((color >> 16) & 0xFF) * 3 / 4) << 16 | (((color >> 8) & 0xFF) * 3 / 4) << 8 | ((color & 0xFF) * 3 / 4);
	}

	private static int hsv(float h, float s, float v) {
		return 0xFF000000 | (java.awt.Color.HSBtoRGB(h, s, v) & 0xFFFFFF);
	}

	// ---------------------------------------------------------------- the pretend profile service

	/** Minecraft's profile service, played by a tiny server: it remembers the skin worn and what was uploaded. */
	static final class FakeApi {
		final HttpServer server;
		final String base;
		/** Daemon threads, so a test that stops early can't keep the game from closing. */
		private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool(task -> {
			Thread thread = new Thread(task, "Skins test server");
			thread.setDaemon(true);
			return thread;
		});
		final Map<String, byte[]> textures = new ConcurrentHashMap<>();
		final List<String> authorizations = new CopyOnWriteArrayList<>();
		volatile String currentSkin; // texture name, or null for the default skin
		volatile boolean currentSlim;
		volatile String activeCape = "cape-migrator";
		volatile int uploadStatus = 200;
		volatile long uploadDelayMs;
		volatile byte[] lastUpload;
		volatile String lastVariant;
		volatile String lastContentType;
		volatile int uploads;

		FakeApi() throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			base = "http://127.0.0.1:" + server.getAddress().getPort();
			server.createContext("/", this::handle);
			server.setExecutor(pool);
			server.start();
		}

		void stop() {
			server.stop(0);
			pool.shutdownNow();
		}

		private void handle(HttpExchange exchange) throws IOException {
			try (exchange) {
				String path = exchange.getRequestURI().getPath();
				String method = exchange.getRequestMethod();
				String auth = exchange.getRequestHeaders().getFirst("Authorization");
				if (path.startsWith("/minecraft/profile") && !path.startsWith("/minecraft/profile/lookup")) {
					authorizations.add(String.valueOf(auth));
				}
				if (path.equals("/minecraft/profile") && method.equals("GET")) {
					json(exchange, 200, profile());
				} else if (path.equals("/minecraft/profile/skins") && method.equals("POST")) {
					upload(exchange);
				} else if (path.equals("/minecraft/profile/skins/active") && method.equals("DELETE")) {
					currentSkin = null;
					json(exchange, 200, profile());
				} else if (path.equals("/minecraft/profile/capes/active")) {
					if (method.equals("DELETE")) {
						activeCape = null;
					} else {
						String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
						activeCape = body.contains("cape-pan") ? "cape-pan" : "cape-migrator";
					}
					json(exchange, 200, profile());
				} else if (path.startsWith("/minecraft/profile/lookup/name/")) {
					String name = path.substring(path.lastIndexOf('/') + 1);
					if (name.equalsIgnoreCase("AlexPlays")) {
						json(exchange, 200, "{\"id\": \"" + uuid().toString().replace("-", "") + "\", \"name\": \"AlexPlays\"}");
					} else {
						json(exchange, 404, "{\"path\": \"x\", \"errorMessage\": \"not found\"}");
					}
				} else if (path.startsWith("/session/minecraft/profile/")) {
					String value = Base64.getEncoder().encodeToString(("{\"timestamp\": 1, \"profileName\": \"AlexPlays\", \"textures\": {\"SKIN\": {\"url\": \""
							+ base + "/texture/alexplays\", \"metadata\": {\"model\": \"slim\"}}}}").getBytes(StandardCharsets.UTF_8));
					json(exchange, 200, "{\"id\": \"x\", \"name\": \"AlexPlays\", \"properties\": [{\"name\": \"textures\", \"value\": \"" + value + "\"}]}");
				} else if (path.startsWith("/texture/") && textures.containsKey(path.substring(9))) {
					byte[] png = textures.get(path.substring(9));
					exchange.getResponseHeaders().add("Content-Type", "image/png");
					exchange.sendResponseHeaders(200, png.length);
					exchange.getResponseBody().write(png);
				} else {
					json(exchange, 404, "{}");
				}
			}
		}

		static UUID uuid() {
			return UUID.nameUUIDFromBytes("skins-test:AlexPlays".getBytes(StandardCharsets.UTF_8));
		}

		private void upload(HttpExchange exchange) throws IOException {
			byte[] body = exchange.getRequestBody().readAllBytes();
			lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
			uploads++;
			if (uploadDelayMs > 0) {
				try {
					Thread.sleep(uploadDelayMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (uploadStatus != 200) {
				json(exchange, uploadStatus, "{\"errorMessage\": \"nope\"}");
				return;
			}
			// Take the multipart body apart: the variant and the file.
			String boundary = lastContentType.substring(lastContentType.indexOf("boundary=") + 9);
			String text = new String(body, StandardCharsets.ISO_8859_1);
			int variantAt = text.indexOf("name=\"variant\"\r\n\r\n") + "name=\"variant\"\r\n\r\n".length();
			lastVariant = text.substring(variantAt, text.indexOf("\r\n", variantAt));
			int fileAt = text.indexOf("Content-Type: image/png\r\n\r\n") + "Content-Type: image/png\r\n\r\n".length();
			int fileEnd = text.indexOf("\r\n--" + boundary + "--", fileAt);
			lastUpload = java.util.Arrays.copyOfRange(body, fileAt, fileEnd);
			String name = "up" + uploads;
			textures.put(name, lastUpload);
			currentSkin = name;
			currentSlim = "slim".equals(lastVariant);
			json(exchange, 200, profile());
		}

		private String profile() {
			String skins = currentSkin == null ? "[]" : "[{\"id\": \"s\", \"state\": \"ACTIVE\", \"url\": \"" + base + "/texture/" + currentSkin
					+ "\", \"variant\": \"" + (currentSlim ? "SLIM" : "CLASSIC") + "\"}]";
			String capes = "[{\"id\": \"cape-migrator\", \"state\": \"" + ("cape-migrator".equals(activeCape) ? "ACTIVE" : "INACTIVE")
					+ "\", \"url\": \"\", \"alias\": \"MIGRATOR\"}, {\"id\": \"cape-pan\", \"state\": \"" + ("cape-pan".equals(activeCape) ? "ACTIVE" : "INACTIVE")
					+ "\", \"url\": \"\", \"alias\": \"PAN\"}]";
			return "{\"id\": \"0\", \"name\": \"Player\", \"skins\": " + skins + ", \"capes\": " + capes + "}";
		}

		private static void json(HttpExchange exchange, int status, String body) throws IOException {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		}
	}

	// ---------------------------------------------------------------- helpers

	private static void size(ClientGameTestContext context, int w, int h, int scale) {
		context.getInput().resizeWindow(w, h);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			TestCompat.resizeGui(mc);
		});
	}

	private static boolean widgetsFit(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof AbstractWidget w && w.visible)
				.map(c -> (AbstractWidget) c)
				.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8 && w.getBottom() <= mc.getWindow().getGuiScaledHeight()));
	}

	private static boolean hasButton(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof AbstractWidget w
				&& w.visible && w.getMessage().getString().equals(label)));
	}

	/** Screen pixels of a visible widget's centre. */
	private static double[] centre(ClientGameTestContext context, java.util.function.Predicate<AbstractWidget> which, String what) {
		return context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof AbstractWidget w && w.visible && which.test(w)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("no " + what + " on " + mc.gui.screen());
		});
	}

	private static void click(ClientGameTestContext context, String label) {
		double[] at = centre(context, w -> w.active && w.getMessage().getString().equals(label), "button \"" + label + "\"");
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(4);
	}

	private static Tab tab(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen() instanceof JavaHostScreen s ? s.tab() : null);
	}

	/** The Skins page on screen (call on the client thread). */
	private static SkinsPage page(net.minecraft.client.Minecraft mc) {
		return (SkinsPage) ((JavaHostScreen) mc.gui.screen()).page();
	}

	private static void openSkins(ClientGameTestContext context) {
		context.setScreen(() -> new JavaHostScreen(null, Tab.SKINS));
		context.waitFor(mc -> SkinLibrary.loaded(), 200);
		context.waitTicks(10);
	}

	private static void waitIdle(ClientGameTestContext context) {
		context.waitFor(mc -> SkinService.changing() == null && !SkinService.profileLoading(), 400);
		context.waitTicks(4);
	}

	private static String message() {
		return SkinService.message();
	}

	private static SkinFolder.Entry named(String name) {
		return SkinLibrary.entries().stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
	}

	// ---------------------------------------------------------------- the test

	static void run(ClientGameTestContext context, boolean screenshots) {
		int previousScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		Path dir = context.computeOnClient(mc -> SkinLibrary.dir());
		Path drops = dir.resolveSibling("skins-test-files");
		FakeApi api;
		try {
			// A fresh collection, and a few original skins to drop onto the window.
			if (Files.isDirectory(dir)) {
				try (var files = Files.list(dir)) {
					for (Path file : files.toList()) {
						Files.delete(file);
					}
				}
			}
			Files.createDirectories(drops);
			api = new FakeApi();
			api.textures.put("alexplays", SkinImage.png(character(11)));
			api.textures.put("current", SkinImage.png(character(21)));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		System.clearProperty("javahost.skinApi");
		try {
			// 1. Signed out (the test's offline account): the empty collection, and no way to wear.
			for (int[] s : SIZES) {
				size(context, s[0], s[1], s[2]);
				openSkins(context);
				check(SkinLibrary.entries().isEmpty(), "a new collection is empty");
				check(!context.computeOnClient(mc -> SkinService.canWear()), "an offline account can't wear skins");
				check(hasButton(context, "From a player's name") && hasButton(context, "Open skins folder"), "the empty collection shows ways to add a skin");
				check(widgetsFit(context), "the empty Skins page fits at " + s[0] + "x" + s[1]);
				if (screenshots) {
					context.takeScreenshot("skins-" + s[0] + "-empty");
				}
			}

			// From here on Minecraft's profile service is the pretend one.
			System.setProperty("javahost.skinApi", api.base);
			check(context.computeOnClient(mc -> SkinService.canWear()), "with the test server, skins can be worn");

			// 2. Opening it: a click on yourself on Home opens Skins; a drag only turns the model.
			size(context, 1710, 1070, 4);
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
			context.waitTicks(8);
			double[] model = centre(context, w -> w instanceof IdleSkin.Clickable, "clickable model on Home");
			context.getInput().setCursorPos(model[0], model[1]);
			context.waitTicks(30);
			if (screenshots) {
				context.takeScreenshot("skins-1710-home-hint");
			}
			context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(1);
			for (int i = 0; i < 8; i++) {
				context.getInput().moveCursor(12, 0);
				context.waitTicks(1);
			}
			context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(4);
			check(tab(context) == Tab.HOME, "dragging your model on Home turns it and doesn't open Skins");
			context.getInput().setCursorPos(model[0], model[1]);
			context.waitTicks(2);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(4);
			check(tab(context) == Tab.SKINS, "a click on your model on Home opens Skins");
			check("More".equals(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).highlightedSidebarEntry())),
					"More is highlighted on Skins");
			context.getInput().setCursorPos(0, 0);

			waitIdle(context);
			// 3. Signed in, the first time: the skin worn now is saved as "My skin".
			api.currentSkin = "current";
			openSkins(context);
			context.waitFor(mc -> named("My skin") != null && SkinLibrary.worn() != null, 400);
			check(named("My skin").id().equals(SkinLibrary.worn()), "the skin you wear now is saved as \"My skin\" and marked as worn");
			check(api.authorizations.stream().allMatch(("Bearer " + com.javahost.util.SkinApi.DEV_TOKEN)::equals),
					"the test server only ever gets the dummy token");

			// 4. A wrong-size PNG is refused with a message.
			Path small = drops.resolve("tiny.png");
			Files.write(small, SkinImage.png(new int[32 * 32], 32, 32));
			context.runOnClient(mc -> mc.gui.screen().onFilesDrop(List.of(small)));
			context.waitFor(mc -> message() != null && message().contains("64×64"), 200);
			check(SkinLibrary.entries().size() == 1, "a wrong-size PNG is refused: " + message());
			context.waitTicks(4);
			if (screenshots) {
				context.takeScreenshot("skins-1710-error-size");
			}

			// 5. Dropping skins onto the window adds them.
			String[] names = {"Red Knight", "Moss Ranger", "Sky Pilot", "Lava Cook", "Night Owl", "Sand Scout", "Frost Mage"};
			List<Path> files = new ArrayList<>();
			for (int i = 0; i < names.length; i++) {
				Path file = drops.resolve(names[i].replace(' ', '_') + ".png");
				Files.write(file, SkinImage.png(character(i + 1)));
				files.add(file);
			}
			for (Path file : files) {
				context.runOnClient(mc -> mc.gui.screen().onFilesDrop(List.of(file)));
				context.waitTicks(2);
			}
			context.waitFor(mc -> SkinLibrary.entries().size() == 8, 400);
			check(named("Red Knight") != null && named("Frost Mage") != null, "dropped PNGs are added, named after their files");
			Files.write(drops.resolve("again.png"), SkinImage.png(character(1)));
			context.runOnClient(mc -> mc.gui.screen().onFilesDrop(List.of(drops.resolve("again.png"))));
			context.waitFor(mc -> message() != null && message().contains("already"), 200);
			check(SkinLibrary.entries().size() == 8, "the same skin dropped twice is kept once");

			// 6. Wear one: a well-formed upload, "Wearing", and the menu's model in it at once.
			SkinFolder.Entry knight = named("Red Knight");
			context.runOnClient(mc -> SkinLibrary.setModel(knight.id(), SkinFolder.SLIM));
			context.waitFor(mc -> named("Red Knight").slim(), 100);
			SkinsPage.select(knight.id());
			openSkins(context);
			SkinService.skipCooldown();
			api.uploadDelayMs = 1500;
			click(context, "Wear");
			check(hasButton(context, "Wearing…"), "the button says Wearing… while it goes");
			if (screenshots) {
				context.takeScreenshot("skins-1710-wearing");
			}
			waitIdle(context);
			api.uploadDelayMs = 0;
			byte[] saved = Files.readAllBytes(dir.resolve(knight.id() + ".png"));
			check("slim".equals(api.lastVariant) && java.util.Arrays.equals(saved, api.lastUpload)
					&& api.lastContentType.startsWith("multipart/form-data; boundary="), "Wear uploads the skin file with its arms (variant " + api.lastVariant + ")");
			check(knight.id().equals(SkinLibrary.worn()) && hasButton(context, "Wearing"), "the page shows Wearing for it");
			context.waitFor(mc -> knight.id().equals(SkinLibrary.worn()), 100);
			boolean menuModel = context.computeOnClient(mc -> {
				var texture = SkinLibrary.full(named("Red Knight"));
				return texture != null && texture.equals(ClientCompat.skinTexture(Faces.skinOf(Identity.id())));
			});
			check(menuModel, "the menu's model wears the new skin at once");
			if (screenshots) {
				context.takeScreenshot("skins-1710-worn");
			}

			// 7. Errors in plain words: too many changes, and an expired session.
			SkinFolder.Entry ranger = named("Moss Ranger");
			SkinsPage.select(ranger.id());
			openSkins(context);
			api.uploadStatus = 429;
			SkinService.skipCooldown();
			click(context, "Wear");
			waitIdle(context);
			check("Minecraft allows only a few skin changes a minute. Try again shortly.".equals(message()), "a 429 says to wait: " + message());
			if (screenshots) {
				context.takeScreenshot("skins-1710-error-429");
			}
			api.uploadStatus = 401;
			SkinService.skipCooldown();
			click(context, "Wear");
			waitIdle(context);
			check("Your Minecraft session has expired. Restart the game.".equals(message()), "a 401 says to restart: " + message());
			check(knight.id().equals(SkinLibrary.worn()), "a failed change keeps the worn skin");
			api.uploadStatus = 200;
			SkinService.skipCooldown();

			// 8. Rename and delete through a tile's menu.
			SkinFolder.Entry scout = named("Sand Scout");
			openSkins(context);
			context.runOnClient(mc -> page(mc).renameForTests(scout.id()));
			context.waitTicks(4);
			context.runOnClient(mc -> page(mc).renameField().setValue("Dune Scout"));
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.waitFor(mc -> named("Dune Scout") != null, 100);
			check(named("Sand Scout") == null, "rename through the tile's menu");
			SkinFolder.Entry cook = named("Lava Cook");
			openSkins(context);
			context.runOnClient(mc -> page(mc).openTileMenuForTests(cook.id()));
			context.waitTicks(2);
			// Wear, Rename, Delete.
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(30, 3 + 2 * 18 + 9));
			context.waitFor(mc -> named("Lava Cook") == null, 100);
			check(!Files.exists(dir.resolve(cook.id() + ".png")), "delete removes the skin and its file");
			// The worn one asks first.
			openSkins(context);
			context.runOnClient(mc -> page(mc).openTileMenuForTests(knight.id()));
			context.waitTicks(2);
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(30, 3 + 1 * 18 + 9)); // Rename, Delete…
			context.waitTicks(3);
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()) && named("Red Knight") != null,
					"deleting the skin you wear asks first");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());

			// 9. Copy a player's skin by name.
			context.runOnClient(mc -> SkinsPage.showByName("Nobody_Here"));
			openSkins(context);
			context.runOnClient(mc -> page(mc).lookUpForTests());
			context.waitFor(mc -> !SkinsPage.fetching() && SkinsPage.fetchError() != null, 200);
			check(SkinsPage.fetchError().contains("no Minecraft player"), "an unknown name says so: " + SkinsPage.fetchError());
			context.runOnClient(mc -> SkinsPage.showByName("AlexPlays"));
			openSkins(context);
			context.runOnClient(mc -> page(mc).lookUpForTests());
			context.waitFor(mc -> "AlexPlays".equals(SkinsPage.fetchedName()), 200);
			context.waitTicks(6);
			check(hasButton(context, "Save to my skins"), "a found skin is shown, ready to save");

			// Screenshots of every state at both sizes.
			for (int[] s : SIZES) {
				size(context, s[0], s[1], s[2]);
				String at = s[0] + "x" + s[1];
				String name = "skins-" + s[0] + "-";
				context.runOnClient(mc -> SkinsPage.showByName("AlexPlays"));
				openSkins(context);
				context.runOnClient(mc -> page(mc).lookUpForTests());
				context.waitFor(mc -> "AlexPlays".equals(SkinsPage.fetchedName()), 200);
				context.waitTicks(8);
				check(widgetsFit(context), "add by name fits at " + at);
				if (screenshots) {
					context.takeScreenshot(name + "by-name");
				}
				context.runOnClient(mc -> SkinsPage.showGrid());
				SkinsPage.select(named("Night Owl").id());
				openSkins(context);
				context.waitTicks(10);
				check(widgetsFit(context), "the Skins grid fits at " + at);
				check(hasButton(context, "Cape: Migrator"), "the cape you show is named as a cape");
				int[] grid = context.computeOnClient(mc -> page(mc).gridForTests());
				check(grid[0] == 4 && grid[1] >= 3, "the grid has 4 columns and at least 3 rows at " + at + " (" + grid[0] + "x" + grid[1] + ")");
				if (screenshots) {
					context.takeScreenshot(name + "grid");
				}
				context.runOnClient(mc -> page(mc).openAddMenuForTests());
				context.waitTicks(3);
				check(context.computeOnClient(mc -> {
					int[] bounds = ((JavaHostScreen) mc.gui.screen()).popoverBounds();
					int contentLeft = ((JavaHostScreen) mc.gui.screen()).children().stream().filter(c -> c instanceof IdleSkin.Clickable || c instanceof net.minecraft.client.gui.components.PlayerSkinWidget)
							.map(c -> ((AbstractWidget) c).getRight()).findFirst().orElse(0);
					return bounds != null && bounds[0] >= contentLeft && bounds[0] + bounds[2] <= mc.getWindow().getGuiScaledWidth();
				}), "the Add menu opens over the grid, clear of the model, at " + at);
				if (screenshots) {
					context.takeScreenshot(name + "add-menu");
				}
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
				context.runOnClient(mc -> page(mc).openTileMenuForTests(named("Night Owl").id()));
				context.waitTicks(3);
				SkinFolder.Entry rightmost = SkinLibrary.entries().get(2); // the 4th slot, after Add
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
				context.runOnClient(mc -> page(mc).openTileMenuForTests(rightmost.id()));
				context.waitTicks(2);
				check(context.computeOnClient(mc -> {
					int[] bounds = ((JavaHostScreen) mc.gui.screen()).popoverBounds();
					return bounds != null && bounds[0] + bounds[2] <= mc.getWindow().getGuiScaledWidth() - 4;
				}), "a right-hand tile's menu stays on screen at " + at);
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
				context.runOnClient(mc -> page(mc).openTileMenuForTests(named("Night Owl").id()));
				context.waitTicks(3);
				if (screenshots) {
					context.takeScreenshot(name + "more-menu");
				}
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
				click(context, "Cape: Migrator");
				context.waitTicks(3);
				if (screenshots) {
					context.takeScreenshot(name + "capes");
				}
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
				// Wearing, then worn.
				SkinFolder.Entry owl = named("Night Owl");
				SkinService.skipCooldown();
				api.uploadDelayMs = 1500;
				click(context, "Wear");
				context.getInput().setCursorPos(0, 0);
				context.waitTicks(2);
				if (screenshots) {
					context.takeScreenshot(name + "wearing");
				}
				waitIdle(context);
				api.uploadDelayMs = 0;
				check(owl.id().equals(SkinLibrary.worn()), "wearing works at " + at);
				if (screenshots) {
					context.takeScreenshot(name + "worn");
				}
				// An error.
				SkinsPage.select(named("Sky Pilot").id());
				openSkins(context);
				api.uploadStatus = 429;
				SkinService.skipCooldown();
				click(context, "Wear");
				waitIdle(context);
				context.getInput().setCursorPos(0, 0);
				if (screenshots) {
					context.takeScreenshot(name + "error");
				}
				api.uploadStatus = 200;
				SkinService.skipCooldown();
				// Wear the knight again for the next size.
				SkinsPage.select(knight.id());
				openSkins(context);
				click(context, "Wear");
				waitIdle(context);
				SkinService.skipCooldown();
			}

			// The cape menu switches the cape.
			SkinService.skipCooldown();
			context.runOnClient(mc -> SkinService.setCape("cape-pan"));
			waitIdle(context);
			check("cape-pan".equals(api.activeCape) && SkinService.profile().activeCape().alias().equals("Pan"), "a cape can be switched");

			// The Account page links here.
			context.setScreen(() -> new JavaHostScreen(null, Tab.ACCOUNT));
			context.waitTicks(4);
			click(context, "Change skin");
			check(tab(context) == Tab.SKINS, "Account → Change skin opens Skins");

			// 10. The title screen's model opens it too.
			size(context, 1710, 1070, 4);
			String mode = com.javahost.client.Config.get().titleScreen;
			com.javahost.client.Config.get().titleScreen = "FULL";
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			context.waitTicks(10);
			double[] title = centre(context, w -> w instanceof IdleSkin.Clickable, "clickable model on the title screen");
			context.getInput().setCursorPos(title[0], title[1]);
			context.waitTicks(30);
			if (screenshots) {
				context.takeScreenshot("skins-1710-title-hint");
			}
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(4);
			check(tab(context) == Tab.SKINS, "a click on the title screen's model opens Skins");
			com.javahost.client.Config.get().titleScreen = mode;
			context.getInput().setCursorPos(0, 0);
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			System.clearProperty("javahost.skinApi");
			context.runOnClient(mc -> SkinsPage.showGrid());
			api.stop();
			context.setScreen(() -> null);
			context.getInput().resizeWindow(854, 480);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(previousScale);
				TestCompat.resizeGui(mc);
			});
			context.waitTicks(4);
		}
	}
}
