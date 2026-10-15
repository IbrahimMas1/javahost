plugins {
	id("dev.kikugie.stonecutter")
}

// The version the sources in src/ are written for. Commit only with 26.3 active.
stonecutter active "26.3"

stonecutter parameters {
	swaps["minecraft"] = "\"${node.metadata.version}\";"

	// Renames that touch many lines are done here instead of with //? comments. Each one has a reverse so the
	// sources can be switched back to 26.3.
	val mc = "\\b(minecraft|mc|Minecraft\\.getInstance\\(\\))"
	replacements {
		// 26.2 moved screens and toasts from Minecraft to Minecraft.gui.
		regex(current.parsed < "26.2") {
			replace("$mc\\.gui\\.setScreen\\(", "$1.setScreen(", "$mc\\.setScreen\\(", "$1.gui.setScreen(")
		}
		regex(current.parsed < "26.2") {
			replace("$mc\\.gui\\.screen\\(\\)", "$1.screen", "$mc\\.screen\\b(?!\\()", "$1.gui.screen()")
		}
		regex(current.parsed < "26.2") {
			replace("$mc\\.gui\\.toastManager\\(\\)", "$1.getToastManager()", "$mc\\.getToastManager\\(\\)", "$1.gui.toastManager()")
		}
		// Fabric API took Mojang's names in 26.1 (S2C became Clientbound, and so on).
		string(current.parsed < "26.1") {
			replace("PayloadTypeRegistry.clientboundPlay()", "PayloadTypeRegistry.playS2C()")
		}
		string(current.parsed < "26.1") {
			replace("PayloadTypeRegistry.serverboundPlay()", "PayloadTypeRegistry.playC2S()")
		}
		string(current.parsed < "26.1") {
			replace("PayloadTypeRegistry.clientboundConfiguration()", "PayloadTypeRegistry.configurationS2C()")
		}
		string(current.parsed < "26.1") {
			replace("PayloadTypeRegistry.serverboundConfiguration()", "PayloadTypeRegistry.configurationC2S()")
		}
		string(current.parsed < "26.1") {
			replace("Networking.createClientboundPacket(", "Networking.createS2CPacket(")
		}
		string(current.parsed < "26.1") {
			replace("context.packetListener()", "context.networkHandler()")
		}
		string(current.parsed < "26.1") {
			replace("client.keymapping.v1.KeyMappingHelper", "client.keybinding.v1.KeyBindingHelper")
		}
		string(current.parsed < "26.1") {
			replace("KeyMappingHelper.registerKeyMapping(", "KeyBindingHelper.registerKeyBinding(")
		}
		// 26.1 renamed GUI drawing from "render" to "extract".
		string(current.parsed < "26.1") {
			replace("GuiGraphicsExtractor", "GuiGraphics")
		}
		string(current.parsed < "26.1") {
			replace("PlayerFaceExtractor.extractRenderState(", "PlayerFaceRenderer.draw(")
		}
		string(current.parsed < "26.1") {
			replace("PlayerFaceExtractor", "PlayerFaceRenderer")
		}
		string(current.parsed < "26.1") {
			replace("extractRenderState(", "render(")
		}
		string(current.parsed < "26.1") {
			replace("extractWidgetRenderState(", "renderWidget(")
		}
		string(current.parsed < "26.1") {
			replace("ScreenEvents.beforeExtract(", "ScreenEvents.beforeRender(")
		}
		string(current.parsed < "26.1") {
			replace("ScreenEvents.afterExtract(", "ScreenEvents.afterRender(")
		}
		string(current.parsed < "26.1") {
			replace("Screens.getWidgets(", "Screens.getButtons(")
		}
		regex(current.parsed < "26.1") {
			replace("\\bg\\.text\\(", "g.drawString(", "\\bg\\.drawString\\(", "g.text(")
		}
		// 1.21.9 bundled key and mouse input into event records; older versions use JavaHost's copies.
		string(current.parsed < "1.21.9") {
			replace("net.minecraft.client.input.", "com.javahost.client.compat.input.")
		}
		string(current.parsed < "1.21.9") {
			replace("net.minecraft.world.entity.player.PlayerSkin", "net.minecraft.client.resources.PlayerSkin")
		}
		// Before 1.21.6 GUI drawing happened right away, in order; flushing keeps later drawing on top.
		regex(current.parsed < "1.21.6") {
			replace("\\bg\\.nextStratum\\(\\);", "g.flush();", "\\bg\\.flush\\(\\);", "g.nextStratum();")
		}
		string(current.parsed < "1.21.6") {
			replace("getCurrentVersion().name()", "getCurrentVersion().getName()")
		}
		string(current.parsed < "1.21.6") {
			replace("getCurrentVersion().protocolVersion()", "getCurrentVersion().getProtocolVersion()")
		}
		// Minecraft renamed ResourceLocation to Identifier in 1.21.11.
		string(current.parsed < "1.21.11") {
			replace("Identifier", "ResourceLocation")
		}
	}
}

// ./gradlew buildAll: builds and tests JavaHost for every Minecraft version and collects the jars in
// build/libs/<mod version>/ (what CI publishes).
tasks.register("buildAll") {
	group = "build"
	description = "Builds JavaHost for every Minecraft version."
	dependsOn(stonecutter.versions.map { ":${it.project}:build" })
	dependsOn(stonecutter.versions.map { ":${it.project}:collectJar" })
}
