# How JavaHost works

The details behind the features in the [README](../README.md): how friends connect without a JavaHost server, public and modded worlds, safety, and known limits.

## Friends without a JavaHost server

- Friend requests and invites travel through **ntfy.sh**, a free public message relay. Each player has a mailbox named after their account ID.
- **Requests are signed** with your Mojang-certified chat key (the same one Minecraft signs chat with), so nobody can pretend to be you.
- **Invites, chat and screenshots are encrypted** with a key only you and that friend share. Nobody else can read your world's address or your messages.
- **Version check:** your friends list shows when a friend runs a different JavaHost version, so you know who needs to update.
- **Online status** is encrypted with a separate key that you give each friend. It changes when you remove someone, so they stop seeing you.
- Your game checks the connection to the relay and reconnects by itself if it goes quiet, so invites don't get lost.
- **Friends-only worlds (default):** players who aren't your friends are turned away when they try to join. Minecraft itself verifies accounts. You can switch this off on the Host tab.
- **Offline friends:** messages are kept for 12 hours, so a friend who's offline gets your request when they next play. Screenshots are kept for 3 hours.
- ntfy.sh is free, so it limits each internet connection to about 250 *sent* messages a day (receiving is free). Normal play uses far fewer: your online status sends one every 30 minutes or when it changes. The Account page shows today's count, and JavaHost tells you if you hit the limit.

## Public worlds (beta)

- **Discover** lists worlds people have opened to everyone. Each shows who's hosting, the title, tags, players and limit, ping (measured the same way as Minecraft's server list) and the region. Search, filter by tag, and sort by busiest or closest.
- **Open to the public** from the Host tab or Discover. Give it a title, a description, tags, a player limit, and choose whether visitors can use voice (off by default). JavaHost makes a backup first.
- **Visitors can't wreck anything.** Strangers join in Adventure mode: they can look around, fight and use doors, but can't break or place blocks until you click **Trust**. Your friends play normally. Kick, ban, block and backups all still work.
- **Your address stays private.** Public worlds are reached only through the relay; no router port or IPv6 opening is made. Joining checks the world really belongs to the listed host.
- **Reports and moderation.** Report a world from Discover or from inside it. Three reports from different players hide it until it's reviewed. Listings are checked for links, addresses and offensive words. Hosts sign in with their real Minecraft account, so nobody can list in someone else's name.
- Accounts with online play turned off (parental controls, or banned by Mojang) can't see or open public worlds.
- The list itself is a small service in `directory/` (a Cloudflare Worker with a D1 database). Moderate it with `scripts/moderate.sh`.

## Modded worlds

- **Play with mods together.** Host with any Fabric mods for your Minecraft version (Naturalist, for example). When a friend who's missing some joins, they don't get Minecraft's "registry mismatch" error. Instead they see which mods the world uses, with sizes, and a **Download & restart** button.
- **The very same files.** JavaHost matches each of the host's mods to its exact Modrinth version by hash. Friends download those files from Modrinth, and each one is checked against Modrinth's hash and the host's. Nothing a player sends is ever run. Mods that aren't on Modrinth are listed with a link so friends can get them themselves.
- **Only what's needed.** Client-only mods (Sodium, Iris, minimaps) and host-side mods (Lithium) aren't asked of friends: only mods that add blocks, items, mobs and so on, plus the libraries they need.
- **Your own mods are safe.** Downloaded mods are kept as a *mod set*. The switch happens when Minecraft closes: clashing mods are put aside (never deleted) and everything is put back if anything goes wrong. When you press Play again, JavaHost takes you straight back into your friend's world. **Back to my mods** on the new **Mods** tab restores your set.
- **Browse mods and modpacks.** The Mods tab works like a store: search Modrinth for gameplay mods and modpacks (performance mods and libraries are left out), filter by category (Adventure, Magic, Technology...), or show only the ones that run on your version. Each one opens its own page in the menu with its icon, description, loaders and Minecraft versions. Ones made for your version install with one click (mods go into your mods folder, modpacks become a mod set with the pack's settings); others say where they run, such as "Runs on NeoForge 1.21.1", so you know which version to pick in the installer. Files come only from the hosts Modrinth allows, and every one is hash-checked. Friends who join get the mods they need automatically.
- **Downloads in the menu.** Installs queue up and download one after another. A download icon next to the bell shows a progress ring and opens the list, where you can cancel one. When a mod is ready, a notification says so, with a button to close Minecraft: mods load when the game starts, so press Play again to use them. Installed lists your downloads and every mod JavaHost put in, active or waiting for a restart. Remove one with its bin button: it keeps working until you close Minecraft, then it's deleted (Keep changes your mind). Mods that others need, such as Sodium for Iris, can't be removed while those are installed.
- Friends see "Hosting "World" · 3/8 · 12 mods", and Discover shows "Modded (12)" on public worlds.
- Only Fabric works: Forge/NeoForge modpacks can't load JavaHost.

## Safety

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

## More features

- **Automatic updates.** JavaHost checks for new versions when the game starts (and every few hours). It asks before updating, never mid-game; if you'd rather wait, it tells you whether you can still play with friends who updated. Updates are signed, checked file by file, and installed when Minecraft closes. Settings has Check for updates and Go back to the previous version.
- **World rules.** Difficulty, keep inventory, PvP, daylight and more, on their own page from the Host tab. Only the host (and friends they make co-hosts) can change them, every change is announced in chat and listed, and the host can lock the rules for a challenge run.
- **Send a world copy.** While you and a friend are in the same world, send them a copy of the world you're in (as it is right now), any other saved world, or a backup. It travels over the game connection (LAN, direct or the relay), encrypted so only the two of you can read it, with progress and Cancel in the notification center. Your friend accepts first; the copy lands in their Singleplayer worlds as "World (from you)", and they play as themselves there (their own inventory if they've played it before). Your character is kept too, so when you join their copy you're where you left off.
- **Welcome tour, debug report, screenshot tools.** A short tour the first time; Settings → Copy debug report (no IPs or names) for asking for help; favourite, rename and delete screenshots.
- **Same-world fast lane.** When you and a friend are in the same world, chat and screenshots go through the game connection: instant, full size, and they don't count toward the daily limit.
- **Account page.** Your 3D skin, how friends see you, an **Appear offline** switch, and how many friend messages you've sent today.
