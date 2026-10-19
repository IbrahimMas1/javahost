# JavaHost

A Fabric mod for Minecraft Java Edition: **26.3, 26.2, 26.1.x and 1.21.x** (1.21 to 1.21.11). Pick your version in the installer.

- **Friends and one-click invites.** Add a friend by their Minecraft name. Click **Invite** and their game shows "Alex invited you to play" with a **Join** button. JavaHost opens your world, picks the best connection and falls back automatically. You don't share codes or links. Your Friends tab shows *Sent → Delivered → In your world*.
- **See who's online.** Friends show as *Online*, *Playing* or *Hosting "World" 2/8*. Click **Join** on anyone hosting; no invite needed. The title screen has a small friends panel too.
- **Chat, screenshots and notifications.** Message friends from the menu. Press `F6` in game to take a screenshot and send it to a friend. Everything is end-to-end encrypted. The bell keeps invites, requests, messages and screenshots you missed.
- **Friend profiles.** A 3D skin preview, their status, and buttons to invite, message, send a screenshot, set their voice volume, mute them or remove them.
- **Host your world from your own PC.** Open your singleplayer world and send friends an invite code. You don't need a rented server. JavaHost tries every way to reach you in turn:
  1. **Internet:** your router opens the port automatically (UPnP).
  2. **IPv6:** works even when your internet provider shares one public address between homes (CGNAT).
  3. **Relay:** a free relay (e4mc) for when nothing direct works. Anyone can join this way, even without mods.
  4. **Same Wi-Fi:** for people at home.
- **Proximity voice chat.** Push-to-talk (`V`) or voice activation, mute (`M`). Voices fade with distance and come from the speaker's direction. Set each player's volume or mute them. A ♪ appears on the nametag of whoever is talking.
- **Party voice.** The host (or a co-host) presses `B`, or uses the Voice tab or World rules, to switch the whole world between nearby voice and party voice, where everyone hears everyone wherever they are. It's one setting for everybody, so nobody is left on a different mode.
- **Automatic world backups.** When a friend joins your world, JavaHost saves a backup first (at most every 30 minutes, newest 5 kept). Restore one from the Host tab's Backups: your current world is kept as a copy, never deleted.
- **Same-world fast lane.** When you and a friend are in the same world, chat and screenshots go through the game connection: instant, full size, and they don't count toward the daily limit.
- **Host controls.** The Host tab keeps your invite code, Invite friends and Stop hosting on top, with Players, Settings and Backups below. Each player has a game-mode button and a menu to trust, send a world, kick or ban; Settings has who can join, the player limit, sharing and the advanced connection options.
- **Shared look.** Guests get the host's resource packs and **shader, including its settings** (with Iris). They're asked first, and everything goes back to normal when they leave.
- **Performance.** One click installs Sodium, Lithium, FerriteCore, ImmediatelyFast and Entity Culling for your exact version, checked against Modrinth's hashes. **Quick Tune** gives FPS-friendly settings and can undo them. With Sodium installed, choose **Simple** (Minecraft's own Video Settings) or **Sodium** (detailed) under Settings; each screen has a button to switch. **Quick settings** for render distance, max frame rate and graphics sit right on the Performance tab. There's an optional FPS / ping / memory overlay.
- **Modern menu.** Press `J` in game, or use the **dock** down the right side of the title and pause screens: Host, Friends, Screenshots, Performance, Settings and your Account. Mods have their own tab in the menu. Badges show what's new (including notifications, under Friends), and Friends glows when a friend's world is ready to join. The title screen also shows your 3D skin (drag to turn it) and the JavaHost version. Pick an accent colour in Settings.
- **Account page.** Your 3D skin, how friends see you, an **Appear offline** switch, and how many friend messages you've sent today.
- Friends need a Microsoft-account login, which every normal Minecraft purchase has.

- **Automatic updates.** JavaHost checks for new versions when the game starts (and every few hours). It asks before updating, never mid-game; if you'd rather wait, it tells you whether you can still play with friends who updated. Updates are signed, checked file by file, and installed when Minecraft closes. Settings has Check for updates and Go back to the previous version.
- **World rules.** Difficulty, keep inventory, PvP, daylight and more, on their own page from the Host tab. Only the host (and friends they make co-hosts) can change them, every change is announced in chat and listed, and the host can lock the rules for a challenge run.
- **Send a world copy.** While you and a friend are in the same world, send them a copy of the world you're in (as it is right now), any other saved world, or a backup. It travels over the game connection (LAN, direct or the relay), encrypted so only the two of you can read it, with progress and Cancel in the notification center. Your friend accepts first; the copy lands in their Singleplayer worlds as "World (from you)", and they play as themselves there (their own inventory if they've played it before). Your character is kept too, so when you join their copy you're where you left off.
- **Welcome tour, debug report, screenshot tools.** A short tour the first time; Settings → Copy debug report (no IPs or names) for asking for help; favourite, rename and delete screenshots.

