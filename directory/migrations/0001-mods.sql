-- JavaHost 1.9: how many mods a public world needs. Apply once to the live list:
-- npx wrangler d1 execute javahost-directory --remote --file=migrations/0001-mods.sql
ALTER TABLE listings ADD COLUMN mods INTEGER NOT NULL DEFAULT 0;
