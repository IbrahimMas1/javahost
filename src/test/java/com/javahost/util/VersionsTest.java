package com.javahost.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionsTest {
	@Test
	void ordersVersionsNumerically() {
		assertTrue(Versions.compare("1.3.1", "1.4.0") < 0);
		assertTrue(Versions.compare("1.10.0", "1.9.9") > 0); // not alphabetical
		assertEquals(0, Versions.compare("1.4", "1.4.0"));
		assertTrue(Versions.compare("2.0.0", "1.99.99") > 0);
		assertEquals(0, Versions.compare("garbage", "0"));
	}
}
