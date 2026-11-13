# JavaHost website

One page, `index.html`, with its CSS and JavaScript inline. It is **built**: edit the parts in `../website-src/parts/` and run

    sh website-src/tools/build.sh

from the repository root. That stitches the parts together and draws the page's pixel art into it (`website-src/tools/gen.py`, Python standard library only). Don't edit `index.html` by hand; the next build would overwrite it.

To look at it locally, serve this folder with any static server, for example `python3 -m http.server 4173 --directory website`.

## What's on it

A day in a small world, from morning to night:

- **Hero:** a floating island made of voxel blocks in CSS 3D (grass, a blossom tree, a plank house, a pond and waterfall, a flag, a campfire), with two 3D players standing on it. It assembles on load, turns slowly, leans toward the mouse and can be dragged. Petals drift, clouds move in parallax, and three notes float beside it.
- **Three steps**, then one scene per feature, each in its own colour of sky with pixel hills and clouds: hosting (green), friends (blue), low ping (an orange sunset, with an animated picture of relay versus direct), mods (pink), voice (purple).
- **Night:** a grid of the remaining features around the quick-menu screenshot, four numbers, the download box under the moon, the questions, and a campfire footer with the two players.

Throughout: sections slide in as you reach them, blocks drop into place, the numbers spin before landing, a bar under the top bar fills as you scroll, and stars twinkle. With "reduce motion" on, all motion is off and everything is simply there. If the script fails or is switched off, the content still shows.

## Everything is original

No Mojang or Microsoft artwork is used. The block textures, sky, sun, moon, clouds and hills are drawn by `gen.py`; the two player skins (`media/skin-host.png`, `media/skin-friend.png`) are painted by `scripts/gen-website-skins.py`. The players are six CSS boxes each, wearing a skin; there is no 3D library.

## Media

- `media/shots/`: screenshots of the real menus, taken by the automated game test at 1710x1070 (`build/run/clientGameTest/screenshots` after `./gradlew :26.3:runClientGameTest`). Player names are made up, and none shows an invite code or an address.
- `media/og.jpg`: the picture shown when the link is shared.
- `media/icons.svg`: the icon sprite (see `media/ICONS-LICENSE.txt`).

## Other files

- `relays.json`: the list of JavaHost relays that games read (see `relay/README.md`). It is served from this site, so editing it and pushing is all it takes to add a relay.

## Publishing

`.github/workflows/pages.yml` deploys this folder to GitHub Pages (https://ibrahimmas1.github.io/javahost/) whenever `website/` changes on `main`, or from a manual run.

The download buttons point at the latest GitHub release. When GitHub's API answers, they link straight to the right installer for the visitor's computer (`.exe`, `.dmg`, or the installer `.jar` on Linux) and the badge shows the newest version; without it they open the releases page.
