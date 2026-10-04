import { z } from "zod";

export const ConfigSchema = z.object({
  name: z.string().regex(/^[A-Za-z0-9_-]+$/, "bot name is used as a directory name"),
  minecraft: z.object({
    /** Launcher root holding libraries/, assets/, versions/. */
    root: z.string(),
    version: z.string(),
    java: z.string(),
    /** host:port to auto-join. */
    server: z.string(),
    jvmArgs: z.array(z.string()).default(["-Xmx4G"]),
    mods: z.object({
      source: z.string(),
      exclude: z.array(z.string()).default([]),
      extra: z.array(z.string()).default([]),
    }),
    /** Directories copied from the source instance into the bot game dir if missing (mod configs, gun packs…). */
    copyFromInstance: z.array(z.string()).default(["config", "defaultconfigs"]),
    /** Source instance dir for options.txt and copyFromInstance. */
    instance: z.string(),
    options: z.record(z.string(), z.union([z.string(), z.number(), z.boolean()])).default({}),
  }),
  auth: z.object({
    type: z.literal("authlib-injector"),
    apiRoot: z.string().url(),
    username: z.string(),
    /** Character name to use when the account owns several profiles. */
    profile: z.string().optional(),
    passwordEnv: z.string().default("SMARTWHALE_AUTH_PASSWORD"),
    /** Fallback: a git-ignored file containing only the password. */
    passwordFile: z.string().optional(),
  }),
  bridge: z
    .object({
      port: z.number().int().min(0).max(65535).default(0),
      jar: z.string().default("bridge/build/libs/smartwhale-bridge-0.1.0.jar"),
      /** visible: only blocks the bot could see; omniscient: everything in loaded chunks (docs/DESIGN.md §6.3). */
      perception: z.enum(["visible", "omniscient"]).default("visible"),
    })
    .default({ port: 0, jar: "bridge/build/libs/smartwhale-bridge-0.1.0.jar", perception: "visible" }),
  tools: z
    .object({
      authlibInjector: z.string().default("tools/authlib-injector-1.2.8.jar"),
    })
    .default({ authlibInjector: "tools/authlib-injector-1.2.8.jar" }),
  /** Path to an LLM config file (relative to the repo root) or the same object inline (docs/DESIGN.md §8). */
  llm: z.union([z.string(), z.record(z.string(), z.unknown())]).optional(),
  agent: z
    .object({
      persona: z.string().optional(),
      /** Extra names that count as a mention in chat (case-insensitive). */
      aliases: z.array(z.string()).default([]),
      maxTokensPerTurn: z.number().int().min(256).default(8192),
    })
    .default({ aliases: [], maxTokensPerTurn: 8192 }),
});

export type Config = z.infer<typeof ConfigSchema>;

export const LlmSchema = z
  .object({
    baseUrl: z.string().url().optional(),
    baseURL: z.string().url().optional(),
    apiKey: z.string().optional(),
    apiKeyEnv: z.string().optional(),
    model: z.string(),
    /** Average output speed in tokens/second, used to estimate turn duration. */
    averageTts: z.number().positive().default(50),
    /** Hard limit: no request may exceed it. */
    contextWindow: z.number().int().positive().default(128000),
    /** Compaction starts once the context exceeds this (docs/DESIGN.md §7.5). */
    practicalContextWindow: z.number().int().positive().optional(),
    outputlength: z.number().int().positive().default(8192),
    vision: z.boolean().default(false),
    thinking: z.boolean().default(false),
    supportThinkingEfforts: z.array(z.string()).default([]),
    fillInMiddle: z.boolean().default(false),
    /** echo: send reasoning_content of completed turns back to the API; drop: omit it. */
    reasoning: z.enum(["echo", "drop"]).default("echo"),
    temperature: z.number().min(0).max(2).optional(),
  })
  .refine((c) => c.baseUrl ?? c.baseURL, { message: "baseUrl is required" })
  .refine((c) => c.apiKey ?? c.apiKeyEnv, { message: "apiKey or apiKeyEnv is required" });

export interface LlmConfig {
  baseUrl: string;
  apiKey: string;
  model: string;
  averageTts: number;
  contextWindow: number;
  practicalContextWindow: number;
  outputlength: number;
  vision: boolean;
  thinking: boolean;
  reasoning: "echo" | "drop";
  temperature?: number;
}
