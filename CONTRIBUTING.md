# Contributing to JavaHost

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

- **Bugs and ideas:** [open an issue](https://github.com/IbrahimMas1/javahost/issues/new/choose). For bugs, attach **Settings → Copy debug report** from the game (it leaves out addresses and names).
- **Security problems:** please don't open a public issue; see [SECURITY.md](SECURITY.md).
- **Pull requests:** keep them focused, describe what you tested, and make sure `./gradlew buildAll` passes. Don't include real IP addresses, invite codes or player names in code, tests or screenshots: use documentation addresses (`192.0.2.x`, `2001:db8::/32`) and made-up names.

## Project layout

- `src/main`: shared and server code (hosting, voice relay, mod checks, `util/` helpers with unit tests).
  - `rules/`: world options on the host's server. `WorldRules` (operators, Cheats, lock, history), `RuleCatalog` (every game rule of the running version) and `mixin/MinecraftServerMixin` (command permissions through `util/OperatorPolicy`). The `world_state` packet is in `net/Payloads`.
  - `util/`: among others `ModJar`, `ModToggle`, `ModSwap`, `AfterExit`, `ModChanges`, `ModStates` and `VanillaPlan` (mods on and off, run after the game closes; JDK-only where they run outside the game), and `SkinImage`, `SkinFolder`, `SkinApi` (the skin changer).
- `src/client`: the game client (menus, friends, invites, voice, mods, skins, updates).
  - `rules/WorldOptions`: the client side of `world_state`. The World page is `gui/pages/HostPage` plus the sections in `gui/pages/world/`.
  - `mods/`: `ModLibrary` (My mods), `VanillaMode`, and `AfterExitHelper`, the one helper started as the game closes.
  - `skins/`: `SkinLibrary` and `SkinService`; the page is `gui/pages/SkinsPage`, and `mixin/OwnSkinMixin` shows your new skin on your own player.
- `src/test`: JUnit tests for `util/`. `src/gametest`: in-game tests that drive the real client and take screenshots of every screen (`JavaHostClientTest`, which also runs `SkinsTest`; `ModsToggleTest`, `VanillaTest`, `MultiplayerTest`, `ModdedTest`).
- `installer/`: the Swing installer (`.jar`, `.exe`, `.dmg`).
- `directory/`: the public world list, a Cloudflare Worker with a D1 database (`npm test` runs it on Node's SQLite).

## Build and test

Requires Java 25.

The mod is built once per Minecraft version from one source tree with [Stonecutter](https://stonecutter.kikugie.dev/). Code for other versions sits in `//? if` comments, and version-specific calls live in `compat/` classes. Versions and their Fabric API are listed in `stonecutter.properties.toml`.

```bash
./gradlew buildAll                   # every version; jars in build/libs/<version>/
./gradlew :1.21.1:build              # one version
./gradlew :26.3:runClientGameTest    # in-game tests + UI screenshots (any version from 1.21.4)
./gradlew :1.21.1:runSmoke           # quick in-game check on 1.21.1 (no Fabric test API there)
scripts/multiplayer-test.sh          # two real clients: host + guest over LAN, IPv6 and the relay
scripts/modded-test.sh               # two real clients: the host has Naturalist, the guest gets it when joining
scripts/mods-toggle-test.sh          # My mods: a mod is switched off, the game closes, then on again; mods/ must end as it began
scripts/vanilla-test.sh              # Vanilla mode on, the game closes, then the mods come back; mods/ must end as it began
./gradlew :installer:test            # installer: real install/update/uninstall into a fake .minecraft
./gradlew :installer:jpackageMac     # macOS .dmg
```

`JAVAHOST_TEST_ONLY` runs one part of the in-game test while you work on it, for example `JAVAHOST_TEST_ONLY=hub ./gradlew :26.3:runClientGameTest`:

- `hub`: the menu (Home with sample friends, every tab, the title screen). It prints no "ALL CHECKS PASSED" line: check the log for `FAIL`.
- `skins`: the skin changer, against a stand-in profile service inside the test game (no real account is touched).
- `mods`: My mods. `vanilla`: Vanilla mode.

`runClientGameTest` empties its run folder every time it starts, so the two tests that need the game to really close and start again (`mods-toggle-test.sh`, `vanilla-test.sh`) drive the `runModGuest` game instead, with `JAVAHOST_TEST_ONLY=toggle1`/`toggle2` and `vanilla1`/`vanilla2`. Run one test game at a time.

The GitHub workflow in `.github/workflows/build.yml` builds the Windows `.exe` and the macOS `.dmg`.


## Releasing an update

1. Bump `version=` in `gradle.properties` (and `PROTOCOL` in `JavaHost.java` only if old and new versions can't play together).
2. Commit and push, then run `scripts/release.sh "What's new"`.
3. GitHub builds everything and publishes a release with a signed `update.json`; players' games offer the update on their next launch. Then it uploads the mod jars to Modrinth and CurseForge (`scripts/PublishMod.java`), once the `MODRINTH_TOKEN` / `CURSEFORGE_TOKEN` secrets and `MODRINTH_PROJECT_ID` / `CURSEFORGE_PROJECT_ID` variables are set. Actions → Publish uploads an older release by hand. The jars uploaded there carry `javahost/distribution.properties` (`platform=curseforge` or `modrinth`, see `util/Distribution`): those copies never check for or download updates themselves and only take modpack files from Modrinth's CDN, as both sites require. The GitHub jars stay unmarked.
4. For a fix everyone must have, use `scripts/release.sh --important "What's new"`. Players can't skip it (only "Remind me soon"), and older versions can't be restored with Settings → Restore.

Players can restore the previous version from Settings, and cancel that (or a pending update) any time before Minecraft closes. JavaHost only updates itself in the folders its installer made; when a launcher (CurseForge app, Modrinth App, Prism) installed it, the game says what's new and to update there.

The signing key's private half is the `UPDATE_SIGNING_KEY` Actions secret (a copy is kept outside the repo). Without it, releases can't be signed and the game ignores them.
