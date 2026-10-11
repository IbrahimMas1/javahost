# JavaHost

A Fabric mod for Minecraft **26.3** (Java Edition).

- **Friends and one-click invites.** Add a friend by their Minecraft name. Click **Invite** and their game shows "Alex invited you to play" with a **Join** button. JavaHost opens your world, picks the best connection and falls back automatically. You don't share codes or links.
- **Host your world from your own PC.** Open your singleplayer world and send friends an invite code. You don't need a rented server. JavaHost tries every way to reach you in turn:
  1. **Internet:** your router opens the port automatically (UPnP).
  2. **IPv6:** works even when your internet provider shares one public address between homes (CGNAT).
  3. **Relay:** a free relay (e4mc) for when nothing direct works. Anyone can join this way, even without mods.
  4. **Same Wi-Fi:** for people at home.
- **Proximity voice chat.** Push-to-talk (`V`) or voice activation, mute (`M`). Voices fade with distance and come from the speaker's direction.
- **Shared look.** Guests get the host's resource packs and **shader, including its settings** (with Iris). They're asked first, and everything goes back to normal when they leave.
- **Performance.** One click installs Sodium, Lithium, FerriteCore, ImmediatelyFast and Entity Culling for your exact version, checked against Modrinth's hashes. **Quick Tune** gives FPS-friendly settings and can undo them.
- **Modern menu.** Press `J` in game, or click **JavaHost** on the title or pause screen.
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
- **Invites are encrypted** with a key only you and that friend share. Nobody else can read your world's address.
- **Friends-only worlds (default):** players who aren't your friends are turned away when they try to join. Minecraft itself verifies accounts. You can switch this off on the Host tab.
- **Offline friends:** messages are kept for 12 hours, so a friend who's offline gets your request when they next play.

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
