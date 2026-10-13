// What a public world listing may contain. The game checks the same shape before sending (ListingText.java);
// this side is the authority.

export const TAGS = ["help", "building", "survival", "creative", "adventure", "chill"];
export const MODES = ["survival", "creative", "adventure"];
export const DIFFICULTIES = ["peaceful", "easy", "normal", "hard"];

// A short starting list; extend it as reports come in. Matching ignores case, spacing tricks and l33t spelling.
const BLOCKED = [
	"fuck", "shit", "cunt", "bitch", "whore", "slut", "porn", "nude", "nudes", "sex", "dick", "cock", "pussy",
	"nigger", "nigga", "faggot", "fag", "retard", "kys", "killyourself", "rape", "hitler", "nazi",
];

/** Removes formatting codes and control characters, collapses spaces, trims to max. */
export function clean(text, max) {
	if (typeof text !== "string") {
		return "";
	}
	const cleaned = text.replace(/§./gu, "").replace(/[\p{Cc}\p{Cf}]/gu, " ").replace(/\s+/gu, " ").trim();
	return [...cleaned].slice(0, max).join("");
}

function normalise(text) {
	return text.toLowerCase()
		.replace(/[0@4]/g, (c) => ({ "0": "o", "@": "a", "4": "a" })[c])
		.replace(/[1!|]/g, "i").replace(/3/g, "e").replace(/[5$]/g, "s").replace(/7/g, "t")
		.replace(/[^a-z]/g, "");
}

/** True if the text has a blocked word, a link or an address in it. */
export function unfriendly(text) {
	const lower = text.toLowerCase();
	if (/https?:|www\.|discord\.gg|\b[a-z0-9-]+\.(com|net|org|gg|io|me|xyz|ru|co|link|tk|ly|cc|to|sh|app|dev)\b/u.test(lower)) {
		return "links aren't allowed";
	}
	if (/\b\d{1,3}(\.\d{1,3}){3}\b/u.test(lower) || /\b[0-9a-f]{1,4}(:[0-9a-f]{0,4}){3,7}\b/u.test(lower)) {
		return "addresses aren't allowed";
	}
	const squashed = normalise(text);
	// "f u c k" and "f.u.c.k" count as the word they spell.
	const joined = lower.replace(/(?<=(?:^|[^a-z0-9@$!|])[a-z0-9@$!|])[\s._*-]+(?=[a-z0-9@$!|](?:[^a-z0-9@$!|]|$))/gu, "");
	const words = joined.split(/[^a-z0-9@$!|]+/u).map(normalise);
	for (const bad of BLOCKED) {
		if (words.includes(bad) || (bad.length >= 5 && squashed.includes(bad))) {
			return "please keep it friendly";
		}
	}
	return null;
}

/** Checks and cleans a listing from a host. Returns { listing } or { error }. */
export function validateListing(body) {
	if (!body || typeof body !== "object") {
		return { error: "bad request" };
	}
	const title = clean(body.title, 40);
	const description = clean(body.description ?? "", 160);
	if ([...title].length < 3) {
		return { error: "the title needs at least 3 characters" };
	}
	const problem = unfriendly(title) ?? unfriendly(description);
	if (problem) {
		return { error: problem };
	}
	const tags = Array.isArray(body.tags) ? [...new Set(body.tags.filter((t) => TAGS.includes(t)))].slice(0, 4) : [];
	const mode = MODES.includes(body.mode) ? body.mode : null;
	const difficulty = DIFFICULTIES.includes(body.difficulty) ? body.difficulty : null;
	const max = Number.isInteger(body.max) && body.max >= 2 && body.max <= 16 ? body.max : null;
	const relay = typeof body.relay === "string" && /^[a-z0-9-]{1,40}\.[a-z0-9-]{1,10}\.e4mc\.link$/u.test(body.relay) ? body.relay : null;
	const mc = typeof body.mc === "string" && /^[0-9a-z.\-]{1,16}$/u.test(body.mc) ? body.mc : null;
	const jv = typeof body.jv === "string" && /^[0-9A-Za-z.+\-]{1,16}$/u.test(body.jv) ? body.jv : null;
	const signkey = typeof body.signkey === "string" && /^[A-Za-z0-9+/=]{40,120}$/u.test(body.signkey) ? body.signkey : null;
	if (!mode || !difficulty || !max || !relay || !mc || !jv || !signkey) {
		return { error: "bad request" };
	}
	return {
		listing: {
			title, description, tags, mode, difficulty, max, relay, mc, jv, signkey,
			voice: body.voice === true ? 1 : 0,
			region: relay.split(".")[1],
		},
	};
}
