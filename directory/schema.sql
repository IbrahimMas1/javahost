-- JavaHost public world list. Apply with: npx wrangler d1 execute javahost-directory --remote --file=schema.sql
CREATE TABLE IF NOT EXISTS listings (
  uuid TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  tags TEXT NOT NULL,
  mode TEXT NOT NULL,
  difficulty TEXT NOT NULL,
  max INTEGER NOT NULL,
  players INTEGER NOT NULL DEFAULT 0,
  relay TEXT NOT NULL,
  mc TEXT NOT NULL,
  jv TEXT NOT NULL,
  region TEXT NOT NULL,
  voice INTEGER NOT NULL,
  signkey TEXT NOT NULL,
  created INTEGER NOT NULL,
  changed INTEGER NOT NULL,
  seen INTEGER NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0,
  mods INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS reports (
  host TEXT NOT NULL,
  reporter TEXT NOT NULL,
  reason TEXT NOT NULL,
  ts INTEGER NOT NULL,
  PRIMARY KEY (host, reporter)
);
CREATE INDEX IF NOT EXISTS reports_by_reporter ON reports (reporter, ts);
CREATE TABLE IF NOT EXISTS bans (
  uuid TEXT PRIMARY KEY,
  reason TEXT NOT NULL,
  ts INTEGER NOT NULL
);
