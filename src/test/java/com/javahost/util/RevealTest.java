package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class RevealTest {
	@Test
	void selectsTheFileInFinderOrExplorer() {
		Path shot = Path.of("/Users/alex/My Game/screenshots/shot 1.png");
		assertEquals(List.of("open", "-R", shot.toAbsolutePath().toString()), Reveal.command("Mac OS X", shot));
		// Explorer wants "/select," and the path as one argument; spaces are fine because no shell is involved.
		assertEquals(List.of("explorer.exe", "/select," + shot.toAbsolutePath()), Reveal.command("Windows 11", shot));
		assertNull(Reveal.command("Linux", shot));
	}
}
