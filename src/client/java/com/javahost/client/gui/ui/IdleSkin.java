package com.javahost.client.gui.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.javahost.JavaHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * The 3D player model, alive: it breathes, shifts its weight, sways a little, follows the mouse with its head and
 * (on Home) waves now and then. It's the game's own skin widget (so it draws right on every Minecraft version);
 * this only poses its model just before each frame, from the skin supplier the widget calls while drawing. If the
 * model can't be reached on some version, the widget simply stands still.
 */
public final class IdleSkin {
	/** One model's movable parts; overlay parts (hat, sleeves...) only where they're separate from the limbs. */
	private record Rig(ModelPart head, ModelPart body, ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg, ModelPart leftLeg,
			ModelPart hat, ModelPart rightSleeve, ModelPart leftSleeve, ModelPart rightPants, ModelPart leftPants) {
	}

	private final List<Rig> rigs = new ArrayList<>();
	private final boolean waves;
	private final long born = System.nanoTime();
	private PlayerSkinWidget widget;
	private Field rotationY;
	private Field rotationX;
	/** Each model's animation, so a page can carry the drag angle over to a rebuilt model. */
	private static final java.util.Map<PlayerSkinWidget, IdleSkin> ATTACHED = new java.util.WeakHashMap<>();
	private float swayApplied;
	private float headYaw;
	private float headPitch;
	private long lastFrame;

	private IdleSkin(boolean waves) {
		this.waves = waves;
	}

	/** The skin widget for {@code player}, animated. {@code waves}: lift an arm and wave every so often. */
	public static PlayerSkinWidget create(int width, int height, UUID player, boolean waves) {
		return create(width, height, () -> Faces.skinOf(player), waves);
	}

	/** An animated model wearing whatever {@code skin} gives each frame (e.g. the skin picked in the Skins page). */
	public static PlayerSkinWidget create(int width, int height, Supplier<PlayerSkin> skin, boolean waves) {
		IdleSkin idle = new IdleSkin(waves);
		PlayerSkinWidget widget = new PlayerSkinWidget(width, height, Minecraft.getInstance().getEntityModels(), () -> {
			idle.pose();
			return skin.get();
		});
		idle.attach(widget);
		return widget;
	}

	/**
	 * Your model, which also opens something when clicked (not dragged: dragging still turns it). Hovering it shows a
	 * quiet {@code hint} under its feet, so the click can be found.
	 */
	public static PlayerSkinWidget clickable(int width, int height, UUID player, boolean waves, String hint, Runnable onClick) {
		IdleSkin idle = new IdleSkin(waves);
		Clickable widget = new Clickable(width, height, () -> {
			idle.pose();
			return Faces.skinOf(player);
		}, hint, onClick);
		idle.attach(widget);
		return widget;
	}

	/** A model that tells a click from a drag: pressed and let go within a few pixels and under half a second. */
	public static final class Clickable extends PlayerSkinWidget {
		private static final double SLOP = 3;
		private static final long CLICK_MS = 450;
		private final String hint;
		private final Runnable onClick;
		private double pressX = Double.NaN;
		private double pressY;
		private double moved;
		private long pressedAt;
		private float shown;
		private long hoverSince;

		Clickable(int width, int height, Supplier<PlayerSkin> skin, String hint, Runnable onClick) {
			super(width, height, Minecraft.getInstance().getEntityModels(), skin);
			this.hint = hint;
			this.onClick = onClick;
			setMessage(net.minecraft.network.chat.Component.literal(hint));
		}

		//? if >=1.21.9 {
		@Override
		public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
			pressed(event.x(), event.y());
		}

		@Override
		protected void onDrag(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
			moved += Math.abs(dx) + Math.abs(dy);
			super.onDrag(event, dx, dy);
		}

