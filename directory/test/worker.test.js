import { test } from "node:test";
import assert from "node:assert/strict";

import { handle, signInPayload } from "../src/worker.js";
import { unfriendly, validateListing } from "../src/rules.js";
import { d1 } from "../dev/d1.js";

const PROFILES = {
	Alex: "00000000-0000-0000-0000-00000000000a",
	Sam: "00000000-0000-0000-0000-00000000000b",
	Eve: "00000000-0000-0000-0000-00000000000e",
	Kim: "00000000-0000-0000-0000-00000000000c",
	Lee: "00000000-0000-0000-0000-00000000000d",
};

const b64 = (bytes) => Buffer.from(bytes).toString("base64");
const rsa = (hash) => crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash },
	true, ["sign", "verify"]);
// A stand-in for Mojang's certificate key, and one chat key per player, certified by it.
const MOJANG = await rsa("SHA-1");
const PLAYER_KEYS = {};

async function certificate(name, expiresAt) {
	PLAYER_KEYS[name] ??= await rsa("SHA-256");
	const der = new Uint8Array(await crypto.subtle.exportKey("spki", PLAYER_KEYS[name].publicKey));
	const payload = new Uint8Array(24 + der.length);
	const hex = PROFILES[name].replace(/-/g, "");
	for (let i = 0; i < 16; i++) {
		payload[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
	}
	new DataView(payload.buffer).setBigInt64(16, BigInt(expiresAt));
	payload.set(der, 24);
	const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", MOJANG.privateKey, payload));
	return { expires_at: new Date(expiresAt).toISOString(), key: "-----BEGIN RSA PUBLIC KEY-----\n" + b64(der) + "\n-----END RSA PUBLIC KEY-----\n",
		signature_v2: b64(signature) };
}

async function environment() {
	const mojangKey = b64(new Uint8Array(await crypto.subtle.exportKey("spki", MOJANG.publicKey)));
	return { DB: d1(), TOKEN_SECRET: "test-secret-0123456789abcdef0123456789", ADMIN_SECRET: "admin-secret", MOJANG_KEYS: [mojangKey] };
}

async function call(env, method, path, body, token, now = Date.now()) {
	const headers = token ? { authorization: "Bearer " + token } : {};
	const response = await handle(new Request("https://directory.test" + path, {
		method, headers, body: body === undefined ? undefined : JSON.stringify(body),
	}), env, now);
	return { status: response.status, body: await response.json() };
}

async function signIn(env, name, now = Date.now(), tamper = {}) {
	const challenge = (await call(env, "POST", "/auth/challenge", undefined, null, now)).body;
	const uuid = tamper.uuid ?? PROFILES[name];
	const key = await certificate(name, tamper.expiresAt ?? now + 60 * 60_000);
	const signer = tamper.signer ?? (PLAYER_KEYS[name]);
	const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", signer.privateKey, signInPayload(challenge.serverId, uuid, name)));
	const verified = await call(env, "POST", "/auth/verify", { name, uuid, serverId: challenge.serverId, expires: challenge.expires, key, sig: b64(sig) }, null, now);
	return tamper.raw ? verified : verified.body.token;
}

const LISTING = {
	title: "Help me dig a big hole", description: "Bring shovels. Chill vibes.", tags: ["help", "chill", "nonsense"],
	mode: "survival", difficulty: "normal", max: 8, relay: "brave-otter.eu.e4mc.link", mc: "26.3", jv: "1.8.0",
	signkey: "MCowBQYDK2VwAyEAVyahZgQBPByQoruX+qDjCAdMy6GmsRkEqDAewcPcV3k=", voice: false,
};

test("signing in needs a Mojang-certified key, a fresh challenge and the right account", async () => {
	const env = await environment();
	assert.ok(await signIn(env, "Alex"));
	assert.equal((await call(env, "POST", "/auth/verify", { name: "Alex", uuid: PROFILES.Alex, serverId: "0".repeat(32), expires: Date.now() + 1000 })).status, 400);
	await signIn(env, "Eve");
	assert.equal((await signIn(env, "Alex", Date.now(), { raw: true, signer: PLAYER_KEYS.Eve })).status, 401, "someone else's key can't sign for Alex");
	assert.equal((await signIn(env, "Alex", Date.now(), { raw: true, uuid: PROFILES.Sam })).status, 401, "Alex's certificate doesn't cover Sam's account");
	assert.equal((await signIn(env, "Alex", Date.now(), { raw: true, expiresAt: Date.now() - 1000 })).status, 401, "an expired certificate is refused");
	const challenge = (await call(env, "POST", "/auth/challenge")).body;
	assert.equal((await call(env, "POST", "/auth/verify", { name: "Alex", uuid: PROFILES.Alex, serverId: challenge.serverId, expires: challenge.expires })).status, 401,
		"no certificate, no sign-in");
	assert.equal((await call(env, "PUT", "/listing", LISTING, "forged.token")).status, 401);
});

test("a listing shows up, keeps itself alive with heartbeats, and expires", async () => {
	const env = await environment();
	const now = Date.now();
	const token = await signIn(env, "Alex", now);
	assert.equal((await call(env, "PUT", "/listing", LISTING, token, now)).status, 200);
	let worlds = (await call(env, "GET", "/listings", undefined, null, now)).body.worlds;
	assert.equal(worlds.length, 1);
	assert.equal(worlds[0].name, "Alex");
	assert.equal(worlds[0].uuid, "00000000-0000-0000-0000-00000000000a");
	assert.deepEqual(worlds[0].tags, ["help", "chill"], "unknown tags are dropped");
	assert.equal(worlds[0].region, "eu");
	assert.equal((await call(env, "POST", "/listing/heartbeat", { players: 3 }, token, now + 5 * 60_000)).status, 200);
	worlds = (await call(env, "GET", "/listings", undefined, null, now + 10 * 60_000)).body.worlds;
	assert.equal(worlds[0].players, 3, "still listed 5 minutes after the last heartbeat");
	worlds = (await call(env, "GET", "/listings", undefined, null, now + 13 * 60_000)).body.worlds;
	assert.equal(worlds.length, 0, "gone 8 minutes after the last heartbeat");
	assert.equal((await call(env, "PUT", "/listing", LISTING, token, now + 14 * 60_000)).status, 200, "and can list again");
	assert.equal((await call(env, "DELETE", "/listing", undefined, token, now + 14 * 60_000)).status, 200);
	assert.equal((await call(env, "GET", "/listings", undefined, null, now + 14 * 60_000)).body.worlds.length, 0);
});

test("one change a minute, and listings must be clean and well formed", async () => {
	const env = await environment();
	const now = Date.now();
	const token = await signIn(env, "Alex", now);
	assert.equal((await call(env, "PUT", "/listing", LISTING, token, now)).status, 200);
	assert.equal((await call(env, "PUT", "/listing", { ...LISTING, title: "Another title" }, token, now + 10_000)).status, 429);
	assert.equal((await call(env, "PUT", "/listing", { ...LISTING, title: "Another title" }, token, now + 61_000)).status, 200);
	for (const bad of [
		{ title: "Join discord.gg/abc" }, { description: "my ip 203.0.113.7 lol" }, { title: "x" },
		{ relay: "203.0.113.7:25565" }, { relay: "evil.example.com" }, { max: 500 }, { mode: "hardcore-op" }, { signkey: "nope" },
		{ title: "f u c k this" }, { title: "Sh1t server" },
	]) {
		const response = await call(env, "PUT", "/listing", { ...LISTING, ...bad }, token, now + 200_000);
		assert.equal(response.status, 400, JSON.stringify(bad));
	}
	const cleaned = await call(env, "PUT", "/listing", { ...LISTING, title: "§kNice§r  world\u0000 " }, token, now + 300_000);
	assert.equal(cleaned.body.title, "Nice world");
});

test("three different players' reports hide a listing; one player can't do it alone", async () => {
	const env = await environment();
	const now = Date.now();
	const host = await signIn(env, "Alex", now);
	await call(env, "PUT", "/listing", LISTING, host, now);
	const target = "00000000-0000-0000-0000-00000000000a";
	const eve = await signIn(env, "Eve", now);
	for (let i = 0; i < 5; i++) {
		await call(env, "POST", "/report", { host: target, reason: "offensive" }, eve, now);
	}
	assert.equal((await call(env, "GET", "/listings", undefined, null, now)).body.worlds.length, 1, "repeat reports from one player count once");
	assert.equal((await call(env, "POST", "/report", { host: target, reason: "griefing" }, host, now)).status, 400, "can't report yourself");
	await call(env, "POST", "/report", { host: target, reason: "griefing" }, await signIn(env, "Sam", now), now);
	await call(env, "POST", "/report", { host: target, reason: "scam" }, await signIn(env, "Kim", now), now);
	assert.equal((await call(env, "GET", "/listings", undefined, null, now)).body.worlds.length, 0, "hidden after 3 reporters");
	const reports = await call(env, "GET", "/admin/reports", undefined, "admin-secret", now);
	assert.equal(reports.body.reports[0].reports, 3);
	assert.equal((await call(env, "GET", "/admin/reports", undefined, "wrong", now)).status, 403);
	await call(env, "POST", "/admin/unhide", { uuid: target }, "admin-secret", now);
	assert.equal((await call(env, "GET", "/listings", undefined, null, now)).body.worlds.length, 1, "a moderator can bring it back");
});

test("a banned account loses its listing and can't sign in or list again", async () => {
	const env = await environment();
	const now = Date.now();
	const token = await signIn(env, "Lee", now);
	await call(env, "PUT", "/listing", LISTING, token, now);
	await call(env, "POST", "/admin/ban", { uuid: "00000000-0000-0000-0000-00000000000d", reason: "griefing" }, "admin-secret", now);
	assert.equal((await call(env, "GET", "/listings", undefined, null, now)).body.worlds.length, 0);
	assert.equal((await call(env, "PUT", "/listing", LISTING, token, now + 120_000)).status, 403);
	assert.equal((await signIn(env, "Lee", now, { raw: true })).status, 403);
});

test("without a real secret the service refuses everything", async () => {
	const env = { ...(await environment()), TOKEN_SECRET: undefined };
	assert.equal((await call(env, "GET", "/listings")).status, 503);
	assert.equal((await call(env, "POST", "/auth/challenge")).status, 503);
});

test("the text rules", () => {
	assert.equal(unfriendly("Help build a castle"), null);
	assert.equal(unfriendly("Essex and Sussex builders"), null, "no false alarms on place names");
	assert.ok(unfriendly("visit www.example.com"));
	assert.ok(unfriendly("[2001:db8::1]:25565 here"));
	assert.ok(unfriendly("N1gg3r"));
	assert.equal(validateListing({ ...LISTING, voice: "yes" }).listing.voice, 0, "voice is only on when it's really true");
});
