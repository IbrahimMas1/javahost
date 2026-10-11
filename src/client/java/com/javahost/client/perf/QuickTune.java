package com.javahost.client.perf;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.javahost.client.Config;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.server.level.ParticleStatus;

/**
 * One-click video settings tuned for frame rate, keeping the look close to vanilla. The previous
 * values are saved first, so "Restore" puts everything back exactly as it was.
 */
public final class QuickTune {
	private QuickTune() {
	}

	private record Tweak<T>(String key, Function<Options, OptionInstance<T>> option, Function<T, T> tuned) {
	}

	private static Map<String, Tweak<?>> tweaks() {
		Map<String, Tweak<?>> map = new LinkedHashMap<>();
		// The preset comes first: restoring it re-applies the preset, then the individual values follow.
		add(map, new Tweak<>("graphicsPreset", Options::graphicsPreset, current -> current));
		add(map, new Tweak<>("renderDistance", Options::renderDistance, current -> Math.min(current, 12)));
		add(map, new Tweak<>("simulationDistance", Options::simulationDistance, current -> Math.min(current, 8)));
		add(map, new Tweak<>("cloudStatus", Options::cloudStatus, current -> CloudStatus.OFF));
		add(map, new Tweak<>("entityShadows", Options::entityShadows, current -> false));
		add(map, new Tweak<>("particles", Options::particles,
				current -> current == ParticleStatus.MINIMAL ? current : ParticleStatus.DECREASED));
		add(map, new Tweak<>("biomeBlendRadius", Options::biomeBlendRadius, current -> Math.min(current, 1)));
		add(map, new Tweak<>("improvedTransparency", Options::improvedTransparency, current -> false));
		add(map, new Tweak<>("chunkSectionFadeInTime", Options::chunkSectionFadeInTime, current -> 0.0));
		add(map, new Tweak<>("prioritizeChunkUpdates", Options::prioritizeChunkUpdates, current -> PrioritizeChunkUpdates.NONE));
		add(map, new Tweak<>("entityDistanceScaling", Options::entityDistanceScaling, current -> Math.min(current, 0.75)));
		add(map, new Tweak<>("enableVsync", Options::enableVsync, current -> false));
		add(map, new Tweak<>("framerateLimit", Options::framerateLimit, current -> 260));
		return map;
	}

	private static void add(Map<String, Tweak<?>> map, Tweak<?> tweak) {
		map.put(tweak.key(), tweak);
	}

	public static boolean isApplied() {
		return !Config.get().settingsBackup.isEmpty();
	}

	public static void apply() {
		Options options = Minecraft.getInstance().options;
		Config config = Config.get();
		boolean firstTime = config.settingsBackup.isEmpty();
		for (Tweak<?> tweak : tweaks().values()) {
			if (firstTime) {
				config.settingsBackup.put(tweak.key(), encode(options, tweak));
			}
			tune(options, tweak);
		}
		options.save();
		config.save();
	}

	public static void restore() {
		Options options = Minecraft.getInstance().options;
		Config config = Config.get();
		// Walk our own list rather than the saved map so the preset is always restored first.
		for (Tweak<?> tweak : tweaks().values()) {
			String saved = config.settingsBackup.get(tweak.key());
			if (saved != null) {
				decodeInto(options, tweak, saved);
			}
		}
		config.settingsBackup.clear();
		options.save();
		config.save();
	}

	private static <T> void tune(Options options, Tweak<T> tweak) {
		OptionInstance<T> option = tweak.option().apply(options);
		option.set(tweak.tuned().apply(option.get()));
	}

	private static <T> String encode(Options options, Tweak<T> tweak) {
		OptionInstance<T> option = tweak.option().apply(options);
		return option.codec().encodeStart(JsonOps.INSTANCE, option.get()).result().map(JsonElement::toString).orElse("");
	}

	private static <T> void decodeInto(Options options, Tweak<T> tweak, String json) {
		if (json.isEmpty()) {
			return;
		}
		OptionInstance<T> option = tweak.option().apply(options);
		option.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().ifPresent(option::set);
	}
}
