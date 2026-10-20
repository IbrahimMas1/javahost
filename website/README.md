# JavaHost website

Buildless landing page: `index.html` holds the CSS and JavaScript. Serve this folder with any static server, for example `python3 -m http.server 4173 --directory website` from the repository root.

The look follows the mod's own menu (dark glass, green accent, Pixelarticons) with Minecraft touches: Pixelify Sans headings (Google Fonts), blocky buttons, inventory-slot cards, a hotbar for the showcase, an XP-bar progress line and pixel-art grass edges drawn at load time (original art, no Mojang textures). No external scripts.

## Media

`media/showcase/` holds real footage recorded in real time in Minecraft 26.3 with JavaHost (vanilla graphics):

- `hero.mp4` (1280×720, loops behind the hero) and eight clips at 1440×810: `host`, `invite`, `voice`, `players`, `discover`, `modpack`, `mods`, `performance`. Each has a `.jpg` poster.
- `stills/*.jpg`: screenshots for the feature sections.

Player names are made up, and hosting uses documentation addresses, so no real names, codes or IPs appear.

The hero clip loads only after the page, never with reduced motion or Save-Data. Showcase clips load when chosen, pause offscreen, and the Pause motion button stops all video.

## Publishing

`.github/workflows/pages.yml` deploys this folder to GitHub Pages (https://ibrahimmas1.github.io/javahost/) whenever `website/` changes on `main`, or from a manual run. The canonical and Open Graph URLs in `index.html` use that address; change them if the site moves to its own domain (and add the domain under Settings → Pages).

Download buttons point at the latest GitHub release; when GitHub's API answers, they link straight to the `.exe` or `.dmg` and the badge shows the newest version.

## Accessibility and interactions

- Accent colour is saved locally (storage errors are handled), like the mod's Settings.
- Reduced motion and the Pause buttons (in the hero and the showcase window) stop the videos and the drifting signs; content stays readable.
- The hotbar is a tab list: click, arrow keys, or number keys 1–8 while it's on screen. Captions are announced.
- Install tabs use tab/tabpanel semantics with arrow keys; the right platform is picked automatically.
- Made-up reviews on oak signs drift in two rows on desktop and swipe one at a time on phones.
