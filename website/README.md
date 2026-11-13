# JavaHost website

One page, `index.html`, with its CSS and JavaScript inline. No build step: edit the file. To look at it locally, serve this folder with any static server, for example `python3 -m http.server 4173 --directory website`.

## The design

Simple on purpose. A black page on one four-column grid (the faint vertical lines), with one idea per screen:

1. **Hero:** a three-line pixel headline, one sentence, one very large download button, and two 3D players.
2. **Hosting**, **Friends**, **Low ping:** each a label, a headline, a sentence and one picture, in its own colour.
3. **And more:** voice and mods side by side, then four small extras.
4. **Install:** the download button again, and three steps.
5. **FAQ**, then the footer.

Rules to keep when editing:

- **Spacing** comes from one scale: 8, 16, 24, 32, 48, 64, 96, 128, 160. Sections are padded `clamp(96px, 10vw, 160px)`.
- **Three kinds of type:** Silkscreen for headlines, Geist for text, Geist Mono for small labels (all from Google Fonts).
- **One accent per section** (`--a`), used for the label, the headline and a soft light at the top of the section. Green is the brand colour and the only colour buttons use.
- **Short copy.** A headline of two to four words, one sentence under it. If something needs a paragraph, it belongs in `docs/HOW-IT-WORKS.md`.
- Adding something means taking something else out.

Motion is light: the headline rises in, sections ease in as you reach them, packets travel the two routes in the low-ping picture, the footer wordmark drifts. With "reduce motion" on it is all off. If the script fails or is switched off, the content still shows.

## The 3D players

The two characters in the hero are real 3D built with CSS: six boxes each, every face showing its patch of a 64x64 skin. No 3D library. Their heads follow the mouse, they can be dragged round, and one waves. The skins (`media/skin-host.png`, `media/skin-friend.png`) are original pixel art painted by `scripts/gen-website-skins.py`, not Mojang's textures.

## Media

- `media/shots/`: screenshots of the real menus, taken by the automated game test at 1710x1070 (`build/run/clientGameTest/screenshots` after `./gradlew :26.3:runClientGameTest`). Player names are made up, and none shows an invite code or an address.
- `media/og.jpg`: the picture shown when the link is shared.
- `media/icons.svg`: the icon sprite (see `media/ICONS-LICENSE.txt`).

## Other files

- `relays.json`: the list of JavaHost relays that games read (see `relay/README.md`). It is served from this site, so editing it and pushing is all it takes to add a relay.

## Publishing

`.github/workflows/pages.yml` deploys this folder to GitHub Pages (https://ibrahimmas1.github.io/javahost/) whenever `website/` changes on `main`, or from a manual run.

The download buttons point at the latest GitHub release. When GitHub's API answers, they link straight to the right installer for the visitor's computer (`.exe`, `.dmg`, or the installer `.jar` on Linux) and the footer shows the newest version; without it they open the releases page.
