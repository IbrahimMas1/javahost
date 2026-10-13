// Updates src/mojang-keys.js from Mojang (run on your own computer; Mojang refuses Cloudflare's servers).
import { writeFileSync } from "node:fs";
const response = await fetch("https://api.minecraftservices.com/publickeys");
const keys = (await response.json()).playerCertificateKeys.map((k) => k.publicKey);
writeFileSync(new URL("../src/mojang-keys.js", import.meta.url), `// Mojang's public keys for player certificates (the keys Minecraft signs chat with are certified by these).
// From https://api.minecraftservices.com/publickeys ("playerCertificateKeys"). Cloudflare can't fetch them (Mojang
// refuses its servers), so they're kept here; refresh with: node dev/refresh-mojang-keys.js
export const PLAYER_CERTIFICATE_KEYS = ${JSON.stringify(keys, null, 1)};
`);
console.log(`Saved ${keys.length} keys.`);