## Install (recommended): the JavaHost Installer

1. Open the Minecraft Launcher once if you never have, then close it.
2. Run **JavaHost Installer**:
   - **Mac:** open the `.dmg` and run the app. It's unsigned, so the first time you need to right-click → **Open**.
   - **Windows:** run the `.exe`. If SmartScreen appears, click **More info → Run anyway**.
3. Pick your **Minecraft version** (the one your mods are made for), choose add-ons and click **Install**.
4. In the Minecraft Launcher, pick the **JavaHost** profile for that version (e.g. "JavaHost 1.21.1") and press Play.

The installer:
- installs the official **Fabric loader**, **Fabric API**, JavaHost and the add-ons you picked, for the version you chose;
- gives each Minecraft version its own profile and folder (`javahost` for 26.3, `javahost-1.21.1` and so on), so mods for different versions never mix and your normal Minecraft is never touched;
- downloads JavaHost from the latest release and checks it against the release's signed list before installing;
- can copy your settings, packs and worlds into that folder.

Run it again to update, add another version, or uninstall.

## Minecraft versions (1.10)

| JavaHost build | Runs on |
|---|---|
| 26.3 | 26.3 |
| 26.2 | 26.2 |
| 26.1.2 | 26.1, 26.1.1, 26.1.2 |
| 1.21.11 | 1.21.11 |
| 1.21.10 | 1.21.9, 1.21.10 |
| 1.21.8 | 1.21.6, 1.21.7, 1.21.8 |
| 1.21.4 | 1.21.4 |
| 1.21.1 | 1.21, 1.21.1 |

- Every version has the same features. Friends, chat, invites and screenshots work between versions; joining a world needs the same Minecraft version, as always in Minecraft. A friend on another version shows "Playing on 1.21.1".
- Each version updates itself to the next JavaHost release for that same version.
- Mods for 1.21.1 (The Aether, YUNG's mods and many modpacks) work with the 1.21.1 build, including **Download & restart** for friends who join.

Friends need JavaHost too to get voice and your packs. The easiest way is to send them the installer. Players without mods can still join through the relay address.

## How friends work (no JavaHost server)

- Friend requests and invites travel through **ntfy.sh**, a free public message relay. Each player has a mailbox named after their account ID.
- **Requests are signed** with your Mojang-certified chat key (the same one Minecraft signs chat with), so nobody can pretend to be you.
- **Invites, chat and screenshots are encrypted** with a key only you and that friend share. Nobody else can read your world's address or your messages.
- **Version check:** your friends list shows when a friend runs a different JavaHost version, so you know who needs to update.
- **Online status** is encrypted with a separate key that you give each friend. It changes when you remove someone, so they stop seeing you.
- Your game checks the connection to the relay and reconnects by itself if it goes quiet, so invites don't get lost.
- **Friends-only worlds (default):** players who aren't your friends are turned away when they try to join. Minecraft itself verifies accounts. You can switch this off on the Host tab.
- **Offline friends:** messages are kept for 12 hours, so a friend who's offline gets your request when they next play. Screenshots are kept for 3 hours.
- ntfy.sh is free, so it limits each internet connection to about 250 *sent* messages a day (receiving is free). Normal play uses far fewer: your online status sends one every 30 minutes or when it changes. The Account page shows today's count, and JavaHost tells you if you hit the limit.

## Public worlds (1.8, beta)

- **Discover** lists worlds people have opened to everyone. Each shows who's hosting, the title, tags, players and limit, ping (measured the same way as Minecraft's server list) and the region. Search, filter by tag, and sort by busiest or closest.
- **Open to the public** from the Host tab or Discover. Give it a title, a description, tags, a player limit, and choose whether visitors can use voice (off by default). JavaHost makes a backup first.
- **Visitors can't wreck anything.** Strangers join in Adventure mode: they can look around, fight and use doors, but can't break or place blocks until you click **Trust**. Your friends play normally. Kick, ban, block and backups all still work.
- **Your address stays private.** Public worlds are reached only through the relay; no router port or IPv6 opening is made. Joining checks the world really belongs to the listed host.
- **Reports and moderation.** Report a world from Discover or from inside it. Three reports from different players hide it until it's reviewed. Listings are checked for links, addresses and offensive words. Hosts sign in with their real Minecraft account, so nobody can list in someone else's name.
- Accounts with online play turned off (parental controls, or banned by Mojang) can't see or open public worlds.
- The list itself is a small service in `directory/` (a Cloudflare Worker with a D1 database). Moderate it with `scripts/moderate.sh`.

## Modded worlds (1.9)

