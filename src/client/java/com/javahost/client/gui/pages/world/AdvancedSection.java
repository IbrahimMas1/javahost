package com.javahost.client.gui.pages.world;

import java.util.List;

import com.javahost.client.Config;
import com.javahost.client.gui.JavaHostScreen;
import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Segmented;
import com.javahost.client.gui.ui.TextField;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.gui.ui.ToggleSwitch;
import com.javahost.client.host.E4mcCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** How hosting connects: the game port, UPnP, IPv6 and the relay. While hosting they wait for the next time. */
public final class AdvancedSection extends WorldSection {
	/** The port as typed, until hosting starts (null: not touched). */
	private static String typedPort;

	private boolean hosting;
	private int cardY;
	private int cardH;
	private int upnpY;
	private int relayY;
	private int noteY = -1;

	public AdvancedSection(Host host) {
		super(host);
	}

	public void configure(boolean hosting) {
		this.hosting = hosting;
	}

	public static String typedPort() {
		return typedPort;
	}

	public static void forgetTypedPort() {
		typedPort = null;
	}

	@Override
	protected int layout(int y) {
		Config config = Config.get();
		int inner = width - 2 * PAD;
		boolean editable = !hosting;
		cardY = y;
		int top = y + LABEL;
		if (editable) {
			TextField port = add(new TextField(70, Component.literal("Port")), width - 70 - PAD, top + 8);
			port.setMaxLength(5);
			port.setValue(typedPort != null ? typedPort : String.valueOf(config.port));
			port.setResponder(text -> typedPort = text);
		}
		upnpY = top + 36;
		ToggleSwitch upnp = add(new ToggleSwitch(inner, Component.literal("Open router port automatically"), "No router setup needed (UPnP)",
				config.useUpnp, v -> {
					config.useUpnp = v;
					config.save();
				}), PAD, upnpY + 8);
		ToggleSwitch ipv6 = add(new ToggleSwitch(inner, Component.literal("Allow IPv6 connections"), "Direct, and works behind CGNAT",
				config.useIpv6, v -> {
					config.useIpv6 = v;
					config.save();
				}), PAD, upnpY + 48);
		upnp.active = editable;
		ipv6.active = editable;
		relayY = upnpY + 80;
		if (E4mcCompat.isInstalled()) {
			Segmented<String> relay = add(new Segmented<>(inner, List.of("AUTO", "ALWAYS", "NEVER"), config.relayMode,
					mode -> switch (mode) {
						case "ALWAYS" -> "Always";
						case "NEVER" -> "Never";
						default -> "When needed";
					}, mode -> {
						config.relayMode = mode;
						config.save();
					}), PAD, relayY + 22);
			relay.active = editable;
		} else {
			add(new FlatButton(90, 18, Component.literal("Get relay"), FlatButton.Style.SECONDARY, Icons.CLOUD,
					b -> screen.switchTo(JavaHostScreen.Tab.PERFORMANCE)), width - 90 - PAD, relayY + 9);
		}
		cardH = relayY + (E4mcCompat.isInstalled() ? 48 : 36) - top;
		y = top + cardH;
		noteY = -1;
		if (hosting) {
			noteY = y + 8;
			y = noteY + 10;
		}
		return y;
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int y = top + cardY;
		label(g, "Advanced", left, y);
		card(g, left, y + LABEL, width, cardH);
		int c = y + LABEL;
		Draw.text(g, "Game port", left + PAD, c + 10, Theme.TEXT);
		Draw.text(g, "1024 to 65535", left + PAD, c + 21, Theme.FAINT);
		if (hosting) {
			String port = String.valueOf(Config.get().port);
			Draw.text(g, port, left + width - PAD - Draw.width(port), c + 14, Theme.MUTED);
		}
		divider(g, left, top + upnpY, width);
		divider(g, left, top + upnpY + 40, width);
		divider(g, left, top + relayY, width);
		int r = top + relayY;
		if (E4mcCompat.isInstalled()) {
			Draw.textFit(g, "Relay, for friends who can't connect directly", width - 2 * PAD, left + PAD, r + 10, Theme.TEXT);
		} else {
			Draw.textFit(g, "Relay", width - 2 * PAD - 100, left + PAD, r + 9, Theme.TEXT);
			Draw.textFit(g, "Not installed", width - 2 * PAD - 100, left + PAD, r + 20, Theme.FAINT);
		}
		if (noteY >= 0) {
			note(g, Icons.GEAR, "These apply next time you host.", left + 2, top + noteY, width - 2, Theme.FAINT);
		}
	}
}
