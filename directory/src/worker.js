// JavaHost public world list.
//
// Hosts sign in with Minecraft's own server login (the game calls Mojang's joinServer, we ask Mojang's hasJoined),
// so nobody can list a world or report as someone else. Listings go away 7 minutes after the last heartbeat; three
// reports from different players hide one until it's reviewed; banned accounts can't list. Nothing here ever holds
// a player's IP address: listings only carry a relay address.

import { validateListing, clean } from "./rules.js";

const MINUTE = 60_000;
const LISTING_TTL = 7 * MINUTE;
const TOKEN_TTL = 12 * 60 * MINUTE;
const CHALLENGE_TTL = 2 * MINUTE;
const CHANGE_GAP = MINUTE;
const REPORTS_TO_HIDE = 3;
const REPORTS_PER_DAY = 10;
const REASONS = ["offensive", "griefing", "scam", "other"];

const encoder = new TextEncoder();

async function hmac(secret, text) {
	const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
	const sig = await crypto.subtle.sign("HMAC", key, encoder.encode(text));
	return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function sameText(a, b) {
	if (a.length !== b.length) {
		return false;
	}
	let diff = 0;
	for (let i = 0; i < a.length; i++) {
		diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
	}
	return diff === 0;
}

function json(body, status = 200, headers = {}) {
	return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

function dashed(id) {
	return id.replace(/^(\w{8})(\w{4})(\w{4})(\w{4})(\w{12})$/u, "$1-$2-$3-$4-$5").toLowerCase();
}

async function readJson(request) {
	const text = await request.text();
	if (text.length > 4096) {
		return null;
	}
	try {
		return JSON.parse(text);
	} catch {
		return null;
	}
}

// ---------------------------------------------------------------- tokens

async function makeToken(env, uuid, name, now) {
	const body = btoa(JSON.stringify({ u: uuid, n: name, e: now + TOKEN_TTL }));
	return body + "." + await hmac(env.TOKEN_SECRET, "token|" + body);
}

async function readToken(env, request, now) {
	const header = request.headers.get("authorization") ?? "";
	const token = header.startsWith("Bearer ") ? header.slice(7) : "";
	const [body, sig] = token.split(".");
	if (!body || !sig || !sameText(sig, await hmac(env.TOKEN_SECRET, "token|" + body))) {
		return null;
	}
	try {
		const data = JSON.parse(atob(body));
		return data.e > now ? { uuid: data.u, name: data.n } : null;
	} catch {
		return null;
	}
}

// ---------------------------------------------------------------- routes

async function challenge(env, now) {
	const expires = now + CHALLENGE_TTL;
	const serverId = (await hmac(env.TOKEN_SECRET, "challenge|" + expires)).slice(0, 32);
	return json({ serverId, expires });
}

async function verify(env, request, now) {
	const body = await readJson(request);
	if (!body || typeof body.name !== "string" || !/^[A-Za-z0-9_]{1,16}$/u.test(body.name)
		|| typeof body.serverId !== "string" || !Number.isInteger(body.expires) || body.expires < now) {
		return json({ error: "bad request" }, 400);
	}
	const expected = (await hmac(env.TOKEN_SECRET, "challenge|" + body.expires)).slice(0, 32);
	if (!sameText(expected, body.serverId)) {
		return json({ error: "bad challenge" }, 400);
	}
	const mojang = env.MOJANG_URL ?? "https://sessionserver.mojang.com";
	const answer = await fetch(`${mojang}/session/minecraft/hasJoined?username=${encodeURIComponent(body.name)}&serverId=${body.serverId}`);
	if (answer.status !== 200) {
		return json({ error: "Minecraft couldn't confirm your account" }, 401);
	}
	const profile = await answer.json();
	const uuid = dashed(profile.id);
	if (await env.DB.prepare("SELECT 1 FROM bans WHERE uuid = ?").bind(uuid).first()) {
		return json({ error: "this account can't use public worlds" }, 403);
	}
	return json({ token: await makeToken(env, uuid, profile.name, now), uuid, name: profile.name });
}

async function publish(env, request, who, now) {
	if (await env.DB.prepare("SELECT 1 FROM bans WHERE uuid = ?").bind(who.uuid).first()) {
		return json({ error: "this account can't use public worlds" }, 403);
	}
	const checked = validateListing(await readJson(request));
	if (checked.error) {
		return json({ error: checked.error }, 400);
	}
	const l = checked.listing;
	const existing = await env.DB.prepare("SELECT changed, seen FROM listings WHERE uuid = ?").bind(who.uuid).first();
	if (existing && existing.seen > now - LISTING_TTL && now - existing.changed < CHANGE_GAP) {
		return json({ error: "wait a minute before changing your listing again" }, 429);
	}
	await env.DB.prepare(
		`INSERT INTO listings (uuid, name, title, description, tags, mode, difficulty, max, players, relay, mc, jv, region, voice, signkey, created, changed, seen, hidden)
		 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?15, ?15, 0)
		 ON CONFLICT(uuid) DO UPDATE SET name = ?2, title = ?3, description = ?4, tags = ?5, mode = ?6, difficulty = ?7, max = ?8,
		   relay = ?9, mc = ?10, jv = ?11, region = ?12, voice = ?13, signkey = ?14, changed = ?15, seen = ?15`)
		.bind(who.uuid, who.name, l.title, l.description, JSON.stringify(l.tags), l.mode, l.difficulty, l.max, l.relay, l.mc, l.jv,
			l.region, l.voice, l.signkey, now)
		.run();
	return json({ ok: true, title: l.title, description: l.description });
}

async function heartbeat(env, request, who, now) {
	const body = await readJson(request);
	const players = Number.isInteger(body?.players) ? Math.max(0, Math.min(16, body.players)) : 0;
	const result = await env.DB.prepare("UPDATE listings SET players = ?, seen = ? WHERE uuid = ?").bind(players, now, who.uuid).run();
	return result.meta.changes > 0 ? json({ ok: true }) : json({ error: "not listed" }, 404);
}

async function unpublish(env, who) {
	await env.DB.prepare("DELETE FROM listings WHERE uuid = ?").bind(who.uuid).run();
	return json({ ok: true });
}

async function list(env, now) {
	const { results } = await env.DB.prepare(
		`SELECT uuid, name, title, description, tags, mode, difficulty, max, players, relay, mc, jv, region, voice, signkey
		 FROM listings WHERE hidden = 0 AND seen > ? ORDER BY players DESC, changed DESC LIMIT 200`).bind(now - LISTING_TTL).all();
	return json({ worlds: results.map((r) => ({ ...r, tags: JSON.parse(r.tags), voice: r.voice === 1 })) }, 200,
		{ "cache-control": "public, max-age=15" });
}

async function report(env, request, who, now) {
	const body = await readJson(request);
	const host = typeof body?.host === "string" ? body.host.toLowerCase() : "";
	const reason = REASONS.includes(body?.reason) ? body.reason : null;
	if (!/^[0-9a-f-]{36}$/u.test(host) || !reason || host === who.uuid) {
		return json({ error: "bad request" }, 400);
	}
	const today = await env.DB.prepare("SELECT COUNT(*) AS n FROM reports WHERE reporter = ? AND ts > ?").bind(who.uuid, now - 24 * 60 * MINUTE).first();
	if (today.n >= REPORTS_PER_DAY) {
		return json({ error: "you've sent a lot of reports today" }, 429);
	}
	await env.DB.prepare("INSERT OR REPLACE INTO reports (host, reporter, reason, ts) VALUES (?, ?, ?, ?)").bind(host, who.uuid, reason, now).run();
	const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM reports WHERE host = ? AND ts > ?").bind(host, now - 24 * 60 * MINUTE).first();
	if (recent.n >= REPORTS_TO_HIDE) {
		await env.DB.prepare("UPDATE listings SET hidden = 1 WHERE uuid = ?").bind(host).run();
	}
	return json({ ok: true });
}

// ---------------------------------------------------------------- moderation (bearer ADMIN_SECRET)

async function admin(env, request, path, now) {
	const header = request.headers.get("authorization") ?? "";
	if (!env.ADMIN_SECRET || !sameText(header, "Bearer " + env.ADMIN_SECRET)) {
		return json({ error: "not allowed" }, 403);
	}
	if (path === "/admin/reports" && request.method === "GET") {
		const { results } = await env.DB.prepare(
			`SELECT r.host, l.name, l.title, l.description, l.hidden, COUNT(*) AS reports, GROUP_CONCAT(r.reason) AS reasons, MAX(r.ts) AS latest
			 FROM reports r LEFT JOIN listings l ON l.uuid = r.host GROUP BY r.host ORDER BY latest DESC LIMIT 100`).all();
		return json({ reports: results });
	}
	const body = await readJson(request);
	const uuid = typeof body?.uuid === "string" ? body.uuid.toLowerCase() : "";
	if (!/^[0-9a-f-]{36}$/u.test(uuid)) {
		return json({ error: "bad request" }, 400);
	}
	if (path === "/admin/ban") {
		await env.DB.batch([
			env.DB.prepare("INSERT OR REPLACE INTO bans (uuid, reason, ts) VALUES (?, ?, ?)").bind(uuid, clean(body.reason ?? "", 200), now),
			env.DB.prepare("DELETE FROM listings WHERE uuid = ?").bind(uuid),
		]);
		return json({ ok: true });
	}
	if (path === "/admin/unban") {
		await env.DB.prepare("DELETE FROM bans WHERE uuid = ?").bind(uuid).run();
		return json({ ok: true });
	}
	if (path === "/admin/unhide") {
		await env.DB.batch([
			env.DB.prepare("UPDATE listings SET hidden = 0 WHERE uuid = ?").bind(uuid),
			env.DB.prepare("DELETE FROM reports WHERE host = ?").bind(uuid),
		]);
		return json({ ok: true });
	}
	return json({ error: "not found" }, 404);
}

// ---------------------------------------------------------------- entry points

export async function handle(request, env, now = Date.now()) {
	const url = new URL(request.url);
	const path = url.pathname;
	// Without a real secret, sign-ins could be forged: refuse everything until it's set.
	if (typeof env.TOKEN_SECRET !== "string" || env.TOKEN_SECRET.length < 32) {
		return json({ error: "the public list isn't ready yet" }, 503);
	}
	try {
		if (path === "/listings" && request.method === "GET") {
			return await list(env, now);
		}
		if (path === "/auth/challenge" && request.method === "POST") {
			return await challenge(env, now);
		}
		if (path === "/auth/verify" && request.method === "POST") {
			return await verify(env, request, now);
		}
		if (path.startsWith("/admin/")) {
			return await admin(env, request, path, now);
		}
		const who = await readToken(env, request, now);
		if (!who) {
			return json({ error: "sign in again" }, 401);
		}
		if (path === "/listing" && request.method === "PUT") {
			return await publish(env, request, who, now);
		}
		if (path === "/listing/heartbeat" && request.method === "POST") {
			return await heartbeat(env, request, who, now);
		}
		if (path === "/listing" && request.method === "DELETE") {
			return await unpublish(env, who);
		}
		if (path === "/report" && request.method === "POST") {
			return await report(env, request, who, now);
		}
		return json({ error: "not found" }, 404);
	} catch (error) {
		console.error(error);
		return json({ error: "something went wrong" }, 500);
	}
}

export default {
	async fetch(request, env, ctx) {
		const url = new URL(request.url);
		// The public list is the same for everyone: serve it from Cloudflare's cache for a few seconds.
		if (url.pathname === "/listings" && request.method === "GET" && typeof caches !== "undefined") {
			const key = new Request(new URL("/listings?cache=2", url).toString());
			const cached = await caches.default.match(key);
			if (cached) {
				return cached;
			}
			const response = await handle(request, env);
			if (response.status === 200) {
				ctx.waitUntil(caches.default.put(key, response.clone())); // only good answers, for 15 seconds
			}
			return response;
		}
		return handle(request, env);
	},
	// Hourly: forget listings that stopped long ago and reports older than a week.
	async scheduled(event, env) {
		const now = Date.now();
		await env.DB.batch([
			env.DB.prepare("DELETE FROM listings WHERE seen < ?").bind(now - 60 * MINUTE),
			env.DB.prepare("DELETE FROM reports WHERE ts < ?").bind(now - 7 * 24 * 60 * MINUTE),
		]);
	},
};
