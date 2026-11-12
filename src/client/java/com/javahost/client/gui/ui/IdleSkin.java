package com.javahost.client.gui.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.javahost.JavaHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.model.geom.ModelPart;

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
	private float swayApplied;
	private float headYaw;
	private float headPitch;
	private long lastFrame;

	private IdleSkin(boolean waves) {
		this.waves = waves;
	}

	/** The skin widget for {@code player}, animated. {@code waves}: lift an arm and wave every so often. */
	public static PlayerSkinWidget create(int width, int height, UUID player, boolean waves) {
		IdleSkin idle = new IdleSkin(waves);
		PlayerSkinWidget widget = new PlayerSkinWidget(width, height, Minecraft.getInstance().getEntityModels(), () -> {
			idle.pose();
			return Faces.skinOf(player);
		});
		idle.attach(widget);
		return widget;
	}

	/** Finds the widget's models and its turn angle (private in the game's class, so by type rather than by name). */
	private void attach(PlayerSkinWidget target) {
		widget = target;
		try {
			for (Field field : PlayerSkinWidget.class.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				Object value = field.get(target);
				if (field.getType() == float.class && field.getFloat(target) == 30f) {
					rotationY = field; // starts at 30 degrees; the tilt (the other float) starts at -5
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
