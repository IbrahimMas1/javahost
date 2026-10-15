package com.javahost.client.compat.input;

import com.mojang.blaze3d.platform.InputConstants;

/** Minecraft 1.21.9's KeyEvent, for older versions (they pass key, scancode and modifiers separately). */
public record KeyEvent(int key, int scancode, int modifiers) {
	public boolean isConfirmation() {
		return key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
	}

	public boolean isLeft() {
		return key == InputConstants.KEY_LEFT;
	}

	public boolean isRight() {
		return key == InputConstants.KEY_RIGHT;
	}

	public boolean isEscape() {
		return key == InputConstants.KEY_ESCAPE;
	}
}
