# How JavaHost works

The details behind the features in the [README](../README.md): how friends connect without a JavaHost server, public and modded worlds, world options, your mods and skins, safety, and known limits.

## Friends without a JavaHost server

- Friend requests and invites travel through **ntfy.sh**, a free public message relay. Each player has a mailbox named after their account ID.
- **Requests are signed** with your Mojang-certified chat key (the same one Minecraft signs chat with), so nobody can pretend to be you.
- **Invites, chat and screenshots are encrypted** with a key only you and that friend share. Nobody else can read your world's address or your messages.
- **Version check:** your friends list shows when a friend runs a different JavaHost version, so you know who needs to update.
- **Online status** is encrypted with a separate key that you give each friend. It changes when you remove someone, so they stop seeing you.
- Your game checks the connection to the relay and reconnects by itself if it goes quiet, so invites don't get lost.
- **Friends-only worlds (default):** players who aren't your friends are turned away when they try to join. Minecraft itself verifies accounts. You can switch this off on the World page (General → Who can join).
- **Offline friends:** messages are kept for 12 hours, so a friend who's offline gets your request when they next play. Screenshots are kept for 3 hours.
- ntfy.sh is free, so it limits each internet connection to about 250 *sent* messages a day (receiving is free). Normal play uses far fewer: your online status sends one every 30 minutes or when it changes. The Account page shows today's count, and JavaHost tells you if you hit the limit.

## Direct connections between friends

- When a friend joins, the two games first try to connect **straight to each other**, with nothing in between. That gives the lowest ping your two internet connections allow, which matters most far from the relay's servers (the Middle East, much of Asia, Africa, South America).
- Each game asks a public STUN server how its connection looks from the internet, the two swap those addresses over the same sealed messages invites use, and then both send to each other at the same moment. Each router sees its own player speaking first and lets the answers back in. No port is opened on your router and nothing needs setting up.
- The game's normal connection then travels over that link, with lost packets re-sent quickly so a shaky line doesn't freeze the game. It is the same connection Minecraft always makes: your login is still checked and the traffic is still encrypted by the game.
- It works on most home connections. A few routers and some mobile networks give every destination a different port, which blocks it: after a few seconds the join carries on with the other ways in (your router's open port, IPv6, the relay).
- **When there's no direct way,** the two games meet at a JavaHost relay if one is listed: a small server that passes their packets on without being able to read them. Both games measure their ping to each relay and pick the one nearest to both. Anyone can run one ([relay/README.md](../relay/README.md)). With no JavaHost relay in reach, the join goes on to the other ways in, ending with e4mc's relay.
- This is for friends, who have a private channel to arrange it. Invite codes and public worlds use the other ways in.

## Public worlds (beta)

- **Public worlds** lists worlds people have opened to everyone. Each shows who's hosting, the title, tags, players and limit, ping (measured the same way as Minecraft's server list) and the region. Search, filter by tag, and sort by busiest or closest.
- **Open to the public** from the World page (General → Public listing) or from Public worlds. Give it a title, a description, tags, a player limit, and choose whether visitors can use voice (off by default). JavaHost makes a backup first.
- **Visitors can't wreck anything.** Strangers join in Adventure mode: they can look around, fight and use doors, but can't break or place blocks until you click **Trust**. Your friends play normally. Kick, ban, block and backups all still work.
- **Your address stays private.** Public worlds are reached only through the relay; no router port or IPv6 opening is made. Joining checks the world really belongs to the listed host.
- **Reports and moderation.** Report a world from Public worlds or from inside it. Three reports from different players hide it until it's reviewed. Listings are checked for links, addresses and offensive words. Hosts sign in with their real Minecraft account, so nobody can list in someone else's name.
- Accounts with online play turned off (parental controls, or banned by Mojang) can't see or open public worlds.
- The list itself is a small service in `directory/` (a Cloudflare Worker with a D1 database). Moderate it with `scripts/moderate.sh`.

## Modded worlds

