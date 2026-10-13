// Runs the list service on this computer for JavaHost's in-game tests: node dev/server.js <port>
// Test accounts are offline accounts, so "Mojang" here accepts any name and answers with its offline id.
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { handle } from "../src/worker.js";
import { d1 } from "./d1.js";

const port = Number(process.argv[2] ?? 8788);
const env = { DB: d1(), TOKEN_SECRET: "dev-secret", ADMIN_SECRET: process.env.ADMIN_SECRET ?? "dev-admin", MOJANG_URL: "http://mojang.local" };

function offlineId(name) {
	const hash = createHash("md5").update("OfflinePlayer:" + name).digest();
	hash[6] = (hash[6] & 0x0f) | 0x30;
	hash[8] = (hash[8] & 0x3f) | 0x80;
	return hash.toString("hex");
}

const realFetch = globalThis.fetch;
globalThis.fetch = async (url, init) => {
	const parsed = new URL(url);
	if (parsed.host === "mojang.local") {
		const name = parsed.searchParams.get("username");
		return new Response(JSON.stringify({ id: offlineId(name), name }));
	}
	return realFetch(url, init);
};

createServer(async (req, res) => {
	const chunks = [];
	for await (const chunk of req) {
		chunks.push(chunk);
	}
	const body = chunks.length ? Buffer.concat(chunks) : undefined;
	const request = new Request("http://localhost:" + port + req.url, {
		method: req.method, headers: req.headers, body: req.method === "GET" || req.method === "HEAD" ? undefined : body,
	});
	const response = await handle(request, env);
	res.writeHead(response.status, Object.fromEntries(response.headers));
	res.end(Buffer.from(await response.arrayBuffer()));
}).listen(port, "127.0.0.1", () => console.log("JavaHost list service (dev) on http://127.0.0.1:" + port));
