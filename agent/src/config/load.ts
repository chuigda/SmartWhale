import fs from "node:fs";
import { z } from "zod";
import { ConfigSchema, type Config } from "./schema.ts";
import { resolveFromRoot } from "../util/paths.ts";

export function loadConfig(file: string): Config {
  const raw = JSON.parse(fs.readFileSync(resolveFromRoot(file), "utf8"));
  const parsed = ConfigSchema.safeParse(raw);
  if (!parsed.success) {
    throw new Error(`Invalid config ${file}:\n${z.prettifyError(parsed.error)}`);
  }
  return parsed.data;
}

export function readPassword(config: Config): string {
  const fromEnv = process.env[config.auth.passwordEnv];
  if (fromEnv) return fromEnv;
  if (config.auth.passwordFile) {
    const pw = fs.readFileSync(resolveFromRoot(config.auth.passwordFile), "utf8").replace(/\r?\n$/, "");
    if (pw) return pw;
  }
  throw new Error(`No password: set ${config.auth.passwordEnv} or auth.passwordFile`);
}
