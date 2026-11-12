# JavaHost website

A single page with no build step: `index.html` holds the CSS and JavaScript. To look at it locally, serve this folder with any static server, for example `python3 -m http.server 4173 --directory website` from the repository root.

## What's on it

Top to bottom: the headline with the download button and the menu standing beside it, a running strip, how it works in three steps, five feature scenes (hosting, friends, low ping, voice, mods), six more features in a grid, four numbers, install steps for Windows, Mac and Linux, and common questions.

The look: a near-black page drawn on a visible grid, very large pixel headlines (Silkscreen), Pixelify Sans for labels and buttons, the visitor's system font for body text, square buttons with a hard pixel shadow, and Pixelarticons (`media/icons.svg`). Each feature scene takes one of the mod's five accent colours. Fonts come from Google Fonts; there are no external scripts.

Motion, all in `index.html`: the headline rises in line by line with a blinking block cursor; the menu in the hero is tilted in 3D and turns toward the mouse, with three floating notes; pixel motes drift up behind it (a small canvas, paused when off screen); sections slide in as you reach them; an experience bar under the top bar fills as you scroll; the steps get a line of light; packets travel the two routes in the low-ping picture (SVG); a light follows the mouse across the grid tiles; the numbers spin before landing. With "reduce motion" on, all of it is switched off and everything is simply there.

## Media

- `media/shots/`: screenshots of the real menus, taken by the automated game test at 1710x1070 (`build/run/clientGameTest/screenshots` after `./gradlew :26.3:runClientGameTest`). Player names are made up, and none of them shows an invite code or an address. To refresh them, copy the new files over the old names.
- `media/og.jpg`: the picture shown when the link is shared.
- `media/icons.svg`: the icon sprite (see `media/ICONS-LICENSE.txt`).

## Other files

- `relays.json`: the list of JavaHost relays that games read (see `relay/README.md`). It is served from this site, so editing it and pushing is all it takes to add a relay.

## Publishing

`.github/workflows/pages.yml` deploys this folder to GitHub Pages (https://ibrahimmas1.github.io/javahost/) whenever `website/` changes on `main`, or from a manual run. The canonical and Open Graph addresses in `index.html` use that address; change them if the site moves to its own domain.

The download buttons point at the latest GitHub release. When GitHub's API answers, they link straight to the right installer for the visitor's computer (`.exe`, `.dmg`, or the installer `.jar` on Linux) and the badge shows the newest version; without it they open the releases page.

## Accessibility

- The computer picker is a real tab list: click, or use the arrow keys.
- Questions are plain `<details>` elements, so they work without JavaScript; so does the rest of the page (nothing is hidden unless the script is running to reveal it).
- With "reduce motion" on, animations, transitions and smooth scrolling are off.