		@Override
		public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
			released(event.x(), event.y());
		}
		//?} else {
		/*@Override
		public void onClick(double x, double y) {
			pressed(x, y);
		}

		@Override
		protected void onDrag(double x, double y, double dx, double dy) {
			moved += Math.abs(dx) + Math.abs(dy);
			super.onDrag(x, y, dx, dy);
		}

		@Override
		public void onRelease(double x, double y) {
			released(x, y);
		}
		*///?}

		private void pressed(double x, double y) {
			pressX = x;
			pressY = y;
			moved = 0;
			pressedAt = System.currentTimeMillis();
		}

		private void released(double x, double y) {
			boolean click = !Double.isNaN(pressX) && Math.abs(x - pressX) + Math.abs(y - pressY) <= SLOP && moved <= SLOP
					&& System.currentTimeMillis() - pressedAt <= CLICK_MS;
			pressX = Double.NaN;
			if (click) {
				onClick.run();
			}
		}

		/** For tests: what a click does. */
		public void click() {
			onClick.run();
		}

		@Override
		protected void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			super.extractWidgetRenderState(g, mouseX, mouseY, a);
			boolean over = isHovered() && Double.isNaN(pressX);
			long now = System.currentTimeMillis();
			if (!over) {
				hoverSince = 0;
			} else if (hoverSince == 0) {
				hoverSince = now;
			}
			// The hint fades in once the mouse has rested on the model for a moment.
			shown = Draw.approach(shown, over && now - hoverSince > 250 ? 1 : 0, 0.25f);
			if (shown < 0.02f) {
				return;
			}
			g.nextStratum();
			int w = Draw.width(hint) + Icons.SIZE + 16;
			int x = getX() + (getWidth() - w) / 2;
			int y = getY() + Math.round(getHeight() * 0.74f) + Math.round((1 - shown) * 3); // over the legs, clear of anything below
			Draw.roundRect(g, x - 1, y - 1, w + 2, 16, 7, Theme.withAlpha(0x40FFFFFF, shown));
			Draw.roundRect(g, x, y, w, 14, 6, Theme.withAlpha(0xFF0E1217, 0.94f * shown));
			Icons.SHIRT.draw(g, x + 6, y + 2, Theme.withAlpha(Theme.ACCENT, shown));
			Draw.text(g, hint, x + 10 + Icons.SIZE, y + 3, Theme.withAlpha(Theme.TEXT, shown));
		}
	}

	/** Finds the widget's models and its turn angle (private in the game's class, so by type rather than by name). */
	private void attach(PlayerSkinWidget target) {
		widget = target;
		ATTACHED.put(target, this);
		try {
			for (Field field : PlayerSkinWidget.class.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				Object value = field.get(target);
				if (field.getType() == float.class && field.getFloat(target) == 30f) {
					rotationY = field; // starts at 30 degrees; the tilt (the other float) starts at -5
				} else if (field.getType() == float.class && field.getFloat(target) == -5f) {
					rotationX = field;
				} else if (value != null && value.getClass().isRecord()) {
					// Older versions keep both models in a small record.
					for (var component : value.getClass().getRecordComponents()) {
						var accessor = component.getAccessor();
						accessor.setAccessible(true);
						addRig(accessor.invoke(value));
					}
				} else {
					addRig(value);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			rigs.clear();
			JavaHost.LOGGER.debug("[Skin] no idle animation here: {}", e.toString());
		}
	}

	private void addRig(Object model) {
		//? if >=1.21.2 {
		if (model instanceof net.minecraft.client.model.Model m) {
			ModelPart root = m.root();
			if (root.hasChild("head") && root.hasChild("right_arm")) {
				rigs.add(new Rig(root.getChild("head"), root.getChild("body"), root.getChild("right_arm"), root.getChild("left_arm"),
						root.getChild("right_leg"), root.getChild("left_leg"), null, null, null, null, null));
			}
		}
		//?} else {
		/*if (model instanceof net.minecraft.client.model.PlayerModel<?> m) {
			// Before 1.21.2 the outer layer isn't attached to the limbs: it has to be moved along with them.
			rigs.add(new Rig(m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg, m.hat, m.rightSleeve, m.leftSleeve,
					m.rightPants, m.leftPants));
		}
		*///?}
	}

	/** How far a model has been turned and tilted by dragging (null if unknown), for {@link #restoreAngles}. */
	public static float[] angles(PlayerSkinWidget widget) {
		IdleSkin idle = widget == null ? null : ATTACHED.get(widget);
		if (idle == null || idle.rotationY == null || idle.rotationX == null) {
			return null;
		}
		try {
			return new float[] {idle.rotationX.getFloat(widget), idle.rotationY.getFloat(widget) - idle.swayApplied};
		} catch (IllegalAccessException e) {
			return null;
		}
	}

	/** Turns a new model to where an old one was ({@link #angles}). */
	public static void restoreAngles(PlayerSkinWidget widget, float[] angles) {
		IdleSkin idle = ATTACHED.get(widget);
		if (angles == null || idle == null || idle.rotationY == null || idle.rotationX == null) {
			return;
		}
		try {
			idle.rotationX.setFloat(widget, angles[0]);
			idle.rotationY.setFloat(widget, angles[1] + idle.swayApplied);
		} catch (IllegalAccessException e) {
			// stays at the default angle
		}
	}

	private static int posedFrames;

	/** For tests: how many frames some model has been posed (0 would mean the animation found nothing to move). */
	public static int posedFrames() {
		return posedFrames;
	}

	private void pose() {
		if (rigs.isEmpty() || widget == null) {
			return;
		}
		posedFrames++;
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		float t = (now - born) / 1e9f;
		float age = t * 20; // in game ticks, to reuse the game's own idle arm sway

		// Head: follows the mouse, smoothly; drifts on its own when the mouse is still far away.
		Minecraft minecraft = Minecraft.getInstance();
		var window = minecraft.getWindow();
		double mouseX = minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / Math.max(1, window.getScreenWidth());
		double mouseY = minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / Math.max(1, window.getScreenHeight());
		float dx = (float) (mouseX - (widget.getX() + widget.getWidth() / 2.0));
		float dy = (float) (mouseY - (widget.getY() + widget.getHeight() * 0.22));
		float reach = Math.max(60, widget.getHeight() * 1.2f);
		float yawTarget = clamp(dx / reach, -1, 1) * 0.6f + (float) Math.sin(t * 0.45) * 0.06f;
		float pitchTarget = clamp(dy / reach, -1, 1) * 0.35f + (float) Math.sin(t * 0.3 + 1) * 0.03f;
		float ease = 1 - (float) Math.exp(-dt * 6);
		headYaw += (yawTarget - headYaw) * ease;
		headPitch += (pitchTarget - headPitch) * ease;

		// Arms: the game's idle bob. A wave lifts the right arm for a moment every quarter minute.
		float bob = (float) Math.cos(age * 0.09f) * 0.05f + 0.05f;
		float swing = (float) Math.sin(age * 0.067f) * 0.05f;
		float wave = 0;
		if (waves) {
			float cycle = (t + 13) % 16; // first wave about three seconds after the menu opens
			if (cycle < 2.2f) {
				wave = smooth(Math.min(1, cycle / 0.35f)) * smooth(Math.min(1, (2.2f - cycle) / 0.4f));
			}
		}
		float shift = (float) Math.sin(t * 0.7) * 0.025f; // weight from one leg to the other

		for (Rig rig : rigs) {
			set(rig.head(), headPitch, -headYaw, 0);
			set(rig.body(), 0, 0, 0);
			set(rig.rightArm(), swing * (1 - wave) - 2.75f * wave, 0, bob * (1 - wave) + (float) Math.sin(t * 11) * 0.28f * wave - 0.15f * wave);
			set(rig.leftArm(), -swing, 0, -bob);
			set(rig.rightLeg(), shift, 0, 0.01f);
			set(rig.leftLeg(), -shift, 0, -0.01f);
			copy(rig.head(), rig.hat());
			copy(rig.rightArm(), rig.rightSleeve());
			copy(rig.leftArm(), rig.leftSleeve());
			copy(rig.rightLeg(), rig.rightPants());
			copy(rig.leftLeg(), rig.leftPants());
		}

		// A slow turn from side to side, added to wherever the player has dragged the model.
		if (rotationY != null) {
			try {
				float sway = (float) Math.sin(t * 0.55) * 5f;
				rotationY.setFloat(widget, rotationY.getFloat(widget) - swayApplied + sway);
				swayApplied = sway;
			} catch (IllegalAccessException e) {
				rotationY = null;
			}
		}
	}

	private static void set(ModelPart part, float x, float y, float z) {
		if (part != null) {
			part.xRot = x;
			part.yRot = y;
			part.zRot = z;
		}
	}

	private static void copy(ModelPart from, ModelPart to) {
		if (from != null && to != null) {
			to.xRot = from.xRot;
			to.yRot = from.yRot;
			to.zRot = from.zRot;
		}
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float smooth(float x) {
		return x * x * (3 - 2 * x);
	}
}
