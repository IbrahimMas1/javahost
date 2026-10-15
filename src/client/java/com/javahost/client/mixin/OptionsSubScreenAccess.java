package com.javahost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;

/** The screen an options screen goes back to (for switching between Minecraft's and Sodium's Video Settings). */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccess {
	@Accessor("lastScreen")
	Screen javahost$lastScreen();
}
