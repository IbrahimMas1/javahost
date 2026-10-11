package com.javahost.client.pack;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;

import com.javahost.JavaHost;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Talks to Iris (the shader mod) by reflection, so JavaHost works with or without it installed.
 * Shader sharing simply turns itself off when Iris is missing.
 */
public final class IrisCompat {
	private static final String IRIS = "net.irisshaders.iris.Iris";

	private IrisCompat() {
	}

	public static boolean isInstalled() {
		return FabricLoader.getInstance().isModLoaded("iris");
	}

	public static Path shaderpacksDirectory() {
		if (!isInstalled()) {
			return null;
		}
		try {
			return (Path) callStatic("getShaderpacksDirectory");
		} catch (RuntimeException e) {
			JavaHost.LOGGER.warn("Could not find the Iris shaderpacks folder", e);
			return null;
		}
	}

	/** File/folder name of the shader pack in use, or empty if shaders are off. */
	@SuppressWarnings("unchecked")
	public static Optional<String> activeShaderPack() {
		if (!isInstalled()) {
			return Optional.empty();
		}
		try {
			Object config = callStatic("getIrisConfig");
			boolean enabled = (boolean) config.getClass().getMethod("areShadersEnabled").invoke(config);
			if (!enabled) {
				return Optional.empty();
			}
			return (Optional<String>) config.getClass().getMethod("getShaderPackName").invoke(config);
		} catch (ReflectiveOperationException | RuntimeException e) {
			JavaHost.LOGGER.warn("Could not read Iris settings", e);
			return Optional.empty();
		}
	}

	/** Switches Iris to {@code packName} (or turns shaders off when {@code null}) and reloads. Render thread only. */
	public static boolean apply(String packName) {
		if (!isInstalled()) {
			return false;
		}
		try {
			Object config = callStatic("getIrisConfig");
			Class<?> cls = config.getClass();
			if (packName != null) {
				cls.getMethod("setShaderPackName", String.class).invoke(config, packName);
			}
			cls.getMethod("setShadersEnabled", boolean.class).invoke(config, packName != null);
			cls.getMethod("save").invoke(config);
			Class.forName(IRIS).getMethod("reload").invoke(null);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			JavaHost.LOGGER.warn("Could not switch Iris shader pack", e);
			return false;
		}
	}

	private static Object callStatic(String name) {
		try {
			Method method = Class.forName(IRIS).getMethod(name);
			return method.invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Iris API changed: " + name, e);
		}
	}
}
