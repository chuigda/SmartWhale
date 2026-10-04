import fs from "node:fs";
import { z } from "zod";
import { ConfigSchema, LlmSchema, type Config, type LlmConfig } from "./schema.ts";
import { resolveFromRoot } from "../util/paths.ts";

export function loadConfig(file: string): Config {
  const raw = JSON.parse(fs.readFileSync(resolveFromRoot(file), "utf8"));
  const parsed = ConfigSchema.safeParse(raw);
  if (!parsed.success) {
    throw new Error(`Invalid config ${file}:\n${z.prettifyError(parsed.error)}`);
  }
  return parsed.data;
}

export function loadLlmConfig(config: Config): LlmConfig | null {
  if (config.llm === undefined) return null;
  const source = typeof config.llm === "string" ? config.llm : "inline llm config";
  const raw = typeof config.llm === "string" ? parseJsonc(fs.readFileSync(resolveFromRoot(config.llm), "utf8")) : config.llm;
  const parsed = LlmSchema.safeParse(raw);
  if (!parsed.success) throw new Error(`Invalid ${source}:\n${z.prettifyError(parsed.error)}`);
  const c = parsed.data;
  const apiKey = c.apiKey ?? (c.apiKeyEnv ? process.env[c.apiKeyEnv] : undefined);
  if (!apiKey) throw new Error(`No API key in ${source}: set apiKey or the ${c.apiKeyEnv} environment variable`);
  return {
    baseUrl: (c.baseUrl ?? c.baseURL)!.replace(/\/+$/, ""),
    apiKey,
    model: c.model,
    averageTts: c.averageTts,
    contextWindow: c.contextWindow,
    practicalContextWindow: Math.min(c.practicalContextWindow ?? c.contextWindow, c.contextWindow),
    outputlength: c.outputlength,
    vision: c.vision,
    thinking: c.thinking,
    reasoning: c.reasoning,
    temperature: c.temperature,
  };
}

/** JSON with // and /* *\/ comments and trailing commas. */
export function parseJsonc(text: string): unknown {
  let out = "";
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (c === '"') {
      const start = i;
      for (i++; i < text.length && text[i] !== '"'; i++) if (text[i] === "\\") i++;
      out += text.slice(start, i + 1);
    } else if (c === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++;
      out += "\n";
    } else if (c === "/" && text[i + 1] === "*") {
      const end = text.indexOf("*/", i + 2);
      i = end < 0 ? text.length : end + 1;
    } else {
      out += c;
    }
  }
  return JSON.parse(out.replace(/,(\s*[}\]])/g, "$1"));
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
