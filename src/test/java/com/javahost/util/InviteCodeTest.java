package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.javahost.net.InviteCode;

class InviteCodeTest {
	@Test
	void ipv4AndIpv6RoundTrip() {
		assertEquals("203.0.113.42:25565", InviteCode.decode(InviteCode.encode("203.0.113.42", 25565)));
		String v6 = InviteCode.encode("2001:db8:4226:11f7:c85f:a6d5:e3af:1410", 25599);
		assertEquals("[2001:db8:4226:11f7:c85f:a6d5:e3af:1410]:25599", InviteCode.decode(v6));
		assertEquals("cool-name.de.e4mc.link", InviteCode.decode("cool-name.de.e4mc.link"));
		assertNull(InviteCode.encode("example.com", 1));
	}
}
