// A stand-in for Cloudflare D1 on Node's built-in SQLite, for tests and the local dev server.
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

export function d1(file = ":memory:") {
	const db = new DatabaseSync(file);
	db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
	const statement = (sql, args = []) => ({
		bind: (...values) => statement(sql, values),
		first: async () => db.prepare(sql).get(...args) ?? null,
		all: async () => ({ results: db.prepare(sql).all(...args) }),
		run: async () => ({ meta: { changes: Number(db.prepare(sql).run(...args).changes) } }),
	});
	return { prepare: (sql) => statement(sql), batch: async (list) => Promise.all(list.map((s) => s.run())) };
}
