# JavaHost website

One page, `index.html`, with its CSS and JavaScript inline. It is **built**: edit the parts in `../website-src/parts/` and run

    sh website-src/tools/build.sh

from the repository root. That stitches the parts together and draws the page's pixel art into it (`website-src/tools/gen.py`, Python standard library only). Don't edit `index.html` by hand; the next build would overwrite it.

To look at it locally, serve this folder with any static server, for example `python3 -m http.server 4173 --directory website`.

## What's on it

A day in a small world, from morning to night, with one idea per screen:

1. **Hero (morning):** the headline, two buttons, and a floating island made of voxel blocks in CSS 3D with two 3D players on it. It assembles on load, turns slowly, leans toward the mouse and can be dragged.
2. **How it works:** three cards.
3. **Features, pinned:** the game window stays still while Hosting, Friends, Voice and Mods scroll past beside it. The screenshot changes with a stepped pixel wipe, the sky behind changes colour (green, blue, purple, pink), a rail and a big numeral show the step, and one label points at the control that matters. On phones, with reduced motion or without JavaScript it is four stacked bands.
4. **Low ping (sunset):** an animated picture of the relay detour against the direct line.
5. **Try it (dusk):** a working miniature of the mod's menu, marked as a demo. Start hosting shows a code, inviting a friend brings their 3D character in, voice shows talking bars. Nothing is sent anywhere.
6. **Night:** the remaining features, four numbers, the download box, the questions, and a campfire footer.

Spacing follows one 8 px scale: sections are padded `clamp(96px, 11vw, 168px)`, content is at most 1200 px wide, text lines about 60 characters, and decorations never sit on text or screenshots. Keep it that way: when adding something, take something else out.

With "reduce motion" on, all motion is off and everything is simply there. If the script fails or is switched off, the content still shows.

Test hooks (harmless, for checking renders): `?step=1..4`, `?at=<section id>&off=N`, `?demo=hosting|invited|voice|party`, `?tab=home|friends|host|voice|mods`, `?still`.

## Everything is original

No Mojang or Microsoft artwork is used. The block textures, sky, sun, moon, clouds and hills are drawn by `gen.py`; the player skins (`media/skin-*.png`) are original pixel art too (the first two are painted by `scripts/gen-website-skins.py`). The players are six CSS boxes each, wearing a skin; there is no 3D library.

## Media

- `media/shots/`: screenshots of the real menus, taken by the automated game test at 1710x1070 (`build/run/clientGameTest/screenshots` after `./gradlew :26.3:runClientGameTest`). Player names are made up, and none shows an invite code or an address.
- `media/og.jpg`: the picture shown when the link is shared.
- `media/icons.svg`: the icon sprite (see `media/ICONS-LICENSE.txt`).

## Other files

- `relays.json`: the list of JavaHost relays that games read (see `relay/README.md`). It is served from this site, so editing it and pushing is all it takes to add a relay.

## Publishing

`.github/workflows/pages.yml` deploys this folder to GitHub Pages (https://ibrahimmas1.github.io/javahost/) whenever `website/` changes on `main`, or from a manual run.

The download buttons point at the latest GitHub release. When GitHub's API answers, they link straight to the right installer for the visitor's computer (`.exe`, `.dmg`, or the installer `.jar` on Linux) and the badge shows the newest version; without it they open the releases page.
