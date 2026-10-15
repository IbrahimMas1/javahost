package com.javahost.client.gui.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.javahost.client.friends.Screenshots.Thumbnail;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
//? if >=1.21.6
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** A clickable image (screenshot thumbnail), cropped to fill its box, with a hover outline. */
public class ImageButton extends Button {
	private final Supplier<Thumbnail> image;
	private float hover;

	public ImageButton(int width, int height, Component narration, Supplier<Thumbnail> image, Consumer<ImageButton> onPress) {
		super(0, 0, width, height, narration, button -> onPress.accept((ImageButton) button), DEFAULT_NARRATION);
		this.image = image;
	}

	@Override
	//? if >=26.1 {
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	//?} else if >=1.21.11 {
	/*protected void renderContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?} else {
	/*protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	*///?}
		hover = Draw.approach(hover, isHoveredOrFocused() ? 1 : 0, 0.35f);
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		Draw.roundRect(g, x, y, w, h, 4, Theme.mix(Theme.BORDER, Theme.ACCENT, hover));
		Thumbnail thumb = image.get();
		if (thumb == null) {
			Draw.roundRect(g, x + 1, y + 1, w - 2, h - 2, 3, Theme.FIELD);
			Icons.CAMERA.draw(g, x + (w - Icons.SIZE) / 2, y + (h - Icons.SIZE) / 2, Theme.FAINT);
			return;
		}
		drawCover(g, thumb, x + 1, y + 1, w - 2, h - 2);
	}

	/** Draws the image scaled to cover the box, cropping the overflow evenly. */
	public static void drawCover(GuiGraphicsExtractor g, Thumbnail thumb, int x, int y, int w, int h) {
		float boxRatio = w / (float) h;
		float imageRatio = thumb.width() / (float) thumb.height();
		int srcW = thumb.width();
		int srcH = thumb.height();
		float u = 0;
		float v = 0;
		if (imageRatio > boxRatio) {
			srcW = Math.round(thumb.height() * boxRatio);
			u = (thumb.width() - srcW) / 2f;
		} else {
			srcH = Math.round(thumb.width() / boxRatio);
			v = (thumb.height() - srcH) / 2f;
		}
		//? if >=1.21.6 {
		g.blit(RenderPipelines.GUI_TEXTURED, thumb.texture(), x, y, u, v, w, h, srcW, srcH, thumb.width(), thumb.height());
		//?} else if >=1.21.2 {
		/*g.blit(net.minecraft.client.renderer.RenderType::guiTextured, thumb.texture(), x, y, u, v, w, h, srcW, srcH, thumb.width(), thumb.height());
		*///?} else
		/*g.blit(thumb.texture(), x, y, w, h, u, v, srcW, srcH, thumb.width(), thumb.height());*/
	}
}
