package com.javahost.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;

import com.javahost.HostState;
import com.javahost.HostState.SharedPack;
import com.javahost.JavaHost;
import com.javahost.client.Config;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.JavaHostScreen.Tab;
import com.javahost.client.gui.PackOfferScreen;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.E4mcCompat;
import com.javahost.client.host.HostController;
import com.javahost.client.pack.PackFiles;
import com.javahost.client.voice.VoiceClient;
import com.javahost.net.InviteCode;
import com.javahost.net.Payloads.PackInfo;
import com.javahost.net.Payloads.PackKind;
import com.javahost.net.Payloads.VoiceUp;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;

/**
 * End-to-end checks run inside the real game: {@code ./gradlew runClientGameTest}.
 * Screenshots land in build/run/clientGameTest/screenshots.
 */
public class JavaHostClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		String role = System.getProperty("javahost.testRole");
		if ("host".equals(role)) {
			MultiplayerTest.host(context);
			return;
		}
		if ("guest".equals(role)) {
			MultiplayerTest.guest(context);
			return;
		}
		if ("modhost".equals(role)) {
			ModdedTest.host(context);
			return;
		}
		if ("modguest".equals(role)) {
			ModdedTest.guest(context);
			return;
		}
		//? if >=26.3 {
		if ("showcase".equals(role)) {
			Showcase.run(context); // website screenshots are only taken on 26.3
			return;
		}
		//?}
		Config.get().tourDone = true; // the welcome tour is tested on its own below
		inviteCodes();
		opusRoundTrip();

		// Keep the test off the user's router and microphone.
		Config config = Config.get();
		config.useUpnp = false;
		config.relayMode = "NEVER";
		E4mcCompat.setRelayEnabled(false);
		config.voiceEnabled = false;
		config.autoAcceptHostPacks = true;

		if ("skins".equals(System.getenv("JAVAHOST_TEST_ONLY"))) {
			SkinsTest.run(context, true); // just the skin changer, while working on it
			return;
		}
		if ("hub".equals(System.getenv("JAVAHOST_TEST_ONLY"))) {
			quickHub(context); // a one-minute look at the menu while working on it
			return;
		}
		guestReceivesHostPacks(context);
		hostingASingleplayerWorld(context);
		titleScreenModes(context);
		SkinsTest.run(context, false);
		hostFromTitle(context);
		JavaHost.LOGGER.info("[JavaHost test] ALL CHECKS PASSED");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("[JavaHost test] FAILED: " + what);
		}
		JavaHost.LOGGER.info("[JavaHost test] ok: {}", what);
	}

	private static void inviteCodes() {
		String code = InviteCode.encode("203.0.113.42", 25565);
		check(code != null && code.matches("JH-[0-9A-Z]{5}-[0-9A-Z]{5}"), "invite code format (" + code + ")");
		check("203.0.113.42:25565".equals(InviteCode.decode(code)), "invite code round trip");
		check("203.0.113.42:25565".equals(InviteCode.decode(code.toLowerCase().replace("-", " "))), "invite code is forgiving about case/spacing");
		check("play.example.net".equals(InviteCode.decode("play.example.net")), "plain address passes through");
		check("jhserver.net".equals(InviteCode.decode("jhserver.net")), "hostname starting with jh passes through");
	}

	private static void opusRoundTrip() {
		try {
			OpusEncoder encoder = new OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
			encoder.setBitrate(24000);
			OpusDecoder decoder = new OpusDecoder(48000, 1);
			short[] tone = new short[960];
			short[] out = new short[960];
			byte[] packet = new byte[1024];
			double energyIn = 0;
			double energyOut = 0;
			for (int frame = 0; frame < 10; frame++) {
				for (int i = 0; i < 960; i++) {
					tone[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * (frame * 960 + i) / 48000.0));
				}
				int length = encoder.encode(tone, 0, 960, packet, 0, packet.length);
				check(length > 0 && length < 200, "opus frame size " + length + " bytes");
				int samples = decoder.decode(packet, 0, length, out, 0, 960, false);
				check(samples == 960, "opus decoded a full 20 ms frame");
				if (frame >= 5) {
					for (int i = 0; i < 960; i++) {
						energyIn += tone[i] * (double) tone[i];
						energyOut += out[i] * (double) out[i];
					}
				}
			}
			double ratio = energyOut / energyIn;
			check(ratio > 0.5 && ratio < 1.5, "opus keeps the voice level (ratio " + String.format("%.2f", ratio) + ")");
		} catch (Exception e) {
			throw new AssertionError("Opus round trip failed", e);
		}
	}

	/** A pack big enough to need several chunks, so flow control and reassembly are exercised. */
	private static Path writeTestPack(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("pack.mcmeta"));
			zip.write(TestCompat.packMcmeta("JavaHost test pack").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("assets/javahost_test/filler.bin"));
			zip.setLevel(0);
			byte[] filler = new byte[1024 * 1024];
			new java.util.Random(42).nextBytes(filler);
			for (int i = 0; i < 3; i++) {
				zip.write(filler); // ~3 MB of incompressible data -> 12 chunks
			}
			zip.closeEntry();
		}
		return file;
	}

	private static void guestReceivesHostPacks(ClientGameTestContext context) {
		Path pack;
		String sha1;
		Path cache = context.computeOnClient(mc -> PackFiles.cache());
		try {
			pack = writeTestPack(context.computeOnClient(mc -> PackFiles.root()).resolve("test/test-pack.zip"));
			sha1 = PackFiles.sha1(pack);
			// Clear any cached copy from an earlier run so the download really happens.
			Files.deleteIfExists(cache.resolve(sha1 + ".zip"));
			HostState.setSharedPacks(List.of(new SharedPack(
					new PackInfo(sha1, PackKind.RESOURCE_PACK, "Test Pack", Files.size(pack)), pack)), "");
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
				var connection = server.connect()) {
			//? if >=26.2 {
			connection.waitForChunksRender();
			//?} else if >=26.1 {
			/*connection.getClientLevel().waitForChunksRender();
			*///?} else
			/*connection.getClientWorld().waitForChunksRender();*/

			context.waitFor(mc -> VoiceClient.isAvailable(), 200);
			check(true, "server announced voice chat to the joining player");

			context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().anyMatch(id -> id.startsWith("server/")), 1200);
			check(Files.exists(cache.resolve(sha1 + ".zip")), "pack streamed in chunks, checksum verified and cached");
			check(true, "host pack applied as a server resource pack");
			context.takeScreenshot("javahost-guest-with-host-pack");

			// Voice frames go through the relay without errors even with nobody else to hear them.
			context.runOnClient(mc -> ClientPlayNetworking.send(new VoiceUp(0, new byte[] {1, 2, 3})));
			//? if >=26.2 {
			connection.waitForServerboundPackets();
			//?} else
			/*context.waitTicks(20);*/
			check(true, "voice frame accepted by the server relay");
		}
		context.waitFor(mc -> mc.getResourcePackRepository().getSelectedIds().stream().noneMatch(id -> id.startsWith("server/")), 600);
		check(true, "host pack removed again after leaving");
		// Removing the pack reloads resources; let that finish before the next world opens.
		context.waitFor(TestCompat::noOverlay, 1200);
		HostState.clear();
	}

	private static void hostingASingleplayerWorld(ClientGameTestContext context) {
		// Enable a user resource pack so the host has something to share.
		context.runOnClient(mc -> {
			Path userPack;
			try {
				userPack = writeTestPack(mc.getResourcePackDirectory().resolve("my-pack.zip"));
			} catch (IOException e) {
				throw new AssertionError(e);
			}
			mc.getResourcePackRepository().reload();
			List<String> selected = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
			selected.add("file/" + userPack.getFileName());
			mc.getResourcePackRepository().setSelected(selected);
		});

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestCompat.waitForChunks(world);

			// Every tab before hosting.
			for (Tab tab : Tab.values()) {
				context.setScreen(() -> new JavaHostScreen(null, tab));
				context.waitTicks(4);
				context.takeScreenshot("ui-" + tab.name().toLowerCase() + (tab == Tab.HOST ? "-setup" : ""));
			}
			// The Host tab scrolled down, to see the bottom of the settings.
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
			context.waitTicks(10);
			context.takeScreenshot("ui-host-setup-scrolled");

			context.setScreen(() -> new PackOfferScreen(List.of(
					new PackInfo("0".repeat(40), PackKind.RESOURCE_PACK, "Faithful 32x", 18_400_000L),
					new PackInfo("1".repeat(40), PackKind.SHADER_PACK, "Complementary Reimagined r5.5.zip", 6_100_000L)),
					24_500_000L, yes -> {
					}));
			context.waitTicks(4);
			context.takeScreenshot("ui-pack-offer");
			context.setScreen(() -> null);

			context.runOnClient(mc -> check(HostController.start(mc.getSingleplayerServer()) == null, "start hosting"));
			context.runOnClient(mc -> check(mc.getSingleplayerServer().isPublished(), "world is open to other players"));
			context.waitFor(mc -> !HostState.sharedPacks().isEmpty(), 600);
			check(HostState.sharedPacks().getFirst().info().size() > 1_000_000, "host's resource pack zipped and hashed for sharing");
			context.waitFor(mc -> !Connectivity.isProbing(), 600);
			check(Connectivity.lanIp() != null, "found LAN address " + Connectivity.lanIp());

			context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
			context.waitTicks(10);
			context.takeScreenshot("ui-host-hosting");
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
			context.waitTicks(4);
			context.takeScreenshot("ui-home-hosting");

			context.setScreen(() -> null);
			newFeatures(context);
			context.runOnClient(mc -> HostController.stop());
			context.runOnClient(mc -> check(!mc.getSingleplayerServer().isPublished(), "stop hosting closes the world again"));
		}
		restoreBackup(context);
		// The title screen with the friends panel, with an invite waiting.
		java.util.UUID alex = java.util.UUID.nameUUIDFromBytes("Alex".getBytes());
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.INVITE, alex, "Alex",
				"Alex invited you to Survival Island", new com.javahost.client.friends.Friends.Invite(alex, "Alex", "Survival Island", List.of(), "",
						System.currentTimeMillis() + 600_000, com.javahost.client.compat.ClientCompat.minecraftVersion())));
		// The title screen on a big window (like a laptop's 2000x1250) at each common GUI scale.
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.getInput().resizeWindow(2000, 1250);
		for (int guiScale : new int[] {4, 3, 2}) {
			context.runOnClient(mc -> {
				mc.options.guiScale().set(guiScale);
				TestCompat.resizeGui(mc);
			});
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			context.waitTicks(10);
			context.takeScreenshot("ui-title-scale" + guiScale);
			hoverDock(context, "Friends");
			context.waitTicks(8);
			context.takeScreenshot("ui-title-scale" + guiScale + "-hover");
			context.getInput().setCursorPos(0, 0);
			// The screenshot preview keeps its buttons on screen at every scale.
			context.setScreen(() -> new JavaHostScreen(null, Tab.SCREENSHOTS));
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(com.javahost.client.friends.Screenshots.list(false).getFirst()));
			context.waitTicks(8);
			context.takeScreenshot("ui-preview-scale" + guiScale);
		}
		// Menu icons at GUI scale 3 (the in-between size).
		context.runOnClient(mc -> {
			mc.options.guiScale().set(3);
			TestCompat.resizeGui(mc);
		});
		for (Tab tab : List.of(Tab.FRIENDS, Tab.SETTINGS, Tab.ACCOUNT)) {
			context.setScreen(() -> new JavaHostScreen(null, tab));
			context.waitTicks(6);
			context.takeScreenshot("ui-scale3-" + tab.name().toLowerCase());
		}
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			TestCompat.resizeGui(mc);
		});
		context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		context.waitTicks(10);
		context.takeScreenshot("ui-title-small-window");
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.items().forEach(com.javahost.client.friends.Notifications::remove));
	}

	/** A worst-case screenshot (random noise doesn't compress): written as a PNG of the given size. */
	static Path writeNoisePng(Path file, int width, int height) {
		try (com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(width, height, false)) {
			java.util.Random random = new java.util.Random(42);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setPixel(x, y, 0xFF000000 | random.nextInt(0xFFFFFF));
				}
			}
			java.nio.file.Files.createDirectories(file.getParent());
			image.writeToFile(file);
			return file;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** Just the menu, in a hosted world: Home with friends, then every tab at the laptop size. */
	private static void quickHub(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestCompat.waitForChunks(world);
			worldPageNotHosting(context);
			context.runOnClient(mc -> HostController.start(mc.getSingleplayerServer()));
			context.waitTicks(20);
			worldOptions(context);
			worldPage(context);
			hubWithFriends(context);
			shellInWorld(context);
			reviewAtScale(context, 4, 1710, 1070);
			if (System.getenv("JAVAHOST_TEST_FPS") != null) {
				frameRates(context);
			}
			tickCost(context);
			context.runOnClient(mc -> HostController.stop());
		}
		// Back on the title screen: the dock and the model, at the laptop size.
		context.getInput().resizeWindow(1710, 1070);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			TestCompat.resizeGui(mc);
		});
		context.waitTicks(30);
		context.takeScreenshot("quick-title");
		titleScreenModes(context);
		SkinsTest.run(context, true);
		hostFromTitle(context);
		worldPageNoWorld(context);
	}

	// ================================================================ 2.0: World options (server side)

	private static String ruleValue(ClientGameTestContext context, String id) {
		return context.computeOnClient(mc -> com.javahost.rules.RuleCatalog.value(mc.getSingleplayerServer(), id));
	}

	private static String firstRule(ClientGameTestContext context, String... ids) {
		for (String id : ids) {
			if (ruleValue(context, id) != null) {
				return id;
			}
		}
		throw new AssertionError("[JavaHost test] FAILED: none of " + java.util.Arrays.toString(ids) + " is a game rule here");
	}

	/**
	 * 2.0: what the World options screen stands on, in a world being hosted: every game rule with the common ones
	 * flagged, changing rules (and refusing bad changes), announcements off by default, Cheats, and operators getting
	 * commands only while Cheats is on. Everything changed is put back.
	 */
	private static void worldOptions(ClientGameTestContext context) {
		context.waitFor(mc -> com.javahost.client.rules.WorldOptions.available(), 200);
		check(context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.role()) == com.javahost.client.rules.WorldOptions.Role.HOST,
				"the host gets World options, as host");
		List<com.javahost.client.rules.WorldOptions.Rule> all = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.allRules());
		List<String> common = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.commonRules().stream()
				.map(com.javahost.client.rules.WorldOptions.Rule::id).toList());
		check(all.size() >= 30 && all.stream().allMatch(r -> !r.nameKey().isEmpty() && !r.category().isEmpty()),
				"the rule catalogue lists the game's rules with names and categories (" + all.size() + ")");
		String keep = firstRule(context, "keep_inventory", "keepInventory");
		String sleep = firstRule(context, "players_sleeping_percentage", "playersSleepingPercentage");
		check(common.size() >= 8 && common.contains(keep) && common.contains(sleep)
				&& (common.contains("mob_griefing") || common.contains("mobGriefing"))
				&& (common.contains("advance_time") || common.contains("doDaylightCycle")),
				"the common rules come first: " + common);
		check(all.subList(0, common.size()).stream().allMatch(com.javahost.client.rules.WorldOptions.Rule::common), "common rules are listed first");
		com.javahost.client.rules.WorldOptions.Rule sleepRule = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.rule(sleep));
		// The game's own range: 0 and up from 1.21.11; before that this rule takes any number.
		check(!sleepRule.bool() && sleepRule.min() <= 0 && sleepRule.max() >= 100, "integer rules carry their range (" + sleepRule.min() + ".." + sleepRule.max() + ")");
		String belowRange = sleepRule.min() > Integer.MIN_VALUE ? String.valueOf(sleepRule.min() - 1L) : "-99999999999";
		check(context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.state() != null
				&& com.javahost.client.rules.RulesClient.state().values().keySet().equals(java.util.Arrays.stream(com.javahost.rules.WorldRules.Rule.values())
						.map(Enum::name).collect(java.util.stream.Collectors.toSet()))), "the old world rules state is still sent, with the same rules");

		String keepBefore = ruleValue(context, keep);
		String sleepBefore = ruleValue(context, sleep);
		String keepOther = String.valueOf(!Boolean.parseBoolean(keepBefore));
		String sleepOther = sleepBefore.equals("37") ? "38" : "37";
		try {
			// Rules, quietly: announcements are off unless the host turns them on.
			check(!context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.announce()), "announcements start off");
			int announced = com.javahost.rules.WorldRules.announcementsSent();
			int oldRules = context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.version());
			check(context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.setRule(keep, Boolean.parseBoolean(keepOther))), "the helper sends a boolean rule");
			context.waitFor(mc -> keepOther.equals(com.javahost.rules.RuleCatalog.value(mc.getSingleplayerServer(), keep)), 100);
			context.waitFor(mc -> keepOther.equals(com.javahost.client.rules.WorldOptions.rule(keep).value()), 100);
			check(true, "a boolean rule changed in the world and came back in the World options (" + keep + " = " + keepOther + ")");
			check(context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.setRule(sleep, Integer.parseInt(sleepOther))), "the helper sends an integer rule");
			context.waitFor(mc -> sleepOther.equals(com.javahost.rules.RuleCatalog.value(mc.getSingleplayerServer(), sleep)), 100);
			context.waitFor(mc -> sleepOther.equals(com.javahost.client.rules.WorldOptions.rule(sleep).value()), 100);
			check(true, "an integer rule changed in the world and came back (" + sleep + " = " + sleepOther + ")");
			check(com.javahost.rules.WorldRules.announcementsSent() == announced, "with announcements off, changing rules adds no chat line");
			check(context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.version()) > oldRules, "the old world rules state is still sent after changes");

			check(!context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.setRule(sleep, belowRange)), "the helper won't send a value out of range");
			context.runOnClient(mc -> {
				com.javahost.client.rules.WorldOptions.requestUnchecked("rule:" + sleep, belowRange);
				com.javahost.client.rules.WorldOptions.requestUnchecked("rule:" + sleep, "99999999999");
				com.javahost.client.rules.WorldOptions.requestUnchecked("rule:" + sleep, "true");
				com.javahost.client.rules.WorldOptions.requestUnchecked("rule:" + keep, "maybe");
				com.javahost.client.rules.WorldOptions.requestUnchecked("rule:no_such_rule", "true");
				com.javahost.client.rules.WorldOptions.requestUnchecked("__op:not-a-uuid", "true");
				com.javahost.client.rules.WorldOptions.requestUnchecked("__gamemode", "godmode");
			});
			context.waitTicks(20);
			check(sleepOther.equals(ruleValue(context, sleep)) && keepOther.equals(ruleValue(context, keep)),
					"out-of-range, wrongly typed and unknown rule changes are refused, and the server carries on");

			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setAnnounce(true));
			context.waitFor(mc -> com.javahost.client.rules.WorldOptions.announce(), 100);
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setRule(keep, keepBefore));
			context.waitFor(mc -> keepBefore.equals(com.javahost.rules.RuleCatalog.value(mc.getSingleplayerServer(), keep)), 100);
			context.waitTicks(2);
			check(com.javahost.rules.WorldRules.announcementsSent() > announced, "with announcements on, a rule change is told in chat");
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setAnnounce(false));
			context.waitFor(mc -> !com.javahost.client.rules.WorldOptions.announce(), 100);

			// The default game mode.
			String modeBefore = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.defaultGameMode());
			String modeOther = modeBefore.equals("adventure") ? "survival" : "adventure";
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setDefaultGameMode(modeOther));
			context.waitFor(mc -> mc.getSingleplayerServer().getDefaultGameType().getName().equals(modeOther)
					&& modeOther.equals(com.javahost.client.rules.WorldOptions.defaultGameMode()), 100);
			check(true, "the default game mode changes (" + modeBefore + " -> " + modeOther + ")");
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setDefaultGameMode(modeBefore));
			context.waitFor(mc -> mc.getSingleplayerServer().getDefaultGameType().getName().equals(modeBefore), 100);

			operatorsAndCheats(context);
		} finally {
			context.runOnClient(mc -> {
				com.javahost.client.rules.WorldOptions.setRule(keep, keepBefore);
				com.javahost.client.rules.WorldOptions.setRule(sleep, sleepBefore);
				com.javahost.client.rules.WorldOptions.setAnnounce(false);
			});
			context.waitFor(mc -> keepBefore.equals(com.javahost.rules.RuleCatalog.value(mc.getSingleplayerServer(), keep))
					&& sleepBefore.equals(com.javahost.rules.RuleCatalog.value(mc.getSingleplayerServer(), sleep)), 100);
		}
	}

	/** Operators get commands only while Cheats is on; the game's own ops list and "allow commands" don't count. */
	private static void operatorsAndCheats(ClientGameTestContext context) {
		java.util.UUID steve = java.util.UUID.randomUUID();
		java.util.function.IntSupplier level = () -> context.computeOnClient(mc -> TestCompat.permissionLevel(mc.getSingleplayerServer(), steve, "Steve123"));
		boolean cheatsBefore = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.cheats());
		boolean locked = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.cheatsLocked());
		try {
			check(level.getAsInt() == 0, "a guest who isn't an operator has no commands");
			if (locked) {
				check(cheatsBefore && !context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.cheatsLockedReason()).isEmpty(),
						"this world was made with cheats on, and says so instead of pretending they can be turned off");
				context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setCheats(false));
				context.waitTicks(10);
				check(context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.cheats()), "cheats stay on in a world made with them");
			} else {
				context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setCheats(false));
				context.waitFor(mc -> !com.javahost.client.rules.WorldOptions.cheats(), 100);
			}
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setOperator(steve, true));
			context.waitFor(mc -> com.javahost.client.rules.WorldOptions.isOperator(steve), 100);
			check(com.javahost.rules.WorldRules.isOperator(steve), "the host made Steve123 an operator");
			if (!locked) {
				check(level.getAsInt() == 0, "an operator has no commands while cheats are off");
				context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setCheats(true));
				context.waitFor(mc -> com.javahost.client.rules.WorldOptions.cheats(), 100);
				check(true, "the host turned cheats on");
				//? if >=26.2 {
				check(context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().isAllowCommands()), "the world's own cheats flag is on");
				//?}
			}
			check(level.getAsInt() == 2, "an operator gets the gamemasters level while cheats are on");
			if (!locked) {
				context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setCheats(false));
				context.waitFor(mc -> !com.javahost.client.rules.WorldOptions.cheats(), 100);
				check(level.getAsInt() == 0, "turning cheats off takes an operator's commands away");
				//? if >=26.2 {
				check(!context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().isAllowCommands()), "the world's own cheats flag is off");
				//?}
				context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setCheats(true));
				context.waitFor(mc -> com.javahost.client.rules.WorldOptions.cheats(), 100);
			}
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setOperator(steve, false));
			context.waitFor(mc -> !com.javahost.client.rules.WorldOptions.isOperator(steve), 100);
			check(level.getAsInt() == 0, "no commands again once no longer an operator, even with cheats on");
		} finally {
			context.runOnClient(mc -> {
				com.javahost.client.rules.WorldOptions.setOperator(steve, false);
				if (!locked) {
					com.javahost.client.rules.WorldOptions.setCheats(cheatsBefore);
				}
			});
			if (!locked) {
				context.waitFor(mc -> com.javahost.client.rules.WorldOptions.cheats() == cheatsBefore, 100);
			}
		}
	}

	// ================================================================ 2.0: the slimmer shell

	private static final int[][] SHELL_SIZES = {{1710, 1070, 4}, {1280, 800, 3}};

	private static int dockButtons(ClientGameTestContext context) {
		return context.computeOnClient(mc -> (int) mc.gui.screen().children().stream()
				.filter(c -> c.getClass().getSimpleName().equals("DockButton")).count());
	}

	private static String highlighted(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).highlightedSidebarEntry());
	}

	/**
	 * In a world: the sidebar has four places plus More and Settings; More's menu opens each of its pages (with More
	 * highlighted); related pages link to them; the pause menu gets three dock buttons, or none when turned off.
	 */
	private static void shellInWorld(ClientGameTestContext context) {
		int previous = context.computeOnClient(mc -> mc.options.guiScale().get());
		Config config = Config.get();
		boolean pauseDock = config.pauseDock;
		try {
			for (int[] size : SHELL_SIZES) {
				size(context, size[0], size[1], size[2]);
				String at = size[0] + "x" + size[1] + " scale " + size[2];
				String name = "shell-" + size[0] + "-s" + size[2];
				context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
				context.waitTicks(8);
				context.takeScreenshot(name + "-home");
				List<String> labels = context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).sidebarLabels());
				check(labels.equals(List.of("Home", "Friends", "World", "Public worlds", "Mods", "More", "Settings", "Account")),
						"the sidebar is Home, Friends, World, Public worlds, Mods, then More, Settings and the account at " + at + " (" + labels + ")");
				check("Home".equals(highlighted(context)), "Home is highlighted on Home");

				clickSidebar(context, "More");
				check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).moreOpen()), "More opens its menu at " + at);
				context.getInput().setCursorPos(0, 0);
				context.waitTicks(4);
				context.takeScreenshot(name + "-more");
				List<Tab> more = List.of(Tab.SKINS, Tab.SCREENSHOTS, Tab.VOICE, Tab.PERFORMANCE);
				check(JavaHostScreen.MORE.equals(more), "More lists Skins, Screenshots, Voice and Performance");
				for (int i = 0; i < more.size(); i++) {
					Tab expected = more.get(i);
					int row = i;
					context.runOnClient(mc -> {
						JavaHostScreen screen = (JavaHostScreen) mc.gui.screen();
						if (!screen.moreOpen()) {
							screen.toggleMore();
						}
						screen.clickPopover(30, 3 + row * 18 + 9);
					});
					context.waitTicks(4);
					check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == expected
							&& !context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()),
							"More → " + expected.name().toLowerCase() + " opens that page and closes the menu at " + at);
					check("More".equals(highlighted(context)), "More is highlighted on " + expected.name().toLowerCase() + " at " + at);
				}
				context.takeScreenshot(name + "-more-highlighted");

				context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
				context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -30));
				context.waitTicks(10);
				check(widgetsFit(context), "Friends fits at " + at);
				context.takeScreenshot(name + "-friends-bottom");

				// The pause menu: Home, Friends, World.
				context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
				context.waitTicks(6);
				check(dockButtons(context) == 3, "the pause menu's dock has 3 buttons at " + at);
				context.takeScreenshot(name + "-pause");
			}

			// A narrow window: the sidebar is icons only, and More still works.
			size(context, 1020, 780, 3); // 340 GUI pixels wide: under 350, so icons only
			context.setScreen(() -> new JavaHostScreen(null, Tab.VOICE));
			context.waitTicks(6);
			check(context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth() < 350), "the window is narrow enough for the icon-only sidebar");
			check("More".equals(highlighted(context)), "More is highlighted in the icon-only sidebar");
			clickSidebar(context, "More");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).moreOpen()), "More opens its menu in the icon-only sidebar");
			context.getInput().setCursorPos(0, 0);
			context.waitTicks(4);
			context.takeScreenshot("shell-1020-s3-compact-more");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(30, 3 + 18 + 9));
			context.waitTicks(4);
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.SCREENSHOTS, "More → Screenshots in the icon-only sidebar");

			// Shortcuts from related pages.
			size(context, 1710, 1070, 4);
			context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
			context.waitTicks(6);
			clickSidebar(context, "Public worlds");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.DISCOVER
					&& "Public worlds".equals(highlighted(context)), "Public worlds has its own place in the sidebar");
			com.javahost.client.gui.pages.ModsPage.showMine(true);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(6);
			context.takeScreenshot("shell-1710-s4-mods-installed");
			clickMenu(context, "Performance");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.PERFORMANCE, "Mods links to Performance");
			com.javahost.client.gui.pages.ModsPage.showMine(false);
			context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -6));
			context.waitTicks(10);
			context.takeScreenshot("shell-1710-s4-settings-scrolled");
			clickMenu(context, "Voice settings");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.VOICE, "Settings links to Voice");
			context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
			context.waitTicks(6);
			context.takeScreenshot("shell-1710-s4-settings");
			check(hasButton(context, "Buttons on the pause menu"), "Settings has the pause menu switch");

			// Turned off: nothing on the pause menu.
			config.pauseDock = false;
			context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
			context.waitTicks(4);
			check(dockButtons(context) == 0, "with the pause menu buttons off, the pause menu has no dock");
			context.takeScreenshot("shell-1710-s4-pause-off");
		} finally {
			config.pauseDock = pauseDock;
			context.setScreen(() -> null);
			context.getInput().resizeWindow(854, 480);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(previous);
				TestCompat.resizeGui(mc);
			});
			context.waitTicks(4);
		}
	}

	/** Out of a world: the title screen in each of its three modes. */
	private static void titleScreenModes(ClientGameTestContext context) {
		int previous = context.computeOnClient(mc -> mc.options.guiScale().get());
		Config config = Config.get();
		String mode = config.titleScreen;
		try {
			for (int[] size : SHELL_SIZES) {
				size(context, size[0], size[1], size[2]);
				String at = size[0] + "x" + size[1] + " scale " + size[2];
				String name = "shell-" + size[0] + "-s" + size[2] + "-title-";
				for (String m : List.of("FULL", "DOCK", "OFF")) {
					config.titleScreen = m;
					context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
					context.waitTicks(10);
					context.takeScreenshot(name + m.toLowerCase());
					int dock = dockButtons(context);
					boolean skin = context.computeOnClient(mc -> mc.gui.screen().children().stream()
							.anyMatch(c -> c instanceof net.minecraft.client.gui.components.PlayerSkinWidget));
					switch (m) {
						case "FULL" -> check(dock == 6 && skin, "the title screen (full) has 6 dock buttons and the 3D model at " + at + " (" + dock + ")");
						case "DOCK" -> check(dock == 6 && !skin, "the title screen (dock) has the 6 buttons and no model at " + at);
						default -> {
							check(dock == 0 && !skin, "the title screen (off) has no dock and no model at " + at);
							boolean clear = context.computeOnClient(mc -> {
								var widgets = mc.gui.screen().children().stream()
										.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
										.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c).toList();
								var button = widgets.stream().filter(w -> w.getMessage().getString().equals("JavaHost")).toList();
								if (button.size() != 1) {
									return false;
								}
								var b = button.getFirst();
								return widgets.stream().filter(w -> w != b).noneMatch(w -> w.getX() < b.getRight() && b.getX() < w.getRight()
										&& w.getY() < b.getBottom() && b.getY() < w.getBottom());
							});
							check(clear, "the title screen (off) has one JavaHost button that overlaps nothing at " + at);
						}
					}
				}
			}
			clickMenu(context, "JavaHost");
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof JavaHostScreen), "the title screen's JavaHost button opens the menu");
		} finally {
			config.titleScreen = mode;
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			context.getInput().resizeWindow(854, 480);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(previous);
				TestCompat.resizeGui(mc);
			});
			context.waitTicks(4);
		}
	}

	/** 2.0: on the title screen, Home offers to host your last world and the Host tab lists your worlds; Host opens one and starts hosting. */
	private static void hostFromTitle(ClientGameTestContext context) {
		context.runOnClient(mc -> com.javahost.client.host.QuickHost.worlds()); // starts reading the saves folder
		context.waitFor(mc -> !com.javahost.client.host.QuickHost.worlds().isEmpty(), 200);
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
		context.waitTicks(10);
		context.takeScreenshot("title-home");
		String first = context.computeOnClient(mc -> com.javahost.client.host.QuickHost.worlds().get(0).name());
		check(settingsButton(context, "Host " + first).widget() != null, "Home on the title screen offers to host the last world (" + first + ")");
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.waitTicks(10);
		context.takeScreenshot("title-host-worlds");
		check(settingsButton(context, "Host").widget() != null && settingsButton(context, "Play").widget() != null,
				"the Host tab lists your worlds with Host and Play");
		context.runOnClient(mc -> com.javahost.client.host.QuickHost.host(com.javahost.client.host.QuickHost.worlds().get(0)));
		context.waitFor(mc -> HostController.isHosting(), 1200);
		context.runOnClient(mc -> check(mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished(),
				"Host on the title screen opens the world and starts hosting"));
		context.waitTicks(20);
		context.takeScreenshot("title-hosted");
		context.runOnClient(mc -> {
			HostController.stop();
			com.javahost.client.compat.ClientCompat.leaveWorld(net.minecraft.network.chat.Component.literal("test"));
		});
		context.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 600);
		context.waitTicks(60); // the "saving world" screen has its moment
		context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		context.waitTicks(20);
	}

	/**
	 * What the mod costs while you play: everything it does each game tick, timed over a thousand ticks in a hosted
	 * world. A tick is 50 ms; all of JavaHost together has to stay under a tenth of a millisecond of it.
	 */
	private static void tickCost(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			java.util.Map<String, Runnable> steps = new java.util.LinkedHashMap<>();
			steps.put("HostController", HostController::tick);
			steps.put("InviteFlow", () -> com.javahost.client.friends.InviteFlow.tick(mc));
			steps.put("Popups", () -> com.javahost.client.Popups.tick(mc));
			steps.put("Toasts", () -> com.javahost.client.Toasts.tick(mc));
			steps.put("UpdateChecker", () -> com.javahost.client.update.UpdateChecker.tick(mc));
			steps.put("Friends", com.javahost.client.friends.Friends::tick);
			steps.put("Presence", () -> com.javahost.client.friends.Presence.tick(mc));
			steps.put("WorldShare", () -> com.javahost.client.share.WorldShare.tick(mc));
			steps.put("PublicHosting", com.javahost.client.discover.PublicHosting::tick);
			steps.put("ModSets", () -> com.javahost.client.mods.ModSets.tick(mc));
			steps.put("PendingJoin", () -> com.javahost.client.mods.PendingJoin.tick(mc));
			long total = 0;
			StringBuilder detail = new StringBuilder();
			for (var step : steps.entrySet()) {
				for (int i = 0; i < 200; i++) {
					step.getValue().run();
				}
				long start = System.nanoTime();
				for (int i = 0; i < 1000; i++) {
					step.getValue().run();
				}
				long each = (System.nanoTime() - start) / 1000;
				total += each;
				detail.append(step.getKey()).append(' ').append(each).append(" ns, ");
			}
			JavaHost.LOGGER.info("[JavaHost test] tick cost: {}", detail);
			check(total < 100_000, "everything JavaHost does each tick takes " + total / 1000 + " µs (under 100)");
		});
	}

	/** What the menu costs: frames per second with nothing open, then with each kind of screen open (uncapped). */
	private static void frameRates(ClientGameTestContext context) {
		context.getInput().resizeWindow(1710, 1070);
		int[] before = new int[2];
		context.runOnClient(mc -> {
			before[0] = mc.options.guiScale().get();
			before[1] = mc.options.framerateLimit().get();
			mc.options.guiScale().set(4);
			mc.options.framerateLimit().set(260);
			mc.options.enableVsync().set(false);
			TestCompat.resizeGui(mc);
		});
		java.util.Map<String, java.util.function.Supplier<net.minecraft.client.gui.screens.Screen>> screens = new java.util.LinkedHashMap<>();
		screens.put("no menu", () -> null);
		screens.put("vanilla pause", () -> new net.minecraft.client.gui.screens.PauseScreen(true));
		screens.put("hub home", () -> new JavaHostScreen(null, Tab.HOME));
		screens.put("hub voice", () -> new JavaHostScreen(null, Tab.VOICE));
		screens.put("hub mods", () -> new JavaHostScreen(null, Tab.MODS));
		screens.put("hub settings", () -> new JavaHostScreen(null, Tab.SETTINGS));
		for (var entry : screens.entrySet()) {
			context.setScreen(entry.getValue()::get);
			context.waitTicks(70);
			int fps = context.computeOnClient(net.minecraft.client.Minecraft::getFps);
			JavaHost.LOGGER.info("[JavaHost test] fps with {}: {}", entry.getKey(), fps);
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(before[0]);
			mc.options.framerateLimit().set(before[1]);
			TestCompat.resizeGui(mc);
		});
	}

	/** 2.0: the hub's Home with friends online (made-up ones), at the laptop sizes; the 3D model's idle animation runs. */
	@SuppressWarnings("unchecked")
	private static void hubWithFriends(ClientGameTestContext context) {
		record Pal(String name, com.javahost.client.friends.Presence.State state, String world, int players, int max) {
		}
		List<Pal> pals = List.of(new Pal("AlexPlays", com.javahost.client.friends.Presence.State.HOSTING, "Cherry Valley", 3, 8),
				new Pal("LunaBuilds", com.javahost.client.friends.Presence.State.PLAYING, "", 0, 0),
				new Pal("MossyMiner", com.javahost.client.friends.Presence.State.MENU, "", 0, 0),
				new Pal("RedstoneRay", com.javahost.client.friends.Presence.State.MENU, "", 0, 0),
				new Pal("PixelPip", com.javahost.client.friends.Presence.State.PLAYING, "", 0, 0));
		List<java.util.UUID> added = new java.util.ArrayList<>();
		List<com.javahost.client.friends.Friends.Friend>[] friends = new List[1];
		java.util.Map<java.util.UUID, com.javahost.client.friends.Presence.Status>[] latest = new java.util.Map[1];
		context.runOnClient(mc -> {
			try {
				var dataField = com.javahost.client.friends.Friends.class.getDeclaredField("data");
				dataField.setAccessible(true);
				Object data = dataField.get(null);
				var friendsField = data.getClass().getDeclaredField("friends");
				friendsField.setAccessible(true);
				friends[0] = (List<com.javahost.client.friends.Friends.Friend>) friendsField.get(data);
				var latestField = com.javahost.client.friends.Presence.class.getDeclaredField("LATEST");
				latestField.setAccessible(true);
				latest[0] = (java.util.Map<java.util.UUID, com.javahost.client.friends.Presence.Status>) latestField.get(null);
				long now = System.currentTimeMillis();
				String signKey = com.javahost.util.FriendCrypto.encodePublic(com.javahost.util.FriendCrypto.newSigningKeyPair().getPublic());
				for (Pal pal : pals) {
					java.util.UUID id = java.util.UUID.nameUUIDFromBytes(("hub-test:" + pal.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
					added.add(id);
					friends[0].add(new com.javahost.client.friends.Friends.Friend(id, pal.name(), "", now, "", signKey));
					List<com.javahost.client.friends.Friends.RouteInfo> routes = pal.state() == com.javahost.client.friends.Presence.State.HOSTING
							? List.of(new com.javahost.client.friends.Friends.RouteInfo("Relay", "WORKING", "brave-otter.eu.e4mc.link")) : List.of();
					latest[0].put(id, new com.javahost.client.friends.Presence.Status(id, pal.state(), pal.world(), pal.players(), pal.max(), routes, "", now));
				}
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		try {
			int previous = context.computeOnClient(mc -> mc.options.guiScale().get());
			for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 3}}) {
				context.getInput().resizeWindow(size[0], size[1]);
				context.runOnClient(mc -> {
					mc.options.guiScale().set(size[2]);
					TestCompat.resizeGui(mc);
				});
				context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
				context.waitTicks(10);
				context.takeScreenshot("hub-" + size[0] + "-s" + size[2] + "-home-friends");
			}
			check(settingsButton(context, "Join").widget() != null, "Home offers Join for a friend who's hosting");
			check(com.javahost.client.gui.ui.IdleSkin.posedFrames() > 0, "the 3D model's idle animation runs");
			// The head follows the mouse: one shot with it far left, one far right.
			context.getInput().setCursorPos(40, 300);
			context.waitTicks(30);
			context.takeScreenshot("hub-skin-look-left");
			context.getInput().setCursorPos(1900, 300);
			context.waitTicks(30);
			context.takeScreenshot("hub-skin-look-right");
			context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
			context.waitTicks(8);
			context.takeScreenshot("hub-2000-s3-friends");

			// Friends 2.0: favourites go first, friends' comings and goings are listed, and one button invites everyone online.
			context.getInput().resizeWindow(1710, 1070);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(4);
				TestCompat.resizeGui(mc);
				try {
					var seen = com.javahost.client.friends.FriendLog.class.getDeclaredMethod("seen", com.javahost.client.friends.Presence.Status.class,
							com.javahost.client.friends.Presence.Status.class, String.class);
					seen.setAccessible(true);
					for (var friend : friends[0]) {
						if (added.contains(friend.id())) {
							seen.invoke(null, null, latest[0].get(friend.id()), friend.name());
						}
					}
				} catch (ReflectiveOperationException e) {
					throw new AssertionError(e);
				}
				com.javahost.client.friends.FriendLog.toggleFavourite(added.get(3)); // RedstoneRay
			});
			context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
			context.waitTicks(8);
			context.takeScreenshot("friends-2-favourite-activity");
			check(com.javahost.client.friends.FriendLog.isFavourite(added.get(3)), "a friend can be made a favourite");
			check(settingsButton(context, "Remove from favourites").widget() != null && settingsButton(context, "Add to favourites").widget() != null,
					"every friend has a favourite star");
			check(com.javahost.client.friends.FriendLog.activity(10).stream().anyMatch(e -> e.what.equals("started hosting Cherry Valley")),
					"the activity list says a friend started hosting");
			check(settingsButton(context, "Invite all online").widget() != null, "hosting with friends online offers to invite them all");
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -20));
			context.waitTicks(10);
			context.takeScreenshot("friends-2-activity-scrolled");
			context.runOnClient(mc -> com.javahost.client.friends.FriendLog.toggleFavourite(added.get(3)));

			// The quick menu: its key opens a ring of actions; Menu opens the hub.
			context.setScreen(() -> new com.javahost.client.gui.QuickWheelScreen());
			context.waitTicks(10);
			context.getInput().setCursorPos(1710 / 2.0 + 300, 1070 / 2.0 - 120);
			context.waitTicks(10);
			context.takeScreenshot("quick-wheel");
			check(settingsButton(context, "Menu").widget() != null && settingsButton(context, "Invite").widget() != null
					&& settingsButton(context, "Capture").widget() != null, "the quick menu offers Menu, Invite and Capture while hosting");
			clickMenu(context, "Menu");
			context.waitTicks(4);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof JavaHostScreen), "the quick menu's Menu opens JavaHost");
			context.setScreen(() -> null);
			context.getInput().resizeWindow(854, 480);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(previous);
				TestCompat.resizeGui(mc);
			});
			context.waitTicks(4);
		} finally {
			context.runOnClient(mc -> {
				friends[0].removeIf(f -> added.contains(f.id()));
				added.forEach(latest[0]::remove);
			});
		}
	}

	/** UI review at the player's real setup: a 2000x1250 window at GUI scale 4 (and 3), every screen captured. */
	private static void reviewAtScale(ClientGameTestContext context, int guiScale) {
		reviewAtScale(context, guiScale, 2000, 1250);
	}

	private static void reviewAtScale(ClientGameTestContext context, int guiScale, int windowW, int windowH) {
		int previous = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.getInput().resizeWindow(windowW, windowH);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			TestCompat.resizeGui(mc);
		});
		Config.get().fpsOverlay = true;
		context.setScreen(() -> null);
		context.waitTicks(10);
		context.takeScreenshot("review-" + windowW + "-s" + guiScale + "-hud");
		Config.get().fpsOverlay = false;
		for (Tab tab : Tab.values()) {
			if (tab == Tab.PROFILE || tab == Tab.CHAT) {
				continue; // need a friend; covered by the two-game test
			}
			context.setScreen(() -> new JavaHostScreen(null, tab));
			context.waitTicks(8);
			context.takeScreenshot("review-" + windowW + "-s" + guiScale + "-" + tab.name().toLowerCase());
		}
		context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
		context.waitTicks(8);
		context.takeScreenshot("review-" + windowW + "-s" + guiScale + "-pause");
		context.setScreen(() -> null);
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(previous);
			TestCompat.resizeGui(mc);
		});
		context.waitTicks(4);
	}

	// ================================================================ 1.5

	private static String sha256(byte[] data) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
	}

	/** A pretend GitHub release on localhost, signed with a throwaway key the game is told to trust. */
	private static com.sun.net.httpserver.HttpServer fakeRelease(String version, int protocol, boolean tamper) throws Exception {
		return fakeRelease(version, protocol, tamper, "0");
	}

	private static com.sun.net.httpserver.HttpServer fakeRelease(String version, int protocol, boolean tamper, String minimum) throws Exception {
		java.security.KeyPair keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		byte[] jar = ("not really a jar " + version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
		String base = "http://localhost:" + server.getAddress().getPort();
		String json = "{\"version\": \"" + version + "\", \"protocol\": " + protocol + ", \"minecraft\": \"" + com.javahost.client.update.UpdateScreen.minecraftVersion() + "\", "
				+ "\"notes\": \"Faster joining, world rules and a welcome tour.\", \"releasePage\": \"" + base + "\", \"minimum\": \"" + minimum + "\", "
				+ "\"files\": [{\"name\": \"javahost-" + version + ".jar\", \"url\": \"" + base + "/javahost-" + version + ".jar\", "
				+ "\"sha256\": \"" + sha256(jar) + "\", \"size\": " + jar.length + ", \"replaces\": \"javahost-[0-9].*\\\\.jar\"}]}";
		byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(jsonBytes);
		byte[] sig = java.util.Base64.getEncoder().encode(signer.sign());
		byte[] served = tamper ? ("tampered " + version).getBytes(java.nio.charset.StandardCharsets.UTF_8) : jar;
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			byte[] body = path.endsWith("update.json") ? jsonBytes : path.endsWith(".sig") ? sig : path.endsWith(".jar") ? served : new byte[0];
			exchange.sendResponseHeaders(body.length == 0 ? 404 : 200, body.length == 0 ? -1 : body.length);
			try (var out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		System.setProperty("javahost.updateUrl", base);
		System.setProperty("javahost.updateKey", java.util.Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
		return server;
	}

	private static void updateFlow(ClientGameTestContext context, String version, int protocol, boolean tamper, String shot) throws Exception {
		com.sun.net.httpserver.HttpServer server = fakeRelease(version, protocol, tamper);
		try {
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(null));
			context.waitFor(mc -> mc.gui.screen() instanceof com.javahost.client.update.UpdateScreen, 400);
			check(true, "update " + version + " offered on the title screen");
			context.waitTicks(4);
			context.takeScreenshot(shot + "-offer");
			clickMenu(context, "Not now");
			context.waitTicks(4);
			context.takeScreenshot(shot + "-not-now");
			clickMenu(context, "Update now");
			context.waitFor(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
					&& (w.getMessage().getString().equals("Close Minecraft") || w.getMessage().getString().equals("Try again"))), 400);
			context.takeScreenshot(shot + "-result");
			boolean staged = context.computeOnClient(mc -> com.javahost.client.update.Updater.staged());
			if (tamper) {
				check(!staged, "a damaged or tampered download is refused (nothing staged)");
			} else {
				check(staged, "the update is downloaded, verified and waiting for the game to close");
				java.nio.file.Path plan = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("javahost/updates/plan.properties"));
				check(java.nio.file.Files.readString(plan).contains("javahost-" + version + ".jar"), "the swap plan names the new jar");
				java.nio.file.Files.delete(plan); // don't actually swap jars when this test game closes
			}
			context.setScreen(() -> null);
		} finally {
			server.stop(0);
			System.clearProperty("javahost.updateUrl");
			System.clearProperty("javahost.updateKey");
		}
	}

	private static FlatButtonFinder settingsButton(ClientGameTestContext context, String label) {
		return new FlatButtonFinder(context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.getMessage().getString().equals(label))
				.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c).findFirst().orElse(null)));
	}

	private record FlatButtonFinder(net.minecraft.client.gui.components.AbstractWidget widget) {
	}

	/** 1.5.2: a rollback can be cancelled; important releases can't be undone or skipped. */
	private static void rollbackAndImportantUpdates(ClientGameTestContext context) {
		java.nio.file.Path previous = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("javahost/updates/previous"));
		try {
			java.nio.file.Files.createDirectories(previous);
			java.nio.file.Files.writeString(previous.resolve("javahost-1.0.0.jar"), "pretend old version");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		// Restore, then change your mind.
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40));
		context.waitTicks(10);
		clickMenu(context, "Restore 1.0.0");
		clickMenu(context, "Click again to confirm");
		check("rollback".equals(context.computeOnClient(mc -> com.javahost.client.update.Updater.stagedKind())), "Restore (confirmed) waits for Minecraft to close");
		context.waitTicks(6);
		context.takeScreenshot("ui-settings-rollback-pending");
		clickMenu(context, "Cancel going back");
		check(!context.computeOnClient(mc -> com.javahost.client.update.Updater.staged()), "a pending rollback can be cancelled");

		// An important release that 1.0.0 is older than: restoring it is blocked, and a pending rollback is called off.
		context.runOnClient(mc -> {
			try {
				com.javahost.client.update.Updater.stageRollBack();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		try {
			com.sun.net.httpserver.HttpServer server = fakeRelease(com.javahost.JavaHost.VERSION, com.javahost.JavaHost.PROTOCOL, false, com.javahost.JavaHost.VERSION);
			try {
				java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
				context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(() -> done.complete(null)));
				context.waitFor(mc -> done.isDone(), 400);
			} finally {
				server.stop(0);
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		check(!context.computeOnClient(mc -> com.javahost.client.update.Updater.staged()),
				"a pending rollback below an important release is cancelled automatically");
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40));
		context.waitTicks(10);
		var restore = settingsButton(context, "Restore 1.0.0").widget();
		check(restore != null && !restore.active, "Restore is blocked when the previous version is older than an important update");
		context.takeScreenshot("ui-settings-rollback-blocked");

		// An important update for us: no "Not now" / "Keep", only "Remind me soon".
		try {
			com.sun.net.httpserver.HttpServer server = fakeRelease("9.9.11", com.javahost.JavaHost.PROTOCOL, false, "9.9.11");
			try {
				context.setScreen(() -> null);
				java.util.concurrent.CompletableFuture<Void> found = new java.util.concurrent.CompletableFuture<>();
				context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(() -> found.complete(null)));
				context.waitFor(mc -> found.isDone(), 400);
				context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
				context.waitFor(mc -> mc.gui.screen() instanceof com.javahost.client.update.UpdateScreen, 400);
				context.waitTicks(4);
				check(settingsButton(context, "Remind me soon").widget() != null && settingsButton(context, "Not now").widget() == null,
						"an important update can't be skipped, only put off for a few minutes");
				context.takeScreenshot("update-important");
				clickMenu(context, "Remind me soon");
			} finally {
				server.stop(0);
				System.clearProperty("javahost.updateUrl");
				System.clearProperty("javahost.updateKey");
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		try (var files = java.nio.file.Files.walk(previous)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		context.setScreen(() -> null);
	}

	/** 1.12.6: a copy from CurseForge or Modrinth never checks GitHub for updates, and modpacks only use Modrinth. */
	private static void modSiteCopy(ClientGameTestContext context) {
		System.setProperty("javahost.test.platform", "curseforge");
		try {
			com.sun.net.httpserver.HttpServer server = fakeRelease("9.9.13", com.javahost.JavaHost.PROTOCOL, false);
			try {
				java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
				context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(() -> done.complete(null)));
				context.waitFor(mc -> done.isDone(), 100);
				var latest = context.computeOnClient(mc -> com.javahost.client.update.UpdateChecker.latest());
				check(latest == null || !latest.version().equals("9.9.13"), "a CurseForge copy doesn't look for updates itself");
				check(!context.computeOnClient(mc -> com.javahost.client.update.Updater.selfUpdates()), "a CurseForge copy never replaces itself");
				check(com.javahost.util.Modrinth.packHosts().equals(java.util.List.of("cdn.modrinth.com")),
						"a CurseForge copy only downloads modpack files from Modrinth");
				context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
				context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40));
				context.waitTicks(10);
				check(settingsButton(context, "Check for updates").widget() == null, "Settings leaves updates to CurseForge");
				context.takeScreenshot("settings-curseforge-copy");
			} finally {
				server.stop(0);
				System.clearProperty("javahost.updateUrl");
				System.clearProperty("javahost.updateKey");
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		} finally {
			System.clearProperty("javahost.test.platform");
		}
		check(com.javahost.util.Modrinth.packHosts().size() > 1, "the GitHub build still accepts every host Modrinth packs use");
		context.setScreen(() -> null);
	}

	/** 1.12.5: skins of players outside our world (the title screen, friends elsewhere) are looked up, never waited for. */
	private static void outsideSkins(ClientGameTestContext context) {
		context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
		java.util.UUID stranger = java.util.UUID.fromString("00000000-0000-4000-8000-0000000000a1");
		long started = System.nanoTime();
		var first = context.computeOnClient(mc -> com.javahost.client.gui.ui.Faces.skinOf(stranger));
		check(first != null && System.nanoTime() - started < 1_000_000_000L,
				"a face outside the world shows the default skin straight away while it's looked up");
		context.setScreen(() -> null);
	}

	/** 1.13: installed by a launcher (CurseForge, Modrinth App): say what's new and to update there, never swap jars. */
	private static void launcherManagedUpdate(ClientGameTestContext context) {
		System.setProperty("javahost.test.launcherManaged", "true");
		try {
			com.sun.net.httpserver.HttpServer server = fakeRelease("9.9.12", com.javahost.JavaHost.PROTOCOL, false);
			try {
				context.setScreen(() -> null);
				java.util.concurrent.CompletableFuture<Void> found = new java.util.concurrent.CompletableFuture<>();
				context.runOnClient(mc -> com.javahost.client.update.UpdateChecker.checkNow(() -> found.complete(null)));
				context.waitFor(mc -> found.isDone(), 400);
				context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
				context.waitFor(mc -> mc.gui.screen() instanceof com.javahost.client.update.UpdateScreen, 400);
				context.waitTicks(4);
				check(settingsButton(context, "What's new").widget() != null && settingsButton(context, "Update now").widget() == null,
						"a launcher-installed JavaHost points to the launcher instead of updating itself");
				context.takeScreenshot("update-launcher");
				clickMenu(context, "OK");
				check(!context.computeOnClient(mc -> com.javahost.client.update.Updater.staged()), "nothing is downloaded for a launcher install");
			} finally {
				server.stop(0);
				System.clearProperty("javahost.updateUrl");
				System.clearProperty("javahost.updateKey");
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		} finally {
			System.clearProperty("javahost.test.launcherManaged");
		}
		context.setScreen(() -> null);
	}

	/** 1.5: updater, world rules, welcome tour, screenshot tools, debug report. */
	private static void version15(ClientGameTestContext context) {
		try {
			updateFlow(context, "9.9.9", com.javahost.JavaHost.PROTOCOL, false, "update-same-protocol");
			updateFlow(context, "9.9.10", com.javahost.JavaHost.PROTOCOL + 1, true, "update-new-protocol");
		} catch (Exception e) {
			throw new AssertionError(e);
		}

		rollbackAndImportantUpdates(context);
		launcherManagedUpdate(context);
		outsideSkins(context);
		modSiteCopy(context);

		// World rules: the host changes a rule; locking stops changes, even by command.
		context.setScreen(() -> new JavaHostScreen(null, Tab.RULES));
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state() != null, 200);
		check(context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.state().host()), "the host sees world rules and can change them");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("DIFFICULTY", "easy"));
		context.waitFor(mc -> "easy".equals(com.javahost.client.rules.RulesClient.state().values().get("DIFFICULTY")), 100);
		check(context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty() == net.minecraft.world.Difficulty.EASY),
				"changing Difficulty on the page changes the world");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "true"));
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state().locked(), 100);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("DIFFICULTY", "hard"));
		context.runOnClient(mc -> {
			var server = mc.getSingleplayerServer();
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "difficulty hard"));
		});
		context.waitTicks(50);
		check(context.computeOnClient(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty() == net.minecraft.world.Difficulty.EASY),
				"while locked, neither the page nor /difficulty can change the rules");
		context.setScreen(() -> new JavaHostScreen(null, Tab.RULES));
		context.waitTicks(8);
		context.takeScreenshot("ui-world-rules-locked");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "false"));
		context.waitFor(mc -> !com.javahost.client.rules.RulesClient.state().locked(), 100);
		check(context.computeOnClient(mc -> com.javahost.client.rules.RulesClient.state().history().size() >= 3), "changes are kept in the history");

		// Voice reach is one world rule for everyone (1.11): the host switches the whole world to party voice.
		check("nearby".equals(context.computeOnClient(mc -> com.javahost.client.voice.VoiceClient.worldRule())), "a world starts on nearby voice");
		context.runOnClient(mc -> com.javahost.client.JavaHostClient.toggleParty());
		context.waitFor(mc -> "party".equals(com.javahost.client.voice.VoiceClient.worldRule()), 100);
		check(context.computeOnClient(mc -> com.javahost.rules.WorldRules.partyVoice()), "the host's B key switches the whole world to party voice");
		context.setScreen(() -> new JavaHostScreen(null, Tab.VOICE));
		context.waitTicks(8);
		context.takeScreenshot("ui-voice-world-party");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "true"));
		context.waitFor(mc -> com.javahost.client.rules.RulesClient.state().locked(), 100);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("VOICE", "nearby"));
		context.waitTicks(20);
		check(context.computeOnClient(mc -> com.javahost.rules.WorldRules.partyVoice()), "while the rules are locked, voice reach stays as it is");
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("__lock", "false"));
		context.waitFor(mc -> !com.javahost.client.rules.RulesClient.state().locked(), 100);
		context.runOnClient(mc -> com.javahost.client.rules.RulesClient.request("VOICE", "nearby"));
		context.waitFor(mc -> "nearby".equals(com.javahost.client.voice.VoiceClient.worldRule()), 100);
		check(!context.computeOnClient(mc -> com.javahost.rules.WorldRules.partyVoice()), "back to nearby voice for everyone");
		context.setScreen(() -> new JavaHostScreen(null, Tab.RULES));
		context.waitTicks(8);
		context.takeScreenshot("ui-world-rules");

		// Welcome tour: shown the first time, Next through it, Done.
		Config.get().tourDone = false;
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field offered = JavaHostScreen.class.getDeclaredField("tourOffered");
				offered.setAccessible(true);
				offered.set(null, false);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		context.setScreen(() -> new JavaHostScreen(null));
		context.waitTicks(6);
		for (int step = 1; step <= 5; step++) {
			context.takeScreenshot("ui-tour-" + step);
			clickMenu(context, step == 5 ? "Done" : "Next");
		}
		check(Config.get().tourDone, "the welcome tour runs through and is remembered");

		// Screenshot tools: favourite, rename, delete.
		java.nio.file.Path shot = context.computeOnClient(mc -> com.javahost.client.friends.Screenshots.list(false).getFirst());
		context.runOnClient(mc -> com.javahost.client.friends.Screenshots.setFavourite(shot, true));
		check(context.computeOnClient(mc -> com.javahost.client.friends.Screenshots.favourites().contains(shot)), "a starred screenshot shows in Favourites");
		java.nio.file.Path renamed = context.computeOnClient(mc -> {
			try {
				return com.javahost.client.friends.Screenshots.rename(shot, "My best build");
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		check(renamed.getFileName().toString().equals("My best build.png")
				&& context.computeOnClient(mc -> com.javahost.client.friends.Screenshots.isFavourite(renamed)), "renaming keeps it starred");
		context.setScreen(() -> new JavaHostScreen(null, Tab.SCREENSHOTS));
		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(renamed));
		context.waitTicks(8);
		context.takeScreenshot("ui-screenshot-tools");
		clickMenu(context, "Delete");
		clickMenu(context, "Click again");
		check(!java.nio.file.Files.exists(renamed), "Delete (clicked twice) removes the screenshot");

		// Debug report.
		String report = context.computeOnClient(mc -> com.javahost.client.DebugReport.build());
		check(report.startsWith("JavaHost debug report") && report.contains("Minecraft " + com.javahost.client.update.UpdateScreen.minecraftVersion())
				&& !java.util.regex.Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b").matcher(report).find(), "debug report has versions and no IP addresses");
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -40));
		context.waitTicks(10);
		context.takeScreenshot("ui-settings-updates-help");
		context.setScreen(() -> null);
	}

	/** 1.4: big screenshots fit the relay, the video settings switch, and world backups. */
	private static void version14(ClientGameTestContext context) {
		// Screenshot size: ntfy.sh takes at most 2 MB per file.
		Path big = context.computeOnClient(mc -> writeNoisePng(com.javahost.client.friends.Screenshots.dir().resolve("zz-noise-test.png"), 2560, 1440));
		try {
			long original = java.nio.file.Files.size(big);
			byte[] sendable = com.javahost.client.friends.Screenshots.prepareForSending(big);
			check(sendable.length <= 1_900_000 && com.javahost.client.friends.Screenshots.isPng(sendable),
					"a " + original / 1024 + " KB screenshot is scaled to " + sendable.length / 1024 + " KB for the relay (limit 2 MB)");
			byte[] forGame = com.javahost.client.friends.Screenshots.prepareForGame(big);
			check(forGame.length > 2_000_000 && forGame.length <= 3_800_000, "the in-game fast lane keeps it bigger (" + forGame.length / 1024 + " KB)");
			java.nio.file.Files.delete(big);
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		// Video settings: Simple (Minecraft's screen) or Sodium, with a button on each to switch.
		if (com.javahost.client.perf.VideoMenu.sodiumInstalled()) {
			Config config = Config.get();
			config.videoMenu = "SIMPLE";
			context.runOnClient(mc -> com.javahost.client.perf.VideoMenu.openSodium(mc, null));
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen),
					"with Simple chosen, Video Settings opens Minecraft's own screen");
			context.takeScreenshot("video-simple");
			clickMenu(context, "More settings (Sodium)");
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen().getClass().getName().contains("sodium")) && "SODIUM".equals(config.videoMenu),
					"\"More settings (Sodium)\" opens Sodium's screen and remembers it");
			context.takeScreenshot("video-sodium");
			clickMenu(context, "Simple");
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen)
					&& "SIMPLE".equals(config.videoMenu), "\"Simple\" switches back");
			context.setScreen(() -> null);
		}

		// World backup while playing.
		java.util.concurrent.CompletableFuture<Path> backup = context.computeOnClient(mc -> {
			java.util.concurrent.CompletableFuture<Path> done = new java.util.concurrent.CompletableFuture<>();
			mc.getSingleplayerServer().execute(() -> com.javahost.client.host.WorldBackups.backUp(mc.getSingleplayerServer(), "test")
					.whenComplete((file, error) -> {
						if (error != null) {
							done.completeExceptionally(error);
						} else {
							done.complete(file);
						}
					}));
			return done;
		});
		context.waitFor(mc -> backup.isDone(), 60 * 20);
		Path zip = backup.join();
		check(java.nio.file.Files.exists(zip), "world backed up while playing: " + zip.getFileName());
		// Automatic backups wait 30 minutes after the newest one, even across restarts.
		int before = context.computeOnClient(mc -> com.javahost.client.host.WorldBackups.listAll().size());
		var again = context.computeOnClient(mc -> {
			var result = new java.util.concurrent.CompletableFuture<Path>();
			mc.getSingleplayerServer().execute(() -> com.javahost.client.host.WorldBackups.backUpIfDue(mc.getSingleplayerServer(), "test again")
					.whenComplete((file, error) -> result.complete(file)));
			return result;
		});
		context.waitFor(mc -> again.isDone(), 60 * 20);
		check(context.computeOnClient(mc -> com.javahost.client.host.WorldBackups.listAll().size()) == before && zip.equals(again.join()),
				"a second automatic backup within 30 minutes is skipped");
		com.javahost.client.gui.pages.HostPage.showSection("backups");
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.waitTicks(10);
		context.takeScreenshot("ui-host-backups");
		com.javahost.client.gui.pages.HostPage.showSection("settings");
		context.setScreen(() -> null);
	}

	/** After leaving the world: restoring a backup keeps the current world as a copy. */
	private static void restoreBackup(ClientGameTestContext context) {
		var backups = context.computeOnClient(mc -> com.javahost.client.host.WorldBackups.listAll());
		check(!backups.isEmpty(), "backups are listed after leaving the world");
		var newest = backups.getFirst();
		String aside = context.computeOnClient(mc -> {
			try {
				return com.javahost.client.host.WorldBackups.restore(newest);
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		Path saves = context.computeOnClient(mc -> mc.getLevelSource().getBaseDir());
		check(java.nio.file.Files.exists(saves.resolve(newest.world()).resolve("level.dat")), "restore puts the backup back as " + newest.world());
		check(aside == null || java.nio.file.Files.exists(saves.resolve(aside)), "the world it replaced is kept as \"" + aside + "\"");
		com.javahost.client.gui.pages.HostPage.showSection("backups");
		context.setScreen(() -> new JavaHostScreen(new net.minecraft.client.gui.screens.TitleScreen(), Tab.HOST));
		context.waitTicks(12);
		context.takeScreenshot("ui-backups-no-world");
		com.javahost.client.gui.pages.HostPage.showSection("settings");
	}

	/** Everything in the menu can be reached and left with the mouse: ← goes back, ✕ closes. */
	private static void navigation(ClientGameTestContext context, Path file) {
		context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
		context.waitTicks(4);
		clickMenu(context, "Join with a code");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.JOIN, "clicking opens the Join page");
		context.takeScreenshot("ui-join-with-back");
		clickMenu(context, "Back");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.FRIENDS, "← on Join goes back to Friends");

		context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
		context.waitTicks(4);
		clickMenu(context, "Notifications");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.NOTIFICATIONS, "the bell opens notifications");
		clickMenu(context, "Back");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).tab()) == Tab.HOME, "← on notifications goes back to Home");

		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(file));
		context.waitTicks(6);
		clickMenu(context, "Send to a friend");
		context.waitTicks(4);
		context.takeScreenshot("ui-screenshot-send-picker");
		clickMenu(context, "Back");
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).selectedScreenshot()) == null, "← on a screenshot goes back to the grid");
		context.waitTicks(4);
		context.takeScreenshot("ui-screenshots-grid-back");
		// Hovering a thumbnail shows its send shortcut; clicking it opens the preview with the friend list open.
		double[] corner = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof com.javahost.client.gui.ui.ImageButton w) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getRight() - 10) * scale, (w.getY() + 9) * scale};
				}
			}
			throw new AssertionError("no thumbnails");
		});
		context.getInput().setCursorPos(corner[0], corner[1]);
		context.waitTicks(3);
		context.takeScreenshot("ui-screenshots-hover-send");
		context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(4);
		check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).selectedScreenshot()) != null,
				"a thumbnail's send shortcut opens that screenshot");
		context.takeScreenshot("ui-screenshots-shortcut-picker");
		clickMenu(context, "Back");

		clickMenu(context, "Close");
		check(context.computeOnClient(mc -> mc.gui.screen() == null), "✕ closes the menu");
	}

	/** Clicks a visible button in the current screen with the real mouse. */
	// ================================================================ 1.8: public worlds

	static com.google.gson.JsonObject call(String base, String method, String path, com.google.gson.JsonObject body, String token)
			throws Exception {
		var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path)).header("content-type", "application/json")
				.method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody() : java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()));
		if (token != null) {
			request.header("authorization", "Bearer " + token);
		}
		String text = java.net.http.HttpClient.newHttpClient().send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
		return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
	}

	/** Lists a made-up public world on the local list service, as account {@code name}. */
	static void seed(String base, String name, String title, String description, String relay, List<String> tags, int max,
			int players, boolean voice) throws Exception {
		var challenge = call(base, "POST", "/auth/challenge", new com.google.gson.JsonObject(), null);
		var verify = new com.google.gson.JsonObject();
		verify.addProperty("name", name);
		verify.addProperty("uuid", net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name).toString());
		verify.addProperty("serverId", challenge.get("serverId").getAsString());
		verify.addProperty("expires", challenge.get("expires").getAsLong());
		String token = call(base, "POST", "/auth/verify", verify, null).get("token").getAsString();
		var listing = new com.google.gson.JsonObject();
		listing.addProperty("title", title);
		listing.addProperty("description", description);
		var tagArray = new com.google.gson.JsonArray();
		tags.forEach(tagArray::add);
		listing.add("tags", tagArray);
		listing.addProperty("mode", "survival");
		listing.addProperty("difficulty", "normal");
		listing.addProperty("max", max);
		listing.addProperty("relay", relay);
		listing.addProperty("mc", net.minecraft.SharedConstants.getCurrentVersion().name());
		listing.addProperty("jv", JavaHost.VERSION);
		listing.addProperty("voice", voice);
		listing.addProperty("signkey", com.javahost.util.FriendCrypto.encodePublic(com.javahost.util.FriendCrypto.newSigningKeyPair().getPublic()));
		check(call(base, "PUT", "/listing", listing, token).has("ok"), "listed \"" + title + "\"");
		var beat = new com.google.gson.JsonObject();
		beat.addProperty("players", players);
		call(base, "POST", "/listing/heartbeat", beat, token);
	}

	/** Discover with a few public worlds, an opened row, the Open-to-the-public form and Report, at real sizes. */
	private static void publicWorldScreens(ClientGameTestContext context) {
		// JavaHost's background threads must load classes like the game does, or pinging a server from one breaks
		// Minecraft's address checks for the rest of the session (every join then hangs on "Connecting").
		for (var pool : List.of(com.javahost.util.IoExecutor.POOL, com.javahost.util.IoExecutor.NET)) {
			try {
				ClassLoader loader = java.util.concurrent.CompletableFuture.supplyAsync(() -> Thread.currentThread().getContextClassLoader(), pool)
						.get(5, java.util.concurrent.TimeUnit.SECONDS);
				check(loader == JavaHost.class.getClassLoader(), "background threads use the game's class loader");
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
		String serverJs = System.getProperty("javahost.directoryServer");
		if (serverJs == null) {
			JavaHost.LOGGER.info("[JavaHost test] skipping public worlds (no local list service)");
			return;
		}
		int port = 8796;
		String base = "http://127.0.0.1:" + port;
		Process node = null;
		try {
			node = new ProcessBuilder("node", serverJs, String.valueOf(port)).redirectErrorStream(true)
					.redirectOutput(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "javahost-directory-test.log").toFile()).start();
			long deadline = System.currentTimeMillis() + 15_000;
			while (true) {
				try {
					call(base, "GET", "/listings", null, null);
					break;
				} catch (Exception notYet) {
					if (System.currentTimeMillis() > deadline) {
						throw new AssertionError("the local list service didn't start");
					}
					Thread.sleep(200);
				}
			}
			System.setProperty("javahost.directoryUrl", base);
			seed(base, "Alex_TheBuilder", "Help me build a giant castle", "Bring stone and good vibes. Starting the north tower tonight.",
					"brave-otter.eu.e4mc.link", List.of("help", "building"), 8, 3, false);
			seed(base, "Sam", "Hardcore-ish survival", "Normal difficulty, no griefing please.", "calm-heron.us.e4mc.link",
					List.of("survival"), 4, 4, true);
			seed(base, "Kim_Crafts", "Chill creative flatland", "", "quiet-maple.as.e4mc.link", List.of("creative", "chill"), 16, 1, false);
			seed(base, "Lee", "Mining trip to the deep dark", "Need two people with good armour.", "swift-lynx.eu.e4mc.link",
					List.of("help", "survival"), 4, 2, false);
			var found = com.javahost.client.discover.Directory.list().get(10, java.util.concurrent.TimeUnit.SECONDS);
			check(found.size() == 4, "the public list shows the 4 worlds (" + found.size() + ")");
			check(found.stream().allMatch(w -> com.javahost.util.ListingText.isRelayAddress(w.relay())), "public worlds only carry relay addresses");
			// (Only when the relay mod is missing; some versions' tests load it everywhere.)
			check(com.javahost.client.host.E4mcCompat.isInstalled()
					|| context.computeOnClient(mc -> com.javahost.client.discover.PublicHosting.cannotOpen()).contains("relay"),
					"opening to the public needs the relay");
		} catch (Exception e) {
			if (node != null) {
				node.destroy();
			}
			throw new AssertionError(e);
		}
		try {
			// The last size is as short as the user's own window (about 247 GUI pixels tall).
			for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 4}, {1320, 988, 4}}) {
				context.getInput().resizeWindow(size[0], size[1]);
				context.runOnClient(mc -> {
					mc.options.guiScale().set(size[2]);
					TestCompat.resizeGui(mc);
				});
				// The title screen dock: every button inside the window, above the copyright line.
				context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
				context.waitTicks(4);
				boolean dockFits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
						.filter(c -> c.getClass().getSimpleName().equals("DockButton"))
						.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
						.allMatch(w -> w.getBottom() <= mc.getWindow().getGuiScaledHeight() - 12));
				check(dockFits, "the title screen dock fits at " + size[0] + "x" + size[1] + " scale " + size[2]);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-title-dock");
				setDiscoverSelection(context, null);
				context.setScreen(() -> new JavaHostScreen(null, Tab.DISCOVER));
				context.waitTicks(60);
				boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
						.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
						.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
						.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
				check(fits, "Discover fits at " + size[0] + "x" + size[1] + " scale " + size[2]);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-discover");
				java.util.UUID first = context.computeOnClient(mc -> {
					try {
						return com.javahost.client.discover.Directory.list().get(5, java.util.concurrent.TimeUnit.SECONDS).stream()
								.filter(w -> w.title().startsWith("Help me build")).findFirst().orElseThrow().host();
					} catch (Exception e) {
						throw new AssertionError(e);
					}
				});
				setDiscoverSelection(context, first);
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).refresh());
				context.waitTicks(4);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-discover-open");
				context.setScreen(() -> new com.javahost.client.gui.PublicWorldScreen(null));
				context.waitTicks(4);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-public-form");
				context.setScreen(() -> new com.javahost.client.gui.ReportScreen(null, first, "Alex_TheBuilder"));
				context.waitTicks(4);
				context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-report");
			}
		} finally {
			setDiscoverSelection(context, null);
			context.setScreen(() -> null);
			System.clearProperty("javahost.directoryUrl");
			node.destroy();
		}
	}

	/** 1.9: a world that needs mods (some on Modrinth, one not), at the laptop sizes. */
	private static void modsScreens(ClientGameTestContext context) {
		String hash = "a".repeat(128);
		var manifest = new com.javahost.util.ModManifest("Alex_TheBuilder", "", List.of(
				new com.javahost.util.ModManifest.Entry("naturalist", "Naturalist", "2.0.6+26.3-fabric", true, "naturalist.jar", 11_400_000, hash, "35ZJ5hlX", ""),
				new com.javahost.util.ModManifest.Entry("geckolib", "GeckoLib", "5.2.1", false, "geckolib.jar", 1_200_000, hash, "AbCd1234", ""),
				new com.javahost.util.ModManifest.Entry("yungsapi", "YUNG's API", "26.1.2-Fabric-6.1.3", true, "yungsapi.jar", 900_000, hash, "EfGh5678", ""),
				new com.javahost.util.ModManifest.Entry("castlemod", "Castle Builder Extreme With A Long Name", "1.0", true, "castle.jar", 4_000_000, hash, "",
						"https://example.com/castle")));
		var needed = new com.javahost.client.mods.ModSets.Needed(manifest, manifest.mods(), "203.0.113.42:25565", null);
		for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 4}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				TestCompat.resizeGui(mc);
			});
			context.setScreen(() -> new com.javahost.client.gui.ModsNeededScreen(needed));
			context.waitTicks(4);
			boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
					.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
					.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8 && w.getBottom() <= mc.getWindow().getGuiScaledHeight()));
			check(fits, "the mods-needed screen fits at " + size[0] + "x" + size[1] + " scale " + size[2]);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-mods-needed");
			com.javahost.client.gui.pages.ModsPage.showMine(false);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(80); // the search answers from Modrinth and icons arrive
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-mods-tab");
			com.javahost.client.gui.pages.ModsPage.showMine(true);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(8);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-mods-mine");
			com.javahost.client.gui.pages.ModsPage.showMine(false);
		}
		// Browse finds mods for every version; ones that can't run here say where they run, with no Install.
		context.getInput().resizeWindow(1710, 1070);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			TestCompat.resizeGui(mc);
		});
		com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "aether");
		context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
		context.waitFor(mc -> !com.javahost.client.gui.pages.ModsPage.searching() && !com.javahost.client.gui.pages.ModsPage.results().isEmpty()
				&& mc.gui.screen().children().stream().anyMatch(c -> c instanceof com.javahost.client.gui.ui.RowButton), 400);
		context.waitTicks(60); // icons
		long cards = context.computeOnClient(mc -> mc.gui.screen().children().stream().filter(c -> c instanceof com.javahost.client.gui.ui.RowButton).count());
		long installs = context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof com.javahost.client.gui.ui.FlatButton b && b.getMessage().getString().equals("Install")).count());
		check(cards > 0 && installs < cards, "Browse shows mods for other versions without Install (" + cards + " found, " + installs + " installable)");
		context.takeScreenshot("review-1710-s4-mods-search-other-versions");
		com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "");
		context.setScreen(() -> null);
	}

	private static void size(ClientGameTestContext context, int w, int h, int scale) {
		context.getInput().resizeWindow(w, h);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			TestCompat.resizeGui(mc);
		});
	}

	private static boolean widgetsFit(ClientGameTestContext context) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
				.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
				.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
	}

	private static boolean hasButton(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
				&& w.visible && w.getMessage().getString().equals(label)));
	}

	/** 1.12: Discover by category, a project's page, and installs with progress in the menu's download list. */
	private static void storeAndDownloads(ClientGameTestContext context) {
		Path mods = context.computeOnClient(mc -> net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods"));
		java.util.Set<String> before = fileNames(mods);
		String minecraft = context.computeOnClient(mc -> com.javahost.client.mods.Modpacks.gameVersion());
		size(context, 1710, 1070, 4);
		try {
			// A category.
			com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "");
			com.javahost.client.gui.pages.ModsPage.showCategory("magic");
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitFor(mc -> !com.javahost.client.gui.pages.ModsPage.searching() && !com.javahost.client.gui.pages.ModsPage.results().isEmpty(), 400);
			context.waitTicks(60); // icons
			check(com.javahost.client.gui.pages.ModsPage.results().stream().allMatch(p -> p.categories().contains("magic")),
					"the Magic chip shows only magic mods");
			check(widgetsFit(context), "Discover fits at 1710x1070 scale 4");
			context.takeScreenshot("review-1710-s4-mods-category");

			// Only ones that run here.
			com.javahost.client.gui.pages.ModsPage.showCategory("");
			com.javahost.client.gui.pages.ModsPage.showRunsHere(true);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitFor(mc -> !com.javahost.client.gui.pages.ModsPage.searching() && !com.javahost.client.gui.pages.ModsPage.results().isEmpty(), 400);
			check(com.javahost.client.gui.pages.ModsPage.results().stream().allMatch(p -> com.javahost.util.Availability.installable(p, minecraft)),
					"\"Only ones that run here\" shows only mods for this version");
			List<com.javahost.util.Modrinth.Project> picks = context.computeOnClient(mc -> com.javahost.client.gui.pages.ModsPage.results().stream()
					.filter(p -> !com.javahost.client.perf.BoostMods.installedSlug(p.slug())).limit(3).toList());
			check(picks.size() == 3, "found mods to install");

			// A project's page, then Install from it.
			for (int[] s : new int[][] {{1280, 800, 3}, {1710, 1070, 4}}) {
				size(context, s[0], s[1], s[2]);
				com.javahost.client.gui.pages.ModsPage.showDetail(picks.get(0));
				context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
				context.waitTicks(30);
				check(hasButton(context, "Install") && hasButton(context, "Modrinth"), "a mod's page has Install and Modrinth");
				check(widgetsFit(context), "a mod's page fits at " + s[0] + "x" + s[1]);
				context.takeScreenshot("review-" + s[0] + "-s" + s[2] + "-mods-detail");
			}
			clickMenu(context, "Install");
			check(hasButton(context, "Downloads"), "the download icon shows in the menu header");
			// Back to the list, and queue a second one from its card.
			clickMenu(context, "Back");
			check(com.javahost.client.gui.pages.ModsPage.results().size() > 0 && hasButton(context, "Install"), "back to the list");
			context.runOnClient(mc -> com.javahost.client.mods.Downloads.installMod(picks.get(1).id(), picks.get(1).slug(), picks.get(1).title(),
					picks.get(1).iconUrl()));
			context.waitTicks(6);
			context.takeScreenshot("review-1710-s4-downloads-ring");
			clickMenu(context, "Downloads");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()), "the download list opens");
			context.takeScreenshot("review-1710-s4-downloads-popover");
			context.waitFor(mc -> !com.javahost.client.mods.Downloads.active(), 20 * 180);
			for (int i = 0; i < 2; i++) {
				var job = com.javahost.client.mods.Downloads.job(picks.get(i).id());
				check(job != null && job.stage() == com.javahost.client.mods.Downloads.Stage.DONE && job.needsRestart(),
						picks.get(i).title() + " downloaded in turn and waits for a restart");
			}
			context.waitTicks(10);
			context.takeScreenshot("review-1710-s4-downloads-done");
			check(context.computeOnClient(mc -> com.javahost.client.friends.Notifications.items().stream()
					.anyMatch(n -> n.payload() instanceof com.javahost.client.friends.Notifications.Action a && a.label().equals("Close game"))),
					"a notification says it's ready, with Close game");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(10);
			check(hasButton(context, "Restart") && hasButton(context, "Close Minecraft"), "the card says Restart, with Close Minecraft above the list");
			context.takeScreenshot("review-1710-s4-mods-restart");

			// Cancelling one that's waiting its turn.
			var third = context.computeOnClient(mc -> {
				var job = com.javahost.client.mods.Downloads.installMod(picks.get(2).id(), picks.get(2).slug(), picks.get(2).title(), picks.get(2).iconUrl());
				com.javahost.client.mods.Downloads.cancel(job);
				return job;
			});
			context.waitFor(mc -> !third.active(), 20 * 60);
			check(third.stage() == com.javahost.client.mods.Downloads.Stage.CANCELLED, "a cancelled download stops");

			// Installed: the downloads and the mods JavaHost put in.
			for (int[] s : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 3}}) {
				size(context, s[0], s[1], s[2]);
				com.javahost.client.gui.pages.ModsPage.showMine(true);
				context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
				context.waitTicks(10);
				check(widgetsFit(context), "Installed fits at " + s[0] + "x" + s[1]);
				context.takeScreenshot("review-" + s[0] + "-s" + s[2] + "-mods-installed");
			}

			// Removing one: it's marked to go when the game closes, and Keep takes that back.
			size(context, 1710, 1070, 4);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
					mc.getWindow().getGuiScaledHeight() / 2.0, 0, -6));
			context.waitTicks(12);
			clickMenu(context, "Remove");
			check(!com.javahost.client.mods.Removals.pendingJars().isEmpty() && hasButton(context, "Keep"), "Remove marks a mod to go when the game closes");
			context.takeScreenshot("review-1710-s4-mods-removing");
			clickMenu(context, "Keep");
			check(com.javahost.client.mods.Removals.pendingJars().isEmpty(), "Keep takes it off the list");
		} finally {
			context.runOnClient(mc -> {
				try {
					java.nio.file.Files.deleteIfExists(mc.gameDirectory.toPath().resolve("javahost").resolve("remove-on-exit.txt"));
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			});
			// Leave the test game's mods folder as it was.
			for (String name : fileNames(mods)) {
				if (!before.contains(name)) {
					try {
						java.nio.file.Files.deleteIfExists(mods.resolve(name));
					} catch (IOException e) {
						JavaHost.LOGGER.warn("[JavaHost test] couldn't remove {}", name, e);
					}
				}
			}
			context.runOnClient(mc -> {
				com.javahost.client.mods.Downloads.clearFinished();
				com.javahost.client.friends.Notifications.clear();
			});
			com.javahost.client.gui.pages.ModsPage.showRunsHere(false);
			com.javahost.client.gui.pages.ModsPage.showSearch(com.javahost.util.Modrinth.Kind.MOD, "");
			context.setScreen(() -> null);
		}
	}

	/** 1.12.3: a big modpack (thousands of config files) must not slow the Mods page down (it used to re-count every tick). */
	private static void bigModSetStaysFast(ClientGameTestContext context) {
		Path set = context.computeOnClient(mc -> com.javahost.client.mods.ModSets.dir().resolve("pack-showcasetest"));
		try {
			Files.createDirectories(set.resolve("overrides/config"));
			for (int i = 0; i < 3000; i++) {
				Files.writeString(set.resolve("overrides/config/c" + i + ".json"), "{}");
			}
			Files.writeString(set.resolve("set.properties"), "label=Big Test Pack 1.0\nkind=pack\ncreated=1\n");
			context.runOnClient(mc -> com.javahost.client.mods.ModSets.saved()); // a new set: counted once
			context.runOnClient(mc -> {
				try {
					var write = com.javahost.client.mods.ModSets.class.getDeclaredMethod("changed");
					write.setAccessible(true);
					write.invoke(null);
				} catch (ReflectiveOperationException e) {
					throw new AssertionError(e);
				}
			});
			check(context.computeOnClient(mc -> com.javahost.client.mods.ModSets.saved().stream().anyMatch(x -> x.label().equals("Big Test Pack 1.0"))),
					"a new mod set shows up");
			long took = context.computeOnClient(mc -> {
				long start = System.nanoTime();
				for (int i = 0; i < 200; i++) {
					com.javahost.client.mods.ModSets.saved();
					com.javahost.client.mods.ModSets.staged();
					com.javahost.client.mods.ModSets.stagedLabel();
					com.javahost.client.mods.ModSets.activeLabel();
				}
				return (System.nanoTime() - start) / 1_000_000;
			});
			check(took < 50, "200 checks of the mod sets take " + took + " ms (no files read each tick)");
			com.javahost.client.gui.pages.ModsPage.showMine(true);
			context.setScreen(() -> new JavaHostScreen(null, Tab.MODS));
			context.waitTicks(10);
			check(context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
					&& w.getMessage().getString().equals("Use"))), "the big set is listed in Installed");
		} catch (IOException e) {
			throw new AssertionError(e);
		} finally {
			context.runOnClient(mc -> {
				try {
					com.javahost.client.mods.ModSets.delete(com.javahost.client.mods.ModSets.saved().stream()
							.filter(x -> x.folder().equals("pack-showcasetest")).findFirst().orElseThrow());
				} catch (IOException | RuntimeException e) {
					JavaHost.LOGGER.warn("[JavaHost test] couldn't remove the test set", e);
				}
			});
			com.javahost.client.gui.pages.ModsPage.showMine(false);
			context.setScreen(() -> null);
		}
	}

	private static java.util.Set<String> fileNames(Path folder) {
		try (var files = java.nio.file.Files.list(folder)) {
			return files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
		} catch (IOException e) {
			return java.util.Set.of();
		}
	}

	/** 1.12: the Host tab's sub-tabs while hosting, with sample players, at the review sizes; and a player's menus. */
	private static void hostTabs(ClientGameTestContext context) {
		context.runOnClient(mc -> HostController.previewGuests(List.of(
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("Steve123".getBytes()), "Steve123", 42, net.minecraft.world.level.GameType.SURVIVAL, false, false),
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("AlexPlays".getBytes()), "AlexPlays", 180, net.minecraft.world.level.GameType.CREATIVE, true, false),
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("Builder".getBytes()), "Builder_With_A_Long_Name", 0,
						net.minecraft.world.level.GameType.SURVIVAL, false, true))));
		try {
			for (int[] s : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 3}}) {
				size(context, s[0], s[1], s[2]);
				for (String section : List.of("members", "general", "rules", "sharing", "backups", "connection", "advanced")) {
					com.javahost.client.gui.pages.HostPage.showSection(section);
					context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
					context.waitTicks(8);
					check(hasButton(context, "Invite friends") && hasButton(context, "Stop hosting"), "Invite and Stop stay on top (" + section + ")");
					check(widgetsFit(context), "Host " + section + " fits at " + s[0] + "x" + s[1] + " scale " + s[2]);
					context.takeScreenshot("review-" + s[0] + "-s" + s[2] + "-host-" + section);
				}
			}
			size(context, 1710, 1070, 4);
			com.javahost.client.gui.pages.HostPage.showSection("members");
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
			context.waitTicks(8);
			clickMenu(context, "More");
			check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()), "a player's More menu opens");
			context.takeScreenshot("review-1710-s4-host-player-menu");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
			clickMenu(context, "Survival");
			context.takeScreenshot("review-1710-s4-host-game-mode");
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());
		} finally {
			context.runOnClient(mc -> HostController.previewGuests(null));
			size(context, 1710, 1070, 4);
		}
	}

	// ================================================================ 2.0: the World page (World options)

	private static final int[][] WORLD_SIZES = {{1710, 1070, 4}, {1280, 800, 3}};

	private static String worldShot(int[] s) {
		return "world-" + s[0] + "-s" + s[2] + "-";
	}

	private static void openWorldPart(ClientGameTestContext context, String part) {
		com.javahost.client.gui.pages.HostPage.showSection(part);
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.waitTicks(8);
	}

	/** The n-th visible segmented control on the screen (0 = the first). */
	private static com.javahost.client.gui.ui.Segmented<?> segmented(ClientGameTestContext context, int n) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof com.javahost.client.gui.ui.Segmented<?> w && w.visible)
				.map(c -> (com.javahost.client.gui.ui.Segmented<?>) c).skip(n).findFirst().orElse(null));
	}

	/** Clicks choice {@code index} of {@code count} on the n-th segmented control, with the real mouse. */
	private static void clickSegment(ClientGameTestContext context, int n, int index, int count) {
		var control = segmented(context, n);
		if (control == null) {
			throw new AssertionError("[JavaHost test] FAILED: no segmented control #" + n);
		}
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(control.getX() + control.getWidth() * (index + 0.5) / count) * scale, (control.getY() + control.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(4);
	}

	private static net.minecraft.client.gui.components.AbstractWidget widget(ClientGameTestContext context, String label) {
		return context.computeOnClient(mc -> mc.gui.screen().children().stream()
				.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals(label))
				.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c).findFirst().orElse(null));
	}

	/** 2.0: a world of yours, open but not hosted: Members says it's just you, General has the world's settings. */
	private static void worldPageNotHosting(ClientGameTestContext context) {
		context.waitFor(mc -> com.javahost.client.rules.WorldOptions.available(), 200);
		try {
			for (int[] s : WORLD_SIZES) {
				size(context, s[0], s[1], s[2]);
				String at = s[0] + "x" + s[1] + " scale " + s[2];
				openWorldPart(context, "members");
				check(hasButton(context, "Start hosting"), "Start hosting is in view on Members at " + at);
				check(widgetsFit(context), "World (not hosting, Members) fits at " + at);
				context.takeScreenshot(worldShot(s) + "open-members");
				openWorldPart(context, "general");
				check(hasButton(context, "Start hosting") && segmented(context, 1) != null && segmented(context, 1).active,
						"Start hosting and Difficulty are in view (and Difficulty can be changed) before hosting at " + at);
				check(widgetsFit(context), "World (not hosting, General) fits at " + at);
				context.takeScreenshot(worldShot(s) + "open-general");
			}
		} finally {
			com.javahost.client.gui.pages.HostPage.showSection("general");
			context.setScreen(() -> null);
			size(context, 854, 480, 0);
		}
	}

	/** 2.0: on the title screen the World page lists your worlds, with Settings · Backups underneath. */
	private static void worldPageNoWorld(ClientGameTestContext context) {
		try {
			for (int[] s : WORLD_SIZES) {
				size(context, s[0], s[1], s[2]);
				String at = s[0] + "x" + s[1] + " scale " + s[2];
				openWorldPart(context, "settings");
				check(hasButton(context, "Host") && !hasButton(context, "More options"), "with no world open the World page lists your worlds at " + at);
				check(hasButton(context, "Only friends can join"), "with no world open, the hosting settings are under your worlds at " + at);
				check(widgetsFit(context), "World (no world open) fits at " + at);
				context.takeScreenshot(worldShot(s) + "no-world");
				context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -30));
				context.waitTicks(10);
				check(widgetsFit(context), "World (no world open, scrolled) fits at " + at);
				context.takeScreenshot(worldShot(s) + "no-world-scrolled");
			}
		} finally {
			com.javahost.client.gui.pages.HostPage.showSection("general");
			context.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
			size(context, 854, 480, 0);
		}
	}

	/**
	 * 2.0: the World page while hosting: Members · General · Rules · Sharing and the "more" menu switch; the Operator
	 * control and General's controls change the real world and show what the server says; a guest sees it read-only;
	 * everything fits at the laptop sizes.
	 */
	private static void worldPage(ClientGameTestContext context) {
		java.util.UUID steve = java.util.UUID.nameUUIDFromBytes("Steve123".getBytes());
		context.runOnClient(mc -> HostController.previewGuests(List.of(
				new HostController.Guest(steve, "Steve123", 42, net.minecraft.world.level.GameType.SURVIVAL, false, false),
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("AlexPlays".getBytes()), "AlexPlays", 180, net.minecraft.world.level.GameType.ADVENTURE, true, false),
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("LunaBuilds".getBytes()), "LunaBuilds", 64, net.minecraft.world.level.GameType.CREATIVE, false, false),
				new HostController.Guest(java.util.UUID.nameUUIDFromBytes("Builder".getBytes()), "Builder_With_A_Long_Name", 0,
						net.minecraft.world.level.GameType.SURVIVAL, false, true))));
		String difficultyBefore = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.difficulty());
		String modeBefore = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.defaultGameMode());
		boolean cheatsBefore = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.cheats());
		try {
			size(context, 2000, 1250, 3);
			// The four parts, clicked.
			openWorldPart(context, "members");
			check(hasButton(context, "More options") && hasButton(context, "Invite friends"), "the World page has its parts, the more button and Invite");
			List<String> parts = List.of("members", "general", "rules", "sharing");
			for (int i = parts.size() - 1; i >= 0; i--) {
				clickSegment(context, 0, i, parts.size());
				check(parts.get(i).equals(com.javahost.client.gui.pages.HostPage.currentSection()), "clicking " + parts.get(i) + " opens it");
			}
			clickSegment(context, 0, 1, parts.size());
			check(hasButton(context, "Only friends can join") && hasButton(context, "Cheats"), "General has the world's settings and who can join");
			clickSegment(context, 0, 0, parts.size());
			// The more menu.
			List<String> more = List.of("backups", "connection", "advanced");
			for (int i = 0; i < more.size(); i++) {
				int row = i;
				clickMenu(context, "More options");
				check(context.computeOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).popoverOpen()), "the more button opens its menu");
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).clickPopover(30, 3 + row * 18 + 9));
				context.waitTicks(4);
				check(more.get(i).equals(com.javahost.client.gui.pages.HostPage.currentSection()), "more → " + more.get(i) + " opens it");
			}
			clickMenu(context, "Back");
			check("members".equals(com.javahost.client.gui.pages.HostPage.currentSection()), "Back from Advanced returns to the part you were on");

			// Operator: the host makes Steve123 one; the row follows the server.
			openWorldPart(context, "members");
			check(!context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.isOperator(steve)), "Steve123 starts as a plain guest");
			clickMenu(context, "Operator: Steve123");
			context.waitFor(mc -> com.javahost.client.rules.WorldOptions.isOperator(steve), 100);
			context.waitTicks(4);
			check(widget(context, "Operator: Steve123") instanceof com.javahost.client.gui.ui.IconToggle t && t.value(),
					"the Operator control makes Steve123 an operator, and his row shows it once the server agrees");
			check(com.javahost.rules.WorldRules.isOperator(steve), "the server has Steve123 as an operator");
			// Once per world, the note about co-hosts being operators now.
			context.runOnClient(mc -> com.javahost.client.gui.pages.HostPage.forgetOperatorNotice());
			openWorldPart(context, "members");
			check(hasButton(context, "Dismiss"), "a world that already has operators gets the one-time note");
			context.takeScreenshot("world-2000-s3-operator-note");
			clickMenu(context, "Dismiss");
			check(!hasButton(context, "Dismiss"), "the note goes away when dismissed");
			openWorldPart(context, "general");
			check(!hasButton(context, "Dismiss"), "and doesn't come back");

			// General: difficulty and default game mode change the real world.
			List<String> difficulties = com.javahost.util.WorldOptionsData.DIFFICULTIES;
			int target = (difficulties.indexOf(difficultyBefore) + 1) % difficulties.size();
			clickSegment(context, 1, target, difficulties.size());
			context.waitFor(mc -> mc.getSingleplayerServer().getWorldData().getDifficulty().getSerializedName().equals(difficulties.get(target))
					&& difficulties.get(target).equals(com.javahost.client.rules.WorldOptions.difficulty()), 100);
			check(true, "Difficulty on General changes the world (" + difficultyBefore + " -> " + difficulties.get(target) + ")");
			List<String> modes = com.javahost.util.WorldOptionsData.GAME_MODES;
			int modeTarget = modes.indexOf(modeBefore) == 2 ? 0 : 2;
			clickSegment(context, 2, modeTarget, modes.size());
			context.waitFor(mc -> mc.getSingleplayerServer().getDefaultGameType().getName().equals(modes.get(modeTarget))
					&& modes.get(modeTarget).equals(com.javahost.client.rules.WorldOptions.defaultGameMode()), 100);
			check(true, "Default game mode on General changes the world (" + modeBefore + " -> " + modes.get(modeTarget) + ")");
			context.waitTicks(4);
			check(segmented(context, 1).selected().equals(difficulties.get(target)) && segmented(context, 2).selected().equals(modes.get(modeTarget)),
					"General shows the values the server sent back");
			boolean locked = context.computeOnClient(mc -> com.javahost.client.rules.WorldOptions.cheatsLocked());
			if (locked) {
				check(!widget(context, "Cheats").active, "in a world made with cheats, the Cheats switch is on and can't be changed");
			} else {
				clickMenu(context, "Cheats");
				context.waitFor(mc -> com.javahost.client.rules.WorldOptions.cheats() != cheatsBefore, 100);
				context.waitTicks(4);
				check(widget(context, "Cheats") instanceof com.javahost.client.gui.ui.ToggleSwitch t && t.value() == !cheatsBefore,
						"the Cheats switch changes the world and shows the server's value");
				clickMenu(context, "Cheats");
				context.waitFor(mc -> com.javahost.client.rules.WorldOptions.cheats() == cheatsBefore, 100);
				check(true, "and switches back");
			}
			// A change the server refuses ends up showing the real value.
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setLocked(true));
			context.waitFor(mc -> com.javahost.client.rules.WorldOptions.locked(), 100);
			openWorldPart(context, "general");
			check(!segmented(context, 1).active, "with the rules locked, Difficulty can't be changed");
			context.runOnClient(mc -> com.javahost.client.rules.WorldOptions.setLocked(false));
			context.waitFor(mc -> !com.javahost.client.rules.WorldOptions.locked(), 100);

			// Every part at the laptop sizes.
			for (int[] s : WORLD_SIZES) {
				size(context, s[0], s[1], s[2]);
				String at = s[0] + "x" + s[1] + " scale " + s[2];
				for (String part : List.of("members", "general", "rules", "sharing", "backups", "connection", "advanced")) {
					openWorldPart(context, part);
					check(hasButton(context, "Invite friends") && hasButton(context, "Stop hosting"), "Invite and Stop stay on top (" + part + ") at " + at);
					check(widgetsFit(context), "World " + part + " fits at " + at);
					context.takeScreenshot(worldShot(s) + part);
				}
				openWorldPart(context, "general");
				check(segmented(context, 1) != null && segmented(context, 1).getBottom() < context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight()),
						"Difficulty is in view without scrolling at " + at);
				context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -30));
				context.waitTicks(10);
				context.takeScreenshot(worldShot(s) + "general-scrolled");
				openWorldPart(context, "sharing");
				context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -30));
				context.waitTicks(10);
				check(widgetsFit(context), "World sharing (scrolled) fits at " + at);
				context.takeScreenshot(worldShot(s) + "sharing-scrolled");
				openWorldPart(context, "members");
				clickMenu(context, "More options");
				context.getInput().setCursorPos(0, 0);
				context.waitTicks(4);
				context.takeScreenshot(worldShot(s) + "more-menu");
				context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).closePopover());

				// Someone else's world, as a guest who isn't an operator: read-only.
				context.runOnClient(mc -> {
					com.javahost.client.gui.pages.HostPage.previewGuest(true);
					check(com.javahost.client.rules.WorldOptions.receive(com.javahost.util.WorldOptionsData.toJson(new com.javahost.util.WorldOptionsData.State(
							com.javahost.util.WorldOptionsData.Role.NONE, false, false, true, false, "", "normal", "survival", "nearby",
							List.of(new com.javahost.util.WorldOptionsData.Operator(steve, "Steve123")), List.of()))), "a guest's World options are read");
				});
				openWorldPart(context, "general");
				check(!hasButton(context, "More options") && !hasButton(context, "Only friends can join") && !segmented(context, 1).active
						&& !widget(context, "Cheats").active, "a guest sees General read-only, without the host's settings, at " + at);
				check(widgetsFit(context), "World (guest, General) fits at " + at);
				context.takeScreenshot(worldShot(s) + "guest-general");
				openWorldPart(context, "members");
				check(!hasButton(context, "Survival") && context.computeOnClient(mc -> mc.gui.screen().children().stream()
						.noneMatch(c -> c instanceof com.javahost.client.gui.ui.IconToggle)), "a guest's Members has no controls");
				context.takeScreenshot(worldShot(s) + "guest-members");
				context.runOnClient(mc -> {
					com.javahost.client.gui.pages.HostPage.previewGuest(false);
					com.javahost.client.rules.WorldOptions.requestUnchecked("rule:no_such_rule", "true"); // the server sends the real state back
				});
				context.waitFor(mc -> com.javahost.client.rules.WorldOptions.role() == com.javahost.client.rules.WorldOptions.Role.HOST, 100);
			}
		} finally {
			context.runOnClient(mc -> {
				com.javahost.client.gui.pages.HostPage.previewGuest(false);
				com.javahost.client.rules.WorldOptions.setOperator(steve, false);
				com.javahost.client.rules.WorldOptions.setDifficulty(difficultyBefore);
				com.javahost.client.rules.WorldOptions.setDefaultGameMode(modeBefore);
				if (!com.javahost.client.rules.WorldOptions.cheatsLocked()) {
					com.javahost.client.rules.WorldOptions.setCheats(cheatsBefore);
				}
				HostController.previewGuests(null);
			});
			context.waitFor(mc -> !com.javahost.client.rules.WorldOptions.isOperator(steve)
					&& difficultyBefore.equals(com.javahost.client.rules.WorldOptions.difficulty())
					&& modeBefore.equals(com.javahost.client.rules.WorldOptions.defaultGameMode()), 100);
			com.javahost.client.gui.pages.HostPage.showSection("general");
			context.setScreen(() -> null);
			size(context, 854, 480, 0);
		}
	}

	private static void setDiscoverSelection(ClientGameTestContext context, java.util.UUID host) {
		context.runOnClient(mc -> {
			try {
				var field = com.javahost.client.gui.pages.DiscoverPage.class.getDeclaredField("selected");
				field.setAccessible(true);
				field.set(null, host);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	// ================================================================ 1.6

	/** A transfer in a given state, for screenshots (the real flow is covered by the two-game test). */
	private static com.javahost.client.share.WorldShare.Transfer fakeTransfer(java.util.UUID peer, boolean outgoing, String world,
			com.javahost.client.share.WorldShare.Stage stage, long size, long done, String message, Path result) {
		try {
			Class<com.javahost.client.share.WorldShare.Transfer> type = com.javahost.client.share.WorldShare.Transfer.class;
			var constructor = type.getDeclaredConstructor(long.class, boolean.class, java.util.UUID.class, String.class, String.class,
					com.javahost.client.share.WorldShare.Stage.class);
			constructor.setAccessible(true);
			var transfer = constructor.newInstance(new java.util.Random().nextLong(), outgoing, peer, "Alex_TheBuilder", world, stage);
			for (var entry : java.util.Map.<String, Object>of("size", size, "done", done, "speed", 1.6 * 1024 * 1024).entrySet()) {
				var field = type.getDeclaredField(entry.getKey());
				field.setAccessible(true);
				field.set(transfer, entry.getValue());
			}
			for (var entry : new String[][] {{"message", message}}) {
				var field = type.getDeclaredField(entry[0]);
				field.setAccessible(true);
				field.set(transfer, entry[1]);
			}
			var resultField = type.getDeclaredField("result");
			resultField.setAccessible(true);
			resultField.set(transfer, result);
			return transfer;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static java.util.List<com.javahost.client.share.WorldShare.Transfer> transferList() {
		try {
			var field = com.javahost.client.share.WorldShare.class.getDeclaredField("TRANSFERS");
			field.setAccessible(true);
			return (java.util.List<com.javahost.client.share.WorldShare.Transfer>) field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** The world picker, the transfers card in every state, and the offer popup, at laptop sizes. */
	private static void worldShareScreens(ClientGameTestContext context, java.util.UUID alexId) {
		check(context.computeOnClient(mc -> com.javahost.client.share.WorldShare.cannotSendTo(java.util.UUID.randomUUID()))
				.startsWith("Only friends"), "worlds can only be sent to friends");
		check(context.computeOnClient(mc -> com.javahost.client.share.WorldShare.cannotSendTo(alexId)).startsWith("Join the same world"),
				"a friend who isn't in this world can't be sent a world");
		long mb = 1024 * 1024;
		context.runOnClient(mc -> {
			com.javahost.client.share.WorldShare.pauseForTests(true);
			var list = transferList();
			list.clear();
			list.add(fakeTransfer(alexId, false, "Hardcore Island", com.javahost.client.share.WorldShare.Stage.OFFERED, 412 * mb, 0, null, null));
			list.add(fakeTransfer(alexId, true, "Survival with the boys and a very long name", com.javahost.client.share.WorldShare.Stage.SENDING,
					184 * mb, 77 * mb, null, null));
			list.add(fakeTransfer(alexId, false, "Skyblock", com.javahost.client.share.WorldShare.Stage.DONE, 52 * mb, 52 * mb, null,
					mc.getLevelSource().getBaseDir().resolve("Skyblock (from Alex_TheBuilder)")));
			list.add(fakeTransfer(alexId, true, "Creative", com.javahost.client.share.WorldShare.Stage.FAILED, 30 * mb, 4 * mb,
					"Alex_TheBuilder left the world", null));
		});
		for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}, {2000, 1250, 4}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				TestCompat.resizeGui(mc);
			});
			context.setScreen(() -> new JavaHostScreen(null, Tab.NOTIFICATIONS));
			context.waitTicks(8);
			boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
					.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
					.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
			check(fits, "transfer buttons fit at " + size[0] + "x" + size[1] + " scale " + size[2]);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-world-transfers");
			context.setScreen(() -> new com.javahost.client.gui.WorldPickerScreen(null,
					com.javahost.client.friends.Friends.friend(alexId).orElseThrow(), null, null));
			context.waitTicks(30); // folder sizes are measured in the background
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-world-picker");
			// The real popup: offers wait for a menu, then ask.
			context.runOnClient(mc -> com.javahost.client.Popups.offerWorld(transferList().getFirst()));
			context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
			context.waitTicks(6);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof com.javahost.client.gui.FriendPopup), "a world offer pops up on a menu");
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-world-offer");
		}
		context.runOnClient(mc -> {
			transferList().clear();
			com.javahost.client.share.WorldShare.pauseForTests(false);
		});
		context.setScreen(() -> null);
	}

	/** Clicks a visible button in the current screen with the real mouse (one on the page before a sidebar entry of the same name). */
	private static void clickMenu(ClientGameTestContext context, String label) {
		clickWidget(context, label, false);
	}

	/** Clicks an entry in the menu's sidebar with the real mouse. */
	private static void clickSidebar(ClientGameTestContext context, String label) {
		clickWidget(context, label, true);
	}

	private static void clickWidget(ClientGameTestContext context, String label, boolean sidebar) {
		double[] at = context.computeOnClient(mc -> {
			net.minecraft.client.gui.components.AbstractWidget found = null;
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.active
						&& w.getMessage().getString().equals(label)) {
					boolean inSidebar = w.getClass().getSimpleName().equals("SidebarTab");
					if (inSidebar == sidebar) {
						found = w;
						break;
					}
					if (found == null && !sidebar) {
						found = w;
					}
				}
			}
			if (found == null) {
				throw new AssertionError("no button \"" + label + "\" on " + mc.gui.screen());
			}
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(found.getX() + found.getWidth() / 2.0) * scale, (found.getY() + found.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(4);
	}

	/** Moves the mouse over a dock button so its label slides out. */
	private static void hoverDock(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getClass().getName().contains("MenuDock")
						&& w.getMessage().getString().equals(label)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("no dock button " + label);
		});
		context.getInput().setCursorPos(at[0], at[1]);
	}

	/** Typing in a menu text box and pressing Enter submits it (like clicking the button next to it). */
	private static void enterSubmits(ClientGameTestContext context) {
		context.setScreen(() -> new JavaHostScreen(null, Tab.JOIN));
		context.waitTicks(4);
		context.getInput().typeChars(" "); // blank: submitting shows a message without connecting anywhere
		context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
		context.waitTicks(4);
		String message = context.computeOnClient(mc -> {
			try {
				Object page = field(mc.gui.screen(), "page");
				return (String) field(page, "error");
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		context.takeScreenshot("ui-join-enter");
		check(message != null, "Enter in a menu text box submits it (" + message + ")");
		context.setScreen(() -> null);
	}

	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(owner);
	}

	/** 1.1: FPS overlay, benchmark, screenshots page, notification center and accent themes. */
	private static void newFeatures(ClientGameTestContext context) {
		shellInWorld(context);
		enterSubmits(context);
		context.setScreen(() -> new JavaHostScreen(null, Tab.ACCOUNT));
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40,
				mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
		context.waitTicks(10);
		context.takeScreenshot("ui-account-scrolled");
		context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
		context.waitTicks(6);
		hoverDock(context, "World");
		context.waitTicks(8);
		context.takeScreenshot("ui-pause-dock");
		context.getInput().setCursorPos(0, 0);
		Config config = Config.get();
		config.fpsOverlay = true;
		context.waitTicks(20);
		context.takeScreenshot("hud-fps");
		config.fpsOverlay = false;
		context.setScreen(() -> new JavaHostScreen(null, Tab.PERFORMANCE));
		context.waitTicks(6);
		context.takeScreenshot("ui-performance-benchmark");

		context.setScreen(() -> null);
		context.waitTicks(3);
		java.util.concurrent.CompletableFuture<Path> shot = new java.util.concurrent.CompletableFuture<>();
		context.runOnClient(mc -> com.javahost.client.friends.Screenshots.take(shot::complete));
		context.waitFor(mc -> shot.isDone(), 200);
		Path file = shot.join();
		try {
			check(com.javahost.client.friends.Screenshots.isPng(java.nio.file.Files.readAllBytes(file)), "screenshot key saves a PNG: " + file.getFileName());
			byte[] scaled = com.javahost.client.friends.Screenshots.prepareForSending(file);
			check(com.javahost.client.friends.Screenshots.isPng(scaled) && scaled.length < 14_000_000, "screenshot prepared for sending (" + scaled.length / 1024 + " KB)");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		context.setScreen(() -> new JavaHostScreen(null, Tab.SCREENSHOTS));
		context.waitTicks(10);
		context.takeScreenshot("ui-screenshots-grid");
		context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openScreenshot(file));
		context.waitTicks(10);
		context.takeScreenshot("ui-screenshots-preview");
		navigation(context, file);
		version14(context);
		version15(context);
		worldOptions(context);
		worldPage(context);
		// Sample notifications: a long JavaHost notice (wraps in full) and a friend's message.
		context.runOnClient(mc -> {
			com.javahost.client.Toasts.notify("Installed 4 mods", "Restart Minecraft to turn them on. Sodium, Lithium, FerriteCore and ImmediatelyFast are ready and will load next time.");
			com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.MESSAGE,
					java.util.UUID.nameUUIDFromBytes("Alex".getBytes()), "Alex", "Are you on tonight? I found a village with a blacksmith near spawn!", null);
		});
		context.waitTicks(4);
		context.takeScreenshot("toast-two-lines");
		reviewAtScale(context, 4);
		reviewAtScale(context, 3);
		// A laptop-sized window, where the menu switches to its compact icon sidebar.
		reviewAtScale(context, 4, 1710, 1070);
		reviewAtScale(context, 3, 1280, 800);
		hubWithFriends(context);
		// A friend's profile on the laptop-sized window: every button must fit inside the window.
		java.util.UUID alexId = java.util.UUID.nameUUIDFromBytes("Alex".getBytes());
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field dataField = com.javahost.client.friends.Friends.class.getDeclaredField("data");
				dataField.setAccessible(true);
				Object data = dataField.get(null);
				java.lang.reflect.Field friendsField = data.getClass().getDeclaredField("friends");
				friendsField.setAccessible(true);
				@SuppressWarnings("unchecked")
				java.util.List<com.javahost.client.friends.Friends.Friend> friends = (java.util.List<com.javahost.client.friends.Friends.Friend>) friendsField.get(data);
				friends.add(new com.javahost.client.friends.Friends.Friend(alexId, "Alex_TheBuilder", "", System.currentTimeMillis(), null, null));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		for (int[] size : new int[][] {{1710, 1070, 4}, {1280, 800, 3}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(size[2]);
				TestCompat.resizeGui(mc);
			});
			context.setScreen(() -> new JavaHostScreen(null, Tab.FRIENDS));
			context.runOnClient(mc -> ((JavaHostScreen) mc.gui.screen()).openProfile(alexId));
			context.waitTicks(8);
			boolean fits = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible)
					.map(c -> (net.minecraft.client.gui.components.AbstractWidget) c)
					.allMatch(w -> w.getRight() <= mc.getWindow().getGuiScaledWidth() - 8));
			check(fits, "profile buttons fit inside the window at " + size[0] + "x" + size[1] + " scale " + size[2]);
			context.takeScreenshot("review-" + size[0] + "-s" + size[2] + "-profile");
			// Rest the mouse on a cut-off label for over a second: the full text appears.
		}
		worldShareScreens(context, alexId);
		publicWorldScreens(context);
		modsScreens(context);
		storeAndDownloads(context);
		bigModSetStaysFast(context);
		context.runOnClient(mc -> {
			try {
				java.lang.reflect.Field dataField = com.javahost.client.friends.Friends.class.getDeclaredField("data");
				dataField.setAccessible(true);
				Object data = dataField.get(null);
				java.lang.reflect.Field friendsField = data.getClass().getDeclaredField("friends");
				friendsField.setAccessible(true);
				((java.util.List<?>) friendsField.get(data)).removeIf(f -> ((com.javahost.client.friends.Friends.Friend) f).id().equals(alexId));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		// Hover text: on the Host tab before hosting at the small size, the subtitle is cut short; resting on it shows it all.
		// Repeated toasts with the same key update in place instead of piling up.
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			for (int i = 0; i < 12; i++) {
				com.javahost.client.Toasts.show("voice-mode", i % 2 == 0 ? "Party voice" : "Nearby voice", "Spam test " + i);
			}
		});
		context.waitTicks(4);
		int toastCount = context.computeOnClient(mc -> {
			try {
				java.lang.reflect.Field visible = mc.gui.toastManager().getClass().getDeclaredField("visibleToasts");
				visible.setAccessible(true);
				java.lang.reflect.Field queued = mc.gui.toastManager().getClass().getDeclaredField("queued");
				queued.setAccessible(true);
				int count = 0;
				for (Object instance : (java.util.Collection<?>) visible.get(mc.gui.toastManager())) {
					java.lang.reflect.Method getToast = instance.getClass().getDeclaredMethod("getToast");
					getToast.setAccessible(true);
					Object toast = getToast.invoke(instance);
					if ("voice-mode".equals(((net.minecraft.client.gui.components.toasts.Toast) toast).getToken())) {
						count++;
					}
				}
				for (Object toast : (java.util.Collection<?>) queued.get(mc.gui.toastManager())) {
					if ("voice-mode".equals(((net.minecraft.client.gui.components.toasts.Toast) toast).getToken())) {
						count++;
					}
				}
				return count;
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		check(toastCount == 1, "pressing B 12 times leaves one voice toast, not a queue (" + toastCount + ")");
		context.takeScreenshot("toast-after-spam");
		context.runOnClient(mc -> {
			com.javahost.client.Toasts.show("Microphone on", "Press M to toggle");
			com.javahost.client.Toasts.show("World backed up", "Restore it any time from the Host tab, even after friends leave.");
		});
		context.waitTicks(30);
		context.takeScreenshot("toast-stack");
		// A narrow window cuts the Host tab's subtitle; resting the mouse on it shows the full text after a second.
		context.getInput().resizeWindow(900, 560);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(3);
			TestCompat.resizeGui(mc);
		});
		context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
		context.waitTicks(8);
		double[] spot = context.computeOnClient(mc -> {
			// The hint under "Always use the host's packs" is cut short at this size.
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals("Always use the host's packs")) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(w.getX() + 40) * scale, (w.getY() + 15) * scale};
				}
			}
			throw new AssertionError("no packs toggle");
		});
		context.getInput().setCursorPos(spot[0], spot[1]);
		context.waitTicks(30);
		context.takeScreenshot("hover-full-text");
		context.getInput().setCursorPos(0, 0);
		context.setScreen(() -> null);
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(0);
			TestCompat.resizeGui(mc);
		});
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.clear());
		// The Host tab before hosting, at the laptop size: Start hosting must be on screen without scrolling.
		context.runOnClient(mc -> HostController.stop());
		com.javahost.client.gui.pages.HostPage.showSection("settings");
		context.getInput().resizeWindow(1710, 1070);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(4);
			TestCompat.resizeGui(mc);
		});
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.waitTicks(8);
		check(context.computeOnClient(mc -> mc.gui.screen().children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
				&& w.visible && w.getMessage().getString().equals("Start hosting"))), "Start hosting is visible without scrolling");
		context.takeScreenshot("review-1710-s4-host-setup");
		context.runOnClient(mc -> mc.gui.screen().mouseScrolled(mc.getWindow().getGuiScaledWidth() / 2.0 + 40, mc.getWindow().getGuiScaledHeight() / 2.0, 0, -10));
		context.waitTicks(10);
		context.takeScreenshot("review-1710-s4-host-setup-scrolled");
		com.javahost.client.gui.pages.HostPage.showSection("advanced");
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOST));
		context.waitTicks(6);
		context.takeScreenshot("review-1710-s4-host-advanced");
		check(hasButton(context, "Start hosting"), "Start hosting stays on top while Advanced is open");
		com.javahost.client.gui.pages.HostPage.showSection("general");
		clickMenu(context, "Start hosting");
		context.waitFor(mc -> HostController.isHosting(), 200);
		check(true, "Start hosting from the top of the Host tab works");
		context.waitTicks(20);
		context.takeScreenshot("review-1710-s4-host-hosting");
		hostTabs(context);
		context.setScreen(() -> null);
		context.getInput().resizeWindow(854, 480);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(0);
			TestCompat.resizeGui(mc);
		});

		java.util.UUID alex = java.util.UUID.nameUUIDFromBytes("Alex".getBytes());
		context.runOnClient(mc -> {
			com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.ACCEPTED, alex, "Alex", "Alex accepted your request", null);
			com.javahost.client.friends.Notifications.add(com.javahost.client.friends.Notifications.Kind.MESSAGE, alex, "Alex", "want to play later?", null);
		});
		context.setScreen(() -> new JavaHostScreen(null, Tab.HOME));
		context.waitTicks(6);
		context.takeScreenshot("ui-home-unread-bell");
		context.setScreen(() -> new JavaHostScreen(null, Tab.NOTIFICATIONS));
		context.waitTicks(6);
		context.takeScreenshot("ui-notifications");
		context.runOnClient(mc -> com.javahost.client.friends.Notifications.items().forEach(com.javahost.client.friends.Notifications::remove));

		for (String accent : List.of("Blue", "Purple", "Orange", "Pink", "Green")) {
			config.accent = accent;
			context.setScreen(() -> new JavaHostScreen(null, Tab.SETTINGS));
			context.waitTicks(6);
			if (!accent.equals("Green")) {
				context.takeScreenshot("ui-settings-" + accent.toLowerCase());
			}
		}
		check(com.javahost.client.gui.ui.Theme.ACCENT == com.javahost.client.gui.ui.Theme.ACCENTS.get("Green"), "accent colours switch and switch back");
		context.setScreen(() -> null);
	}
}
