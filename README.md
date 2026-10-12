# JavaHost

A Fabric mod for Minecraft **26.3** (Java Edition).

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
- **Party voice.** Press `B` (or use the Voice tab) to talk to everyone in the world, wherever they are, instead of only nearby players.
- **Automatic world backups.** When a friend joins your world, JavaHost saves a backup first (at most every 30 minutes, newest 5 kept). Restore one from the Host tab: your current world is kept as a copy, never deleted.
- **Same-world fast lane.** When you and a friend are in the same world, chat and screenshots go through the game connection: instant, full size, and they don't count toward the daily limit.
- **Host controls.** Change a guest's game mode, kick or ban them, and set a player limit.
- **Shared look.** Guests get the host's resource packs and **shader, including its settings** (with Iris). They're asked first, and everything goes back to normal when they leave.
- **Performance.** One click installs Sodium, Lithium, FerriteCore, ImmediatelyFast and Entity Culling for your exact version, checked against Modrinth's hashes. **Quick Tune** gives FPS-friendly settings and can undo them. With Sodium installed, choose **Simple** (Minecraft's own Video Settings) or **Sodium** (detailed) under Settings; each screen has a button to switch. **Quick settings** for render distance, max frame rate and graphics sit right on the Performance tab. There's an optional FPS / ping / memory overlay.
- **Modern menu.** Press `J` in game, or use the **dock** down the right side of the title and pause screens: Host, Friends, Screenshots, Performance, Settings and your Account. Badges show what's new (including notifications, under Friends), and Friends glows when a friend's world is ready to join. The title screen also shows your 3D skin (drag to turn it) and the JavaHost version. Pick an accent colour in Settings.
- **Account page.** Your 3D skin, how friends see you, an **Appear offline** switch, and how many friend messages you've sent today.
- Friends need a Microsoft-account login, which every normal Minecraft purchase has.

## Install (recommended): the JavaHost Installer

1. Open the Minecraft Launcher once if you never have, then close it.
2. Run **JavaHost Installer**:
   - **Mac:** open the `.dmg` and run the app. It's unsigned, so the first time you need to right-click → **Open**.
   - **Windows:** run the `.exe`. If SmartScreen appears, click **More info → Run anyway**.
3. Choose add-ons and click **Install**.
4. In the Minecraft Launcher, pick the **JavaHost** profile and press Play.

The installer:
- installs the official **Fabric loader**, **Fabric API**, JavaHost and the add-ons you picked;
- puts them in their own **JavaHost** profile with its own folder, so your normal Minecraft is never touched;
- can copy your settings, packs and worlds into that folder.

Run it again to update or uninstall.

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

## Limits

- Direct connections depend on your router and internet provider. The Host tab shows which ways work; when none do, the relay gets friends in.
- The relay (e4mc) is run by a third party. Traffic only passes through it when direct connections fail (or you choose "Always").
- **macOS microphone:** if the Voice tab says the mic is silent, give your launcher microphone access in System Settings → Privacy & Security → Microphone.
- e4mc 6.2.3 crashes on 26.3 *dedicated* servers (not on hosted worlds). JavaHost only uses it for hosted worlds.

## Build and test

Requires Java 25.

```bash
./gradlew build                      # mod jar in build/libs/
./gradlew runClientGameTest          # in-game tests + UI screenshots
scripts/multiplayer-test.sh          # two real clients: host + guest over LAN, IPv6 and the relay
./gradlew :installer:test            # installer: real install/update/uninstall into a fake .minecraft
./gradlew :installer:jpackageMac     # macOS .dmg
```

The GitHub workflow in `.github/workflows/build.yml` builds the Windows `.exe` and the macOS `.dmg`.

## Credits

Icons: [Pixelarticons](https://github.com/halfmage/pixelarticons) by Gerrit Halfmann (MIT). See `src/main/resources/assets/javahost/ICONS-LICENSE.txt`. To change them, edit the list in `scripts/gen-icons.py` and run it on Pixelarticons' `svg` folder.
