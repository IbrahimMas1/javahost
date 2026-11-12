# JavaHost website

A single page with no build step: `index.html` holds the CSS and JavaScript. To look at it locally, serve this folder with any static server, for example `python3 -m http.server 4173 --directory website` from the repository root.

## What's on it

Top to bottom: the headline with the download button, how it works in three steps, a small gallery of the menus, six features, install steps for Windows, Mac and Linux, and common questions. It is meant to stay this short.

The look follows the mod's menu: a dark room lit by two soft lights, glass cards, the green accent, Pixelify Sans for headings and buttons (Google Fonts) and Pixelarticons (`media/icons.svg`). Body text uses the visitor's system font. There are no external scripts.

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

- The gallery and the computer picker are real tab lists: click, or use the arrow keys.
- Questions are plain `<details>` elements, so they work without JavaScript.
- With "reduce motion" on, transitions and smooth scrolling are off.
