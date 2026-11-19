package com.javahost.client.update;

import java.io.IOException;

import com.javahost.update.UpdateService;
import com.javahost.util.UpdateManifest;
import net.minecraft.client.Minecraft;

/**
 * The self-updater behind {@link com.javahost.update.Updates}, found there by name. Only in the GitHub/installer
 * jars: everything in this package is deleted from the copies uploaded to CurseForge and Modrinth.
 */
public final class SelfUpdater implements UpdateService {
	@Override
	public void start() {
		UpdateChecker.start();
	}

	@Override
	public void tick() {
		UpdateChecker.tick(Minecraft.getInstance());
	}

	@Override
	public void launchOnExit() {
		Updater.launchSwapIfStaged();
	}

	@Override
	public boolean selfUpdates() {
		return Updater.selfUpdates();
	}

	@Override
	public String availableVersion() {
		UpdateManifest update = UpdateChecker.available();
		return update == null ? null : update.version();
	}

	@Override
	public boolean important() {
		return UpdateChecker.important();
	}

	@Override
	public boolean checking() {
		return UpdateChecker.checking();
	}

	@Override
	public void checkNow(Runnable whenDone) {
		UpdateChecker.checkNow(whenDone);
	}

	@Override
	public void openUpdateScreen() {
		UpdateManifest update = UpdateChecker.available();
		if (update != null) {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.gui.setScreen(new UpdateScreen(minecraft.gui.screen(), update));
		}
	}

	@Override
	public boolean staged() {
		return Updater.staged();
	}

	@Override
	public String stagedKind() {
		return Updater.stagedKind();
	}

	@Override
	public String stagedVersion() {
		return Updater.stagedVersion();
	}

	@Override
	public void cancelStaged() {
		Updater.cancelStaged();
	}

	@Override
	public String previousVersion() {
		return Updater.previousVersion();
	}

	@Override
	public String rollBackBlockedReason() {
		return Updater.rollBackBlockedReason(UpdateChecker.latest());
	}

	@Override
	public void stageRollBack() throws IOException {
		Updater.stageRollBack();
	}
}
