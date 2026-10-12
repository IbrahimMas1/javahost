package com.javahost.client.friends;

import java.time.LocalDate;
import java.time.ZoneOffset;

import com.javahost.client.Config;
import com.javahost.util.Mailbox;
import net.minecraft.client.Minecraft;

/**
 * Counts friend messages sent today. The free relay (ntfy.sh) accepts about 250 messages a day from one internet
 * connection; receiving doesn't count. Shown on the Account page so the limit is never a surprise.
 */
public final class MessageUsage {
	public static final int DAILY_LIMIT = 250;

	private MessageUsage() {
	}

	public static void install() {
		Mailbox.onSent = () -> Minecraft.getInstance().execute(MessageUsage::record);
	}

	private static String today() {
		return LocalDate.now(ZoneOffset.UTC).toString();
	}

	private static void record() {
		Config config = Config.get();
		if (!today().equals(config.usageDay)) {
			config.usageDay = today();
			config.usageCount = 0;
		}
		config.usageCount++;
		config.save();
	}

	public static int sentToday() {
		Config config = Config.get();
		return today().equals(config.usageDay) ? config.usageCount : 0;
	}
}
