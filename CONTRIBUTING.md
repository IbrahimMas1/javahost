# Contributing to JavaHost

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

- **Bugs and ideas:** [open an issue](https://github.com/IbrahimMas1/javahost/issues/new/choose). For bugs, attach **Settings → Copy debug report** from the game (it leaves out addresses and names).
- **Security problems:** please don't open a public issue; see [SECURITY.md](SECURITY.md).
- **Pull requests:** keep them focused, describe what you tested, and make sure `./gradlew buildAll` passes. Don't include real IP addresses, invite codes or player names in code, tests or screenshots: use documentation addresses (`192.0.2.x`, `2001:db8::/32`) and made-up names.

## Project layout

- `src/main`: shared and server code (hosting, voice relay, world rules, mod checks, `util/` helpers with unit tests).
- `src/client`: the game client (menus, friends, invites, voice, mods, updates).
- `src/test`: JUnit tests for `util/`. `src/gametest`: in-game tests that drive the real client and take screenshots of every screen.
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
./gradlew :installer:test            # installer: real install/update/uninstall into a fake .minecraft
./gradlew :installer:jpackageMac     # macOS .dmg
```

The GitHub workflow in `.github/workflows/build.yml` builds the Windows `.exe` and the macOS `.dmg`.


## Releasing an update

1. Bump `version=` in `gradle.properties` (and `PROTOCOL` in `JavaHost.java` only if old and new versions can't play together).
2. Commit and push, then run `scripts/release.sh "What's new"`.
3. GitHub builds everything and publishes a release with a signed `update.json`; players' games offer the update on their next launch.
4. For a fix everyone must have, use `scripts/release.sh --important "What's new"`. Players can't skip it (only "Remind me soon"), and older versions can't be restored with Settings → Restore.

Players can restore the previous version from Settings, and cancel that (or a pending update) any time before Minecraft closes.

The signing key's private half is the `UPDATE_SIGNING_KEY` Actions secret (a copy is kept outside the repo). Without it, releases can't be signed and the game ignores them.
