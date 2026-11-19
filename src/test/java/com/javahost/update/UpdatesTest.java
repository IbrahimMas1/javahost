package com.javahost.update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The facade in front of the self-updater, which CurseForge and Modrinth copies don't have. */
class UpdatesTest {
	/** A loader that can't see the updater, like the game's loader with a CurseForge/Modrinth jar. */
	private static final ClassLoader WITHOUT_UPDATER = new ClassLoader(UpdatesTest.class.getClassLoader()) {
		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("com.javahost.client.update.")) {
				throw new ClassNotFoundException(name);
			}
			return super.loadClass(name, resolve);
		}
	};

	/** Stands in for an updater whose answers we know. */
	public static final class FakeUpdater implements UpdateService {
		@Override
		public String availableVersion() {
			return "9.9.9";
		}

		@Override
		public boolean staged() {
			return true;
		}
	}

	public static final class NotAnUpdater {
	}

	@AfterEach
	void clearProperties() {
		System.clearProperty("fabric.development");
		System.clearProperty("javahost.test.platform");
		System.clearProperty("javahost.test.noSelfUpdate");
		Updates.use(null);
	}

	@Test
	void withTheUpdaterTheFacadeAsksIt() {
		Updates.use(new FakeUpdater());
		assertTrue(Updates.present());
		assertTrue(Updates.staged());
		assertTrue("9.9.9".equals(Updates.availableVersion()));
		// Even with the updater in the jar, the CurseForge/Modrinth marker wins.
		System.setProperty("fabric.development", "true");
		System.setProperty("javahost.test.platform", "modrinth");
		assertFalse(Updates.present());
		assertFalse(Updates.staged());
		assertNull(Updates.availableVersion());
	}

	@Test
	void withoutTheUpdaterEverythingIsANoOp() {
		// Like a CurseForge/Modrinth jar: the implementation class simply isn't there.
		Updates.use(Updates.load(Updates.IMPLEMENTATION, WITHOUT_UPDATER));
		assertFalse(Updates.present());
		assertNull(Updates.availableVersion());
		assertFalse(Updates.important());
		assertFalse(Updates.checking());
		assertFalse(Updates.staged());
		assertNull(Updates.stagedKind());
		assertNull(Updates.stagedVersion());
		assertNull(Updates.previousVersion());
		assertNull(Updates.rollBackBlockedReason());
		assertFalse(Updates.selfUpdates());
		assertDoesNotThrow(() -> {
			Updates.start();
			Updates.tick();
			Updates.launchOnExit();
			Updates.openUpdateScreen();
			Updates.cancelStaged();
		});
		AtomicBoolean done = new AtomicBoolean();
		Updates.checkNow(() -> done.set(true));
		assertTrue(done.get(), "a check with nothing to check still says it's done");
		assertThrows(IOException.class, Updates::stageRollBack, "there's nothing to go back to");
	}

	@Test
	void theUpdaterIsFoundByNameWhenItsThere() {
		// The real one (the unit tests can see the client classes; its methods need a running game, so only load it).
		assertEquals("com.javahost.client.update.SelfUpdater", Updates.load(Updates.IMPLEMENTATION, getClass().getClassLoader()).getClass().getName());
		assertTrue(Updates.present(), "the GitHub build has it");
		UpdateService service = Updates.load(FakeUpdater.class.getName(), getClass().getClassLoader());
		assertInstanceOf(FakeUpdater.class, service);
		assertTrue(service.staged());
	}

	@Test
	void aMissingOrWrongClassMeansNoUpdater() {
		ClassLoader loader = getClass().getClassLoader();
		assertSame(Updates.OFF, Updates.load(Updates.IMPLEMENTATION, WITHOUT_UPDATER), "not in the jar, as in a site copy");
		assertSame(Updates.OFF, Updates.load("com.javahost.nothing.Here", loader));
		assertSame(Updates.OFF, Updates.load(NotAnUpdater.class.getName(), loader));
		// An isolated loader that can't see the test classes either.
		assertSame(Updates.OFF, Updates.load(FakeUpdater.class.getName(), ClassLoader.getPlatformClassLoader()));
	}

	@Test
	void theMarkerAloneTurnsItOff() {
		// Development runs can pretend to be a mod-site copy (Distribution reads javahost.test.platform).
		System.setProperty("fabric.development", "true");
		System.setProperty("javahost.test.platform", "curseforge");
		assertSame(Updates.OFF, Updates.service());
		assertFalse(Updates.present());
	}

	@Test
	void developmentCanPretendTheUpdaterIsMissing() {
		System.setProperty("javahost.test.noSelfUpdate", "true");
		assertTrue(Updates.present(), "outside development the setting is ignored");
		System.setProperty("fabric.development", "true");
		assertSame(Updates.OFF, Updates.service());
	}
}
