# JavaHost website

One page, `index.html`, with its CSS and JavaScript inline. No build step: edit the file. To look at it locally, serve this folder with any static server, for example `python3 -m http.server 4173 --directory website`.

## The design

Simple, and in the mod's own look rather than anyone else's. The page borrows what the JavaHost menu looks like in game: a dark blue room lit by two soft lights, glass cards with a light rim, rounded green buttons that glow, pill-shaped status labels, the Pixelify Sans headline font, and one accent colour.

Top to bottom, one idea per screen:

1. **Hero:** the headline, one sentence, a large download button, and a small scene: you are hosting, a friend is online, you press **Invite**, and they walk in. (It plays by itself after a few seconds if nobody presses it.)
2. **Hosting**, **Friends:** text beside a screenshot.
3. **Low ping:** the relay detour against the direct line.
4. **And more:** voice and mods with screenshots, then three small tiles.
5. **Install:** the mod's segmented control for Windows / macOS / Linux, the download button, three steps.
6. **Questions**, a closing line, the footer.

**The accent colour** is the visitor's to choose, as in the mod's settings: the five swatches in the top bar recolour the whole page and the host's hoodie, and the choice is remembered (`localStorage` key `javahost-accent`).

Rules to keep when editing:

- **Spacing** comes from one scale: 8, 16, 24, 32, 48, 64, 96, 128, 160. Sections are padded `clamp(96px, 11vw, 160px)`.
- **Two typefaces:** Pixelify Sans for headlines, buttons and labels; Geist for text (both from Google Fonts).
- **One accent** (`--a`, with a neighbouring colour `--a2` for the second light). Never hard-code green.
- **Short copy.** A headline of two to four words, one sentence under it. If something needs a paragraph, it belongs in `docs/HOW-IT-WORKS.md`.
- Adding something means taking something else out.

With "reduce motion" on, all motion is off and the hero shows the finished scene. If the script fails or is switched off, the content still shows.

## The 3D players

Real 3D built with CSS, no library and no model files. Each player is six parts (head, body, arms, legs); every part is a box whose faces show their patch of a 64x64 skin, plus a second, slightly larger box for the skin's outer layer, which is what gives hair, the hood, cuffs, shoes and the headset their depth. They breathe, blink and sway; their heads follow the mouse; they can be dragged round.

Moves are classes on the player (`is-wave`, `is-jump`, `is-spin`, `is-dance`, `is-sneak`, `is-cheer`, `is-walk`) with their keyframes in the styles and their lengths in `LENGTH` in the script. Clicking a player plays the next move; left alone they pick one now and then; a wave gets a wave back; hovering the hero's download button makes them cheer; choosing an accent makes the host spin.

To check a move in a still picture, add `?pose=dance:0.3` to the address (the move, and how many seconds into it).

The skins are original pixel art painted by `scripts/gen-website-skins.py` (not Mojang's textures): `media/skin-host-<accent>.png` for each accent, and `media/skin-friend.png`.

## Media

- `media/shots/`: screenshots of the real menus, taken by the automated game test at 1710x1070 (`build/run/clientGameTest/screenshots` after `./gradlew :26.3:runClientGameTest`). Player names are made up, and none shows an invite code or an address.
- `media/og.jpg`: the picture shown when the link is shared.
- `media/icons.svg`: the icon sprite (see `media/ICONS-LICENSE.txt`).

## Other files

- `relays.json`: the list of JavaHost relays that games read (see `relay/README.md`). It is served from this site, so editing it and pushing is all it takes to add a relay.

## Publishing

`.github/workflows/pages.yml` deploys this folder to GitHub Pages (https://ibrahimmas1.github.io/javahost/) whenever `website/` changes on `main`, or from a manual run.

The download buttons point at the latest GitHub release. When GitHub's API answers, they link straight to the right installer for the visitor's computer (`.exe`, `.dmg`, or the installer `.jar` on Linux) and the footer shows the newest version; without it they open the releases page.
