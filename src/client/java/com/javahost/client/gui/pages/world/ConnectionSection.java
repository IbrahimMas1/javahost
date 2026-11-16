package com.javahost.client.gui.pages.world;

import java.util.List;

import com.javahost.client.gui.ui.Draw;
import com.javahost.client.gui.ui.FlatButton;
import com.javahost.client.gui.ui.Icons;
import com.javahost.client.gui.ui.Theme;
import com.javahost.client.host.Connectivity;
import com.javahost.client.host.Connectivity.Route;
import com.javahost.client.host.Connectivity.State;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Every way friends can reach the hosted world, its state and its address (with copy). Invites pick the best one. */
public final class ConnectionSection extends WorldSection {
	private static final int ROW = 22;

	private final HeroSection hero;
	private boolean hosting;
	private List<Route> routes = List.of();
	private int cardY;

	public ConnectionSection(Host host, HeroSection hero) {
		super(host);
		this.hero = hero;
	}

	public void configure(boolean hosting) {
		this.hosting = hosting;
	}

	@Override
	protected int layout(int y) {
		cardY = y;
		routes = hosting ? Connectivity.routes() : List.of();
		int rowY = y + LABEL + 2;
		for (Route route : routes) {
			if (route.invite() != null && route.state() != State.UNAVAILABLE) {
				add(new FlatButton(18, 16, Component.literal("Copy " + route.name()), FlatButton.Style.GHOST,
						hero.isCopied(route.invite()) ? Icons.CHECK : Icons.COPY, b -> hero.copy(route.invite())).iconOnly(), width - 26, rowY + 3);
			}
			rowY += ROW;
		}
		return y + LABEL + Math.max(1, routes.size()) * ROW + 4;
	}

	@Override
	public void draw(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
		int y = top + cardY;
		label(g, "Connection details", "Invites pick the best way", left, y, width);
		card(g, left, y + LABEL, width, Math.max(1, routes.size()) * ROW + 4);
		int rowY = y + LABEL + 2;
		if (routes.isEmpty()) {
			Icons.GLOBE.draw(g, left + 9, rowY + 6, Theme.FAINT);
			Draw.textFit(g, "Start hosting to see how friends can connect.", width - 34, left + 24, rowY + 7, Theme.MUTED);
		}
		for (int i = 0; i < routes.size(); i++) {
			Route route = routes.get(i);
			if (i > 0) {
				divider(g, left, rowY - 1, width);
			}
			Icons icon = switch (route.name()) {
				case "Relay" -> Icons.CLOUD;
				case "Same Wi-Fi" -> Icons.HOME;
				default -> Icons.GLOBE;
			};
			icon.draw(g, left + 9, rowY + 6, HeroSection.stateColor(route.state()));
			Draw.text(g, route.name(), left + 24, rowY + 7, Theme.TEXT);
			String state = switch (route.state()) {
				case WORKING -> "Ready";
				case CHECKING -> "Checking...";
				case MAYBE -> "Might work";
				case UNAVAILABLE -> "Not available";
				case OFF -> "Off";
			};
			Draw.text(g, state, left + 84, rowY + 7, HeroSection.stateColor(route.state()));
			boolean shown = route.invite() != null && route.state() != State.UNAVAILABLE;
			Draw.textFit(g, shown ? route.invite() : route.detail(), width - 180, left + 150, rowY + 7, shown ? Theme.MUTED : Theme.FAINT);
			rowY += ROW;
		}
	}
}
