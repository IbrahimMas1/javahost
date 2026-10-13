package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ListingTextTest {
	@Test
	void listingsAreCleanAndNeverCarryAnAddress() {
		assertNull(ListingText.problem("Help me dig a big hole", "Bring shovels"));
		assertNotNull(ListingText.problem("x", ""));
		assertNotNull(ListingText.problem("Join us", "discord.gg/abc"));
		assertNotNull(ListingText.problem("Join us", "www.example.com"));
		assertNotNull(ListingText.problem("Join us", "at 203.0.113.7:25565"));
		assertNotNull(ListingText.problem("Join us", "[2001:db8::7]"));
		assertNull(ListingText.problem("Build at 12:30", "starts 7:45 pm"), "times aren't addresses");
		assertEquals("Nice world", ListingText.clean("§kNice§r   world\u0000", 40));
		assertEquals(40, ListingText.clean("x".repeat(100), 40).length());
		assertTrue(ListingText.isRelayAddress("brave-otter.eu.e4mc.link"));
		assertFalse(ListingText.isRelayAddress("203.0.113.7:25565"));
		assertFalse(ListingText.isRelayAddress("brave-otter.eu.e4mc.link.evil.com"));
		assertEquals("EU", ListingText.region("brave-otter.eu.e4mc.link"));
	}
}
