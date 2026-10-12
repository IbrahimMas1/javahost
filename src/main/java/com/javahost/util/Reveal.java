package com.javahost.util;

import java.nio.file.Path;
import java.util.List;

/** The command that opens the system file browser with one file selected ("Show in Finder/Explorer"). */
public final class Reveal {
	private Reveal() {
	}

	/** Null when the system has no "select this file" command (then just open the folder). */
	public static List<String> command(String osName, Path file) {
		String os = osName.toLowerCase();
		String path = file.toAbsolutePath().toString();
		if (os.contains("mac")) {
			return List.of("open", "-R", path);
		}
		if (os.contains("win")) {
			return List.of("explorer.exe", "/select," + path);
		}
		return null;
	}
}
