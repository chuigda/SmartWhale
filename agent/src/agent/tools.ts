import { z } from "zod";
import type { ToolSpec } from "../llm/client.ts";

export interface ToolResult {
  /** Text given to the model. */
  content: string;
  /** Large observation results age out of the context (docs/DESIGN.md §7.5). */
  bulky?: boolean;
}

export interface Tool<S extends z.ZodType = z.ZodType> {
  name: string;
  description: string;
  schema: S;
  bulky?: boolean;
  run(args: z.infer<S>): Promise<unknown>;
}

export function tool<S extends z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

export class ToolError extends Error {
  readonly hint?: string;
  constructor(message: string, hint?: string) {
    super(message);
    this.hint = hint;
  }
}

export const RESULT_MAX_CHARS = 4000;

export function toSpec(t: Tool): ToolSpec {
  const schema = z.toJSONSchema(t.schema, { io: "input", unrepresentable: "any" }) as Record<string, unknown>;
  delete schema.$schema;
  return { type: "function", function: { name: t.name, description: t.description, parameters: schema } };
}

export function formatResult(value: unknown): string {
  const text = typeof value === "string" ? value : JSON.stringify(value);
  if (text.length <= RESULT_MAX_CHARS) return text;
  return `${text.slice(0, RESULT_MAX_CHARS)}… [truncated ${text.length - RESULT_MAX_CHARS} characters; use filters, a smaller radius or a limit to narrow the result]`;
}

/** Parses, validates and runs a tool call. Never throws: errors become `{error, hint}` for the model. */
export async function runTool(tools: Map<string, Tool>, name: string, rawArgs: string): Promise<ToolResult> {
  const t = tools.get(name);
  if (!t) return { content: JSON.stringify({ error: `Unknown tool ${name}` }) };
  let args: unknown;
  try {
    args = rawArgs.trim() ? JSON.parse(rawArgs) : {};
  } catch (e) {
    return { content: JSON.stringify({ error: `Arguments are not valid JSON: ${(e as Error).message}`, hint: "Retry with a valid JSON object" }) };
  }
  const parsed = t.schema.safeParse(args);
  if (!parsed.success) return { content: JSON.stringify({ error: "Invalid arguments", hint: z.prettifyError(parsed.error) }) };
  try {
    const value = await t.run(parsed.data);
    return { content: formatResult(value ?? { ok: true }), bulky: t.bulky };
  } catch (e) {
    const err = e as Error & { hint?: string };
    return { content: JSON.stringify(err.hint ? { error: err.message, hint: err.hint } : { error: err.message }) };
  }
}

export const Pos = z.object({ x: z.number().int(), y: z.number().int(), z: z.number().int() }).describe("Block position");
export const Ids = z
  .union([z.string(), z.array(z.string()).min(1)])
  .describe('Registry id(s) or #tags, e.g. "minecraft:oak_log" or ["#minecraft:logs"]');
