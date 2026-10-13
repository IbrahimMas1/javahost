// Runs the list service on this computer for JavaHost's in-game tests: node dev/server.js <port>
import { createServer } from "node:http";
import { handle } from "../src/worker.js";
import { d1 } from "./d1.js";

const port = Number(process.argv[2] ?? 8788);
// Test accounts are offline accounts without Mojang certificates, so this local copy accepts any sign-in.
const env = { DB: d1(), TOKEN_SECRET: "dev-secret-for-local-tests-only-0123456789", ADMIN_SECRET: process.env.ADMIN_SECRET ?? "dev-admin", DEV_ACCEPT_ANY: true };

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
