package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class DebugMaskTest {
	@Test
	void hidesAddressesCodesAndNames() {
		String log = "Joined AlexPlays's world via 192.0.2.10:25565, sample-name.de.e4mc.link and [2001:db8:4271:1fa4:34c5:c50:3dc3:5bf2]:25599;"
				+ " invite JH-6B01R-GERYX / JH6-2008D-Q1172-7X46K-2GRM1-XRDDZ-4RZZ; me: Steve123; version 1.5.0";
		assertEquals("Joined friend-1's world via [ip]:25565, [relay] and [ip]:25599; invite [invite] / [invite]; me: you; version 1.5.0",
				DebugMask.mask(log, List.of("Steve123", "AlexPlays")));
	}
}
