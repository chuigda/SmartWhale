// Dev helper: `node src/launcher/auth-check.ts <config.json>` — verifies Yggdrasil login without launching the game.
import path from "node:path";
import { loadConfig, readPassword } from "../config/load.ts";
import { obtainSession } from "./yggdrasil.ts";
import { resolveFromRoot } from "../util/paths.ts";

const config = loadConfig(process.argv[2] ?? "config/bot.example.json");
const session = await obtainSession(
  config.auth.apiRoot,
  path.join(resolveFromRoot(path.join("data", config.name)), "auth.json"),
  { username: config.auth.username, profile: config.auth.profile, password: () => readPassword(config) },
);
console.log(`OK: ${session.profile.name} ${session.profile.id}`);
