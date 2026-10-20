# JavaHost

A Fabric mod for playing with friends in your own singleplayer world, without renting a server. Press J, click Start hosting, then Invite, and your friend gets a Join button in their game.

[Download](https://github.com/IbrahimMas1/javahost/releases/latest) · Minecraft 1.21 to 26.3 · [How it works](docs/HOW-IT-WORKS.md)

<img src="docs/media/demo.gif" alt="Inviting a friend and them joining the world" width="640">

## What's in it

- Hosting from your PC. It tries UPnP, IPv6, the e4mc relay and LAN, so most people never have to touch their router.
- A friends list with invites, chat and screenshots. Messages are end-to-end encrypted and there's no JavaHost server in the middle.
- Proximity voice chat (hold V), or party voice where everyone hears everyone.
- A mod store for Modrinth mods and modpacks. If you host a modded world, friends who join get the same mods downloaded for them.
- Public worlds (beta): list your world, or join someone else's. Visitors stay in Adventure mode until you trust them.
- Host controls: game modes, kick and ban, world rules you can lock, automatic backups.

It also keeps itself up to date.

<table>
<tr>
<td><img src="docs/media/host.jpg" alt="Host tab"></td>
<td><img src="docs/media/mods.jpg" alt="Mods tab"></td>
</tr>
<tr>
<td><img src="docs/media/voice.jpg" alt="Voice chat in game"></td>
<td><img src="docs/media/discover.jpg" alt="Discover tab"></td>
</tr>
</table>

## Install

1. Open the Minecraft Launcher once, then close it.
2. Get the installer from the [latest release](https://github.com/IbrahimMas1/javahost/releases/latest): the `.exe` on Windows (SmartScreen: More info, then Run anyway), the `.dmg` on Mac (right-click and Open the first time), or the `.jar` anywhere with Java 21.
3. Pick your Minecraft version, click Install, then play the JavaHost profile in the launcher.

It installs into its own profile and folder, so your normal Minecraft isn't touched. Your friends need it too for invites and voice.

Supported: 26.3, 26.2, 26.1 to 26.1.2, 1.21.9 to 1.21.11, 1.21.6 to 1.21.8, 1.21.4 and 1.21 to 1.21.1. You can only join a friend on the same Minecraft version. Fabric only.

## Help

- Found a bug? [Open an issue](https://github.com/IbrahimMas1/javahost/issues/new/choose) and paste the report from Settings → Copy debug report.
- No microphone on Mac? Allow the Minecraft Launcher in System Settings → Privacy & Security → Microphone.
- Building from source or sending a pull request: see [CONTRIBUTING.md](CONTRIBUTING.md).

MIT licensed. Uses Concentus, TwelveMonkeys ImageIO, Pixelarticons and e4mc; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
