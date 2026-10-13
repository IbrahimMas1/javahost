package com.javahost.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.javahost.JavaHost;
import com.javahost.client.friends.Friends;
import com.javahost.client.friends.Identity;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.HostController;
import com.javahost.client.update.UpdateChecker;
import com.javahost.client.update.Updater;
import com.javahost.util.DebugMask;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * A short report for asking for help: versions, mods, connection state and recent JavaHost log lines. IP addresses,
 * invite codes, relay addresses and player names are removed first.
 */
public final class DebugReport {
	private DebugReport() {
	}

	public static String build() {
		StringBuilder r = new StringBuilder("JavaHost debug report\n");
		r.append("JavaHost ").append(JavaHost.VERSION).append(" (protocol ").append(JavaHost.PROTOCOL).append(")\n");
		r.append("Minecraft ").append(version("minecraft")).append(", Fabric Loader ").append(version("fabricloader"))
				.append(", Fabric API ").append(version("fabric-api")).append('\n');
		r.append("Java ").append(System.getProperty("java.version")).append(", ").append(System.getProperty("os.name")).append(' ')
				.append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
		r.append("Mods: ").append(FabricLoader.getInstance().getAllMods().stream()
				.map(m -> m.getMetadata().getId() + " " + m.getMetadata().getVersion().getFriendlyString())
				.filter(s -> !s.startsWith("fabric-") || s.startsWith("fabric-api "))
				.sorted().collect(Collectors.joining(", "))).append('\n');
		r.append("Friends service: ").append(Friends.status()).append(", ").append(Friends.friends().size()).append(" friends\n");
		r.append("Hosting: ").append(HostController.isHosting()).append('\n');
		for (Connectivity.Route route : Connectivity.routes()) {
			r.append("  route ").append(route.name()).append(": ").append(route.state()).append(" - ").append(route.detail()).append('\n');
		}
		var update = UpdateChecker.available();
		r.append("Update: ").append(Updater.staged() ? "installs on close" : update != null ? update.version() + " available" : "none found").append('\n');
		r.append("\nRecent JavaHost log lines:\n");
		for (String line : recentLog(50)) {
			r.append(line).append('\n');
		}
		List<String> names = new ArrayList<>();
		names.add(Identity.name());
		Friends.friends().forEach(f -> names.add(f.name()));
		return DebugMask.mask(r.toString(), names);
	}

	private static String version(String mod) {
		return FabricLoader.getInstance().getModContainer(mod).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("not installed");
	}

	private static List<String> recentLog(int max) {
		Path log = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("latest.log");
		try {
			List<String> lines = Files.readAllLines(log).stream()
					.filter(l -> l.contains("(JavaHost)") || l.contains("[Friends]") || l.contains("[Update]") || l.contains("[Backups]")
							|| (l.contains("ERROR") && l.toLowerCase().contains("javahost")))
					.toList();
			return lines.subList(Math.max(0, lines.size() - max), lines.size());
		} catch (Exception e) {
			return List.of("(couldn't read the log: " + e.getMessage() + ")");
		}
	}
}
