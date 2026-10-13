import { test } from "node:test";
import assert from "node:assert/strict";

import { handle } from "../src/worker.js";
import { unfriendly, validateListing } from "../src/rules.js";
import { d1 } from "../dev/d1.js";

const PROFILES = {
	Alex: "0000000000000000000000000000000a",
	Sam: "0000000000000000000000000000000b",
	Eve: "0000000000000000000000000000000e",
	Kim: "0000000000000000000000000000000c",
	Lee: "0000000000000000000000000000000d",
};

function environment() {
	const env = { DB: d1(), TOKEN_SECRET: "test-secret-0123456789abcdef0123456789", ADMIN_SECRET: "admin-secret", MOJANG_URL: "https://mojang.test" };
	// Mojang's hasJoined, stubbed: anyone in PROFILES "joined" with any serverId.
	globalThis.fetch = async (url) => {
		const name = new URL(url).searchParams.get("username");
		return PROFILES[name] ? new Response(JSON.stringify({ id: PROFILES[name], name })) : new Response(null, { status: 204 });
	};
	return env;
}

async function call(env, method, path, body, token, now = Date.now()) {
	const headers = token ? { authorization: "Bearer " + token } : {};
	const response = await handle(new Request("https://directory.test" + path, {
		method, headers, body: body === undefined ? undefined : JSON.stringify(body),
	}), env, now);
	return { status: response.status, body: await response.json() };
}

async function signIn(env, name, now = Date.now()) {
	const challenge = (await call(env, "POST", "/auth/challenge", undefined, null, now)).body;
	const verified = await call(env, "POST", "/auth/verify", { name, serverId: challenge.serverId, expires: challenge.expires }, null, now);
	return verified.body.token;
}

const LISTING = {
	title: "Help me dig a big hole", description: "Bring shovels. Chill vibes.", tags: ["help", "chill", "nonsense"],
	mode: "survival", difficulty: "normal", max: 8, relay: "brave-otter.eu.e4mc.link", mc: "26.3", jv: "1.8.0",
	signkey: "MCowBQYDK2VwAyEAVyahZgQBPByQoruX+qDjCAdMy6GmsRkEqDAewcPcV3k=", voice: false,
};

test("signing in needs Mojang to confirm the account, and a challenge we issued", async () => {
	const env = environment();
	assert.ok(await signIn(env, "Alex"));
	assert.equal((await call(env, "POST", "/auth/verify", { name: "Alex", serverId: "0".repeat(32), expires: Date.now() + 1000 })).status, 400);
	const challenge = (await call(env, "POST", "/auth/challenge")).body;
	assert.equal((await call(env, "POST", "/auth/verify", { name: "Nobody", serverId: challenge.serverId, expires: challenge.expires })).status, 401);
	assert.equal((await call(env, "PUT", "/listing", LISTING, "forged.token")).status, 401);
});

test("a listing shows up, keeps itself alive with heartbeats, and expires", async () => {
	const env = environment();
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
	const env = environment();
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
	const env = environment();
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
	const env = environment();
	const now = Date.now();
	const token = await signIn(env, "Lee", now);
	await call(env, "PUT", "/listing", LISTING, token, now);
	await call(env, "POST", "/admin/ban", { uuid: "00000000-0000-0000-0000-00000000000d", reason: "griefing" }, "admin-secret", now);
	assert.equal((await call(env, "GET", "/listings", undefined, null, now)).body.worlds.length, 0);
	assert.equal((await call(env, "PUT", "/listing", LISTING, token, now + 120_000)).status, 403);
	const challenge = (await call(env, "POST", "/auth/challenge", undefined, null, now)).body;
	assert.equal((await call(env, "POST", "/auth/verify", { name: "Lee", serverId: challenge.serverId, expires: challenge.expires }, null, now)).status, 403);
});

test("without a real secret the service refuses everything", async () => {
	const env = { ...environment(), TOKEN_SECRET: undefined };
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