- **Play with mods together.** Host with any Fabric mods for your Minecraft version (Naturalist, for example). When a friend who's missing some joins, they don't get Minecraft's "registry mismatch" error. Instead they see which mods the world uses, with sizes, and a **Download & restart** button.
- **The very same files.** JavaHost matches each of the host's mods to its exact Modrinth version by hash. Friends download those files from Modrinth, and each one is checked against Modrinth's hash and the host's. Nothing a player sends is ever run. Mods that aren't on Modrinth are listed with a link so friends can get them themselves.
- **Only what's needed.** Client-only mods (Sodium, Iris, minimaps) and host-side mods (Lithium) aren't asked of friends: only mods that add blocks, items, mobs and so on, plus the libraries they need.
- **Your own mods are safe.** Downloaded mods are kept as a *mod set*. The switch happens when Minecraft closes: clashing mods are put aside (never deleted) and everything is put back if anything goes wrong. When you press Play again, JavaHost takes you straight back into your friend's world. **Back to my mods** in Mods → My mods restores your set.
- **Browse mods and modpacks.** The Mods tab works like a store: search Modrinth for gameplay mods and modpacks (performance mods and libraries are left out), filter by category (Adventure, Magic, Technology...), or show only the ones that run on your version. Each one opens its own page in the menu with its icon, description, loaders and Minecraft versions. Ones made for your version install with one click (mods go into your mods folder, modpacks become a mod set with the pack's settings); others say where they run, such as "Runs on NeoForge 1.21.1", so you know which version to pick in the installer. Files come only from the hosts Modrinth allows, and every one is hash-checked. Friends who join get the mods they need automatically.
- **Downloads in the menu.** Installs queue up and download one after another. A download icon next to the bell shows a progress ring and opens the list, where you can cancel one. When a mod is ready, a notification says so, with a button to close Minecraft: mods load when the game starts, so press Play again to use them. My mods lists your downloads and every mod in your mods folder, active or waiting for a restart. A mod JavaHost installed can be removed from its row's menu: it keeps working until you close Minecraft, then it's deleted (Keep changes your mind). Mods that others need, such as Sodium for Iris, can't be removed while those are installed.
- Friends see "Hosting "World" · 3/8 · 12 mods", and Public worlds shows "Modded (12)" on listed worlds.
- Only Fabric works: Forge/NeoForge modpacks can't load JavaHost.

## World options and operators

- **One page for the world.** The World page shows the hosting card first, then four parts: **Members** (each player's game mode, an Operator switch, and a menu with Trust, Send a world, Kick and Ban), **General** (difficulty, the default game mode new players get, Cheats, and who can join), **Rules** and **Sharing** (resource packs, shader settings and voice). A more menu has Recent changes, Backups, Connection details and Advanced. Guests see the page read-only; operators can change what they're allowed to.
- **Operators.** The host can make any player in the world an operator (called co-hosts before 2.0; existing co-hosts became operators). Operators can change difficulty and world rules, and they get commands while Cheats is on. Only the host can choose operators, switch Cheats, lock the rules, or choose whether changes are announced.
- **How commands are decided.** In a world you opened from your own game, JavaHost answers the game's own permission question: you keep whatever the game gives you; an operator gets Minecraft's "gamemasters" level (/gamemode, /give, /tp, /time and the like, not /op, /ban or /stop) only while Cheats is on; every other guest gets none. The game's ops list and Open to LAN's "Allow Cheats" don't give guests commands. Dedicated servers are left alone.
- **Cheats per version.** From Minecraft 26.2 the switch changes the world's own cheats setting, for everyone including you. On older versions the world's setting can't be changed while it's open, so the switch only gives operators commands, and a world created with cheats on keeps them (the switch shows as locked).
- **Every game rule.** Rules lists the running Minecraft version's own rules with the game's names, the common ones first (keep inventory, mob griefing, daylight, weather, PvP and so on) and the rest by category, with a search. Only values a rule accepts are taken. The host can **Lock** the rules for a challenge run: while locked they're held at their values, even against /gamerule or /difficulty.
- **Recent changes.** Every change to rules, operators and the lock is listed. Announcing changes in chat is off unless the host turns it on there.
- **How it reaches other games.** Hosts on 2.0 send a `world_state` packet with all of this (cheats, operators, your role, difficulty, default game mode, every rule) to games that understand it, and send it again when a command changes something. The older `rules_state` packet is unchanged, so players on older versions still see the rules as before. With a host on an older version, the page shows the older set of rules.

## Your mods: on, off and Vanilla mode

- **A switch for every mod.** Mods → My mods lists every mod jar with a switch. Mods load when the game starts, so changes wait for the game to close; a bar shows what's waiting, with Undo and Close game. Turning off a library asks first and turns off the mods that need it too; turning a mod on brings back what it needs. JavaHost and Fabric API always stay on.
- **Nothing is deleted.** A mod switched off is moved to `javahost/mods-off/mods/` and moved back when switched on.
- **Vanilla mode** (My mods or Settings) puts every mod aside in `javahost/vanilla/mods/` except JavaHost, Fabric API, e4mc and what they need. You can choose to keep the performance mods too. While it's on, JavaHost hides itself: one small JavaHost button on the title screen, nothing on the pause menu, no on-screen voice status or talking marks, and notices wait under the bell. J still opens the menu, and friends and voice keep working. **Bring my mods back** restores the mods folder exactly as it was. Mods switched off one by one stay off either way. Fabric itself stays installed, so the title screen still says the game is modded. Worlds that use blocks or items from mods that are put aside can lose them if you open them in Vanilla mode; the confirm says so.
- **One helper after the game closes.** As Minecraft closes, JavaHost starts one small Java program (a copy of its own jar at `javahost/after-exit.jar`, log in `javahost/after-exit.log`). It waits for the game to exit, deletes the mods you removed, then makes at most one move: a mod set switch, else Vanilla mode going on or off, else your on/off switches. A mod set switch drops waiting on/off changes, and the next start says so.
- **Rollback.** Nothing is overwritten or deleted until the whole move has worked and its record is written. If any step fails, every step is undone and the mods folder is left as it was. When two different files have the same name, the stored copy is kept in `replaced/` inside that store (named with the time) instead of being overwritten; JavaHost never cleans that folder up.
- **Known gap.** JavaHost reads a jar's own `fabric.mod.json`. For a jar that's already off, mods bundled inside it aren't known, so a mod that needs one of those may not be offered to come back with it.

## Skins

- **Change your skin from the menu.** Click your skin on Home or the title screen (or More → Skins). Your saved skins are in a grid, with a turnable preview, classic or slim arms, the capes your account owns, and **Wear**.
- **Adding skins.** Drop a PNG on the game window, copy any player's skin by their name, or put PNGs in the skins folder (`javahost/skins/`). There's no file picker. Pictures must be 64×64 (old 64×32 skins are converted), and up to 200 are kept.
- **Who it talks to.** Wearing a skin or a cape goes through Minecraft's own profile service (`api.minecraftservices.com`) with your game's session, the same way the launcher does it. The session token is only ever sent there, over HTTPS; redirects aren't followed and the token is never logged or shown. Looking up a player's skin by name uses Minecraft's public profile lookup and `sessionserver.mojang.com`, and skin pictures are only downloaded from `textures.minecraft.net`.
- **When others see it.** You see the new skin at once. Others see it when you next join their world. Guests of a world you host see it after you restart the game, because the game reads your profile once when it starts.

## Quiet while playing

- **Fewer pop-ups.** With **Quiet notifications** on (the default), routine notices (backups, shared packs, finished downloads, updates, someone joining) go under the bell with no pop-up or sound. Pop-ups are kept for things you did a moment ago, people who want you, and problems.
- **Invites during play** are a short pop-up ("Press J to answer"). The Join window waits until a menu is open (pause, the JavaHost menu or the title screen), like friend requests and world offers.
- **Updates** are only offered on the title screen.
- **Voice.** The Muted and Party voice labels are off by default. The microphone and speakers only open while another player is in the world, and close about 5 seconds after you're alone.
- Settings → While playing has the switches: Quiet notifications, sounds, voice status, who's talking, FPS overlay, and the pause menu buttons. Settings → Look chooses what JavaHost adds to the title screen: Full (the menu buttons, your 3D skin and friends), Dock (the buttons only) or Off (one small button).

## Safety

- **Joining checks it's really your friend's world.** Each player has a signing key that only their own game holds. When you join a friend, their game must prove the world is theirs. A fake server, or some other PC on your network, is left at once and the next way in is tried. Friends on older versions need to update before you can join them.
- **Online status can't be faked.** Statuses are signed by the friend who sent them, so another friend can't post a fake "Hosting" with their own address.
- **Real names.** The name on a friend request is checked with Mojang, so nobody can pose as one of your friends. You're warned if a different account uses a friend's old name.
- **Strangers never get in.** Banned, blocked and (in friends-only worlds) unknown players are turned away at login, before they're in the world. Guests get no commands unless the host makes them an operator and Cheats is on. Friends-only also covers worlds opened with Minecraft's own "Open to LAN".
- **Hacked clients can't swamp the host.** Voice, relayed messages, world copies and rule changes have per-player limits. A game that keeps flooding is removed.
- **Nothing harmful lands on your PC.** Host packs must match their SHA-1 and size, and are cached under 2 GB. Fake piece counts are refused. Images and world files are checked for "bombs" before decoding. Received worlds can't bring operator rights.
- **Block a player** from a friend request or their profile. Their messages are dropped unread and they can't join your worlds. Undo it in Settings.

## Limits

- Direct connections depend on both players' routers and internet providers. The World page's Connection details (in its more menu) show which ways work; when none do, the relay gets friends in, at a higher ping.
- The relay (e4mc) is run by a third party. Traffic only passes through it when direct connections fail (or you choose "Always").
- **macOS microphone:** if the Voice page says the mic is silent, give your launcher microphone access in System Settings → Privacy & Security → Microphone.
- e4mc 6.2.3 crashes on 26.3 *dedicated* servers (not on hosted worlds). JavaHost only uses it for hosted worlds.

## More features

- **Automatic updates.** JavaHost checks for new versions when the game starts (and every few hours). It asks before updating, only on the title screen (in a world it's a quiet notice under the bell); if you'd rather wait, it tells you whether you can still play with friends who updated. Updates are signed, checked file by file, and installed when Minecraft closes. Settings has Check for updates and Go back to the previous version.
- **Send a world copy.** While you and a friend are in the same world, send them a copy of the world you're in (as it is right now), any other saved world, or a backup. It travels over the game connection (LAN, direct or the relay), encrypted so only the two of you can read it, with progress and Cancel in the notification center. Your friend accepts first; the copy lands in their Singleplayer worlds as "World (from you)", and they play as themselves there (their own inventory if they've played it before). Your character is kept too, so when you join their copy you're where you left off.
- **Welcome tour, debug report, screenshot tools.** A short tour the first time; Settings → Copy debug report (no IPs or names) for asking for help; favourite, rename and delete screenshots.
- **Same-world fast lane.** When you and a friend are in the same world, chat and screenshots go through the game connection: instant, full size, and they don't count toward the daily limit.
- **Account page.** Your 3D skin, how friends see you, an **Appear offline** switch, and how many friend messages you've sent today.