- **Play with mods together.** Host with any Fabric mods for your Minecraft version (Naturalist, for example). When a friend who's missing some joins, they don't get Minecraft's "registry mismatch" error. Instead they see which mods the world uses, with sizes, and a **Download & restart** button.
- **The very same files.** JavaHost matches each of the host's mods to its exact Modrinth version by hash. Friends download those files from Modrinth, and each one is checked against Modrinth's hash and the host's. Nothing a player sends is ever run. Mods that aren't on Modrinth are listed with a link so friends can get them themselves.
- **Only what's needed.** Client-only mods (Sodium, Iris, minimaps) and host-side mods (Lithium) aren't asked of friends: only mods that add blocks, items, mobs and so on, plus the libraries they need.
- **Your own mods are safe.** Downloaded mods are kept as a *mod set*. The switch happens when Minecraft closes: clashing mods are put aside (never deleted) and everything is put back if anything goes wrong. When you press Play again, JavaHost takes you straight back into your friend's world. **Back to my mods** on the new **Mods** tab restores your set.
- **Browse mods and modpacks.** The Mods tab works like a store: search Modrinth for gameplay mods and modpacks (performance mods and libraries are left out), filter by category (Adventure, Magic, Technology...), or show only the ones that run on your version. Each one opens its own page in the menu with its icon, description, loaders and Minecraft versions. Ones made for your version install with one click (mods go into your mods folder, modpacks become a mod set with the pack's settings); others say where they run, such as "Runs on NeoForge 1.21.1", so you know which version to pick in the installer. Files come only from the hosts Modrinth allows, and every one is hash-checked. Friends who join get the mods they need automatically.
- **Downloads in the menu.** Installs queue up and download one after another. A download icon next to the bell shows a progress ring and opens the list, where you can cancel one. When a mod is ready, a notification says so, with a button to close Minecraft: mods load when the game starts, so press Play again to use them. Installed lists your downloads and every mod JavaHost put in, active or waiting for a restart. Remove one with its bin button: it keeps working until you close Minecraft, then it's deleted (Keep changes your mind). Mods that others need, such as Sodium for Iris, can't be removed while those are installed.
- Friends see "Hosting "World" · 3/8 · 12 mods", and Discover shows "Modded (12)" on public worlds.
- Only Fabric works: Forge/NeoForge modpacks can't load JavaHost.

## Safety (1.7)

- **Joining checks it's really your friend's world.** Each player has a signing key that only their own game holds. When you join a friend, their game must prove the world is theirs. A fake server, or some other PC on your network, is left at once and the next way in is tried. Friends on older versions need to update before you can join them.
- **Online status can't be faked.** Statuses are signed by the friend who sent them, so another friend can't post a fake "Hosting" with their own address.
- **Real names.** The name on a friend request is checked with Mojang, so nobody can pose as one of your friends. You're warned if a different account uses a friend's old name.
- **Strangers never get in.** Banned, blocked and (in friends-only worlds) unknown players are turned away at login, before they're in the world. Guests never get commands. Friends-only also covers worlds opened with Minecraft's own "Open to LAN".
- **Hacked clients can't swamp the host.** Voice, relayed messages, world copies and rule changes have per-player limits. A game that keeps flooding is removed.
- **Nothing harmful lands on your PC.** Host packs must match their SHA-1 and size, and are cached under 2 GB. Fake piece counts are refused. Images and world files are checked for "bombs" before decoding. Received worlds can't bring co-host rights.
- **Block a player** from a friend request or their profile. Their messages are dropped unread and they can't join your worlds. Undo it in Settings.

## Limits

- Direct connections depend on your router and internet provider. The Host tab shows which ways work; when none do, the relay gets friends in.
- The relay (e4mc) is run by a third party. Traffic only passes through it when direct connections fail (or you choose "Always").
- **macOS microphone:** if the Voice tab says the mic is silent, give your launcher microphone access in System Settings → Privacy & Security → Microphone.
- e4mc 6.2.3 crashes on 26.3 *dedicated* servers (not on hosted worlds). JavaHost only uses it for hosted worlds.

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

## Credits

Icons: [Pixelarticons](https://github.com/halfmage/pixelarticons) by Gerrit Halfmann (MIT). See `src/main/resources/assets/javahost/ICONS-LICENSE.txt`. To change them, edit the list in `scripts/gen-icons.py` and run it on Pixelarticons' `svg` folder.

## Releasing an update

1. Bump `version=` in `gradle.properties` (and `PROTOCOL` in `JavaHost.java` only if old and new versions can't play together).
2. Commit and push, then run `scripts/release.sh "What's new"`.
3. GitHub builds everything and publishes a release with a signed `update.json`; players' games offer the update on their next launch.
4. For a fix everyone must have, use `scripts/release.sh --important "What's new"`. Players can't skip it (only "Remind me soon"), and older versions can't be restored with Settings → Restore.

Players can restore the previous version from Settings, and cancel that (or a pending update) any time before Minecraft closes.

The signing key's private half is the `UPDATE_SIGNING_KEY` Actions secret (a copy is kept outside the repo). Without it, releases can't be signed and the game ignores them.
