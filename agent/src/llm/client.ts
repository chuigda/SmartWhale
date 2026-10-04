import type { LlmConfig } from "../config/schema.ts";
import { logger } from "../util/log.ts";

const log = logger("llm");

export interface ToolCall {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}

export type Message =
  | { role: "system"; content: string }
  | { role: "user"; content: string }
  | { role: "assistant"; content: string | null; reasoning_content?: string; tool_calls?: ToolCall[] }
  | { role: "tool"; tool_call_id: string; content: string };

export interface ToolSpec {
  type: "function";
  function: { name: string; description: string; parameters: Record<string, unknown> };
}

export interface Usage {
  prompt_tokens: number;
  completion_tokens: number;
  cached_tokens: number;
  reasoning_tokens: number;
}

export interface Completion {
  content: string;
  reasoning: string;
  toolCalls: ToolCall[];
  finishReason: string | null;
  usage: Usage | null;
  /** True when the request was aborted mid-stream; content/reasoning/toolCalls hold what arrived so far. */
  interrupted: boolean;
  elapsedMs: number;
}

export interface CompleteOptions {
  messages: Message[];
  tools?: ToolSpec[];
  maxTokens: number;
  /** false: ask the model not to think (only sent when the model supports thinking). */
  thinking?: boolean;
  signal?: AbortSignal;
  onDelta?: (kind: "reasoning" | "content", text: string) => void;
}

export class HttpError extends Error {
  readonly status: number;
  constructor(status: number, body: string) {
    super(`HTTP ${status}: ${body.slice(0, 500)}`);
    this.status = status;
  }
}

const MAX_ATTEMPTS = 5;

/** Minimal OpenAI-compatible /chat/completions client with streaming, tool calls and reasoning_content. */
export class LlmClient {
  readonly config: LlmConfig;

  constructor(config: LlmConfig) {
    this.config = config;
  }

  /**
   * Streams one completion. If `signal` aborts mid-stream the partial result is returned with
   * `interrupted: true`; if it aborts before the response starts, the abort error is thrown.
   */
  async complete(opts: CompleteOptions): Promise<Completion> {
    for (let attempt = 1; ; attempt++) {
      try {
        return await this.once(opts);
      } catch (e) {
        if (opts.signal?.aborted) throw e;
        const retryable = !(e instanceof HttpError) || e.status === 429 || e.status >= 500;
        if (!retryable || attempt >= MAX_ATTEMPTS) throw e;
        const delay = Math.min(30000, 1000 * 2 ** (attempt - 1)) * (0.75 + Math.random() * 0.5);
        log.warn(`LLM request failed (${(e as Error).message}); retry ${attempt}/${MAX_ATTEMPTS - 1} in ${Math.round(delay)} ms`);
        await sleep(delay, opts.signal);
      }
    }
  }

  private async once(opts: CompleteOptions): Promise<Completion> {
    const started = Date.now();
    const body: Record<string, unknown> = {
      model: this.config.model,
      messages: opts.messages.map((m) => this.outgoing(m)),
      stream: true,
      stream_options: { include_usage: true },
      max_tokens: Math.min(opts.maxTokens, this.config.outputlength),
    };
    if (opts.tools?.length) body.tools = opts.tools;
    if (this.config.temperature !== undefined) body.temperature = this.config.temperature;
    if (opts.thinking === false && this.config.thinking) body.thinking = { type: "disabled" };

    const res = await fetch(`${this.config.baseUrl}/chat/completions`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${this.config.apiKey}` },
      body: JSON.stringify(body),
      signal: opts.signal,
    });
    if (!res.ok || !res.body) throw new HttpError(res.status, await res.text().catch(() => ""));

    const acc = new StreamAccumulator();
    let interrupted = false;
    try {
      const decoder = new TextDecoder();
      let buf = "";
      for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
        buf += decoder.decode(chunk, { stream: true });
        let nl: number;
        while ((nl = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, nl).trim();
          buf = buf.slice(nl + 1);
          acc.line(line, opts.onDelta);
        }
      }
      if (buf.trim()) acc.line(buf.trim(), opts.onDelta);
    } catch (e) {
      if (!opts.signal?.aborted) throw e;
      interrupted = true;
    }
    return { ...acc.result(), interrupted, elapsedMs: Date.now() - started };
  }

  private outgoing(m: Message): Message {
    if (m.role !== "assistant" || m.reasoning_content === undefined) return m;
    if (this.config.reasoning === "echo") return m;
    const { reasoning_content: _, ...rest } = m;
    return rest;
  }
}

/** Parses SSE `data:` lines from a streamed chat completion. */
export class StreamAccumulator {
  private content = "";
  private reasoning = "";
  private readonly calls: { id: string; name: string; args: string }[] = [];
  private finishReason: string | null = null;
  private usage: Usage | null = null;

  line(line: string, onDelta?: CompleteOptions["onDelta"]): void {
    if (!line.startsWith("data:")) return;
    const data = line.slice(5).trim();
    if (!data || data === "[DONE]") return;
    let j: {
      choices?: {
        delta?: {
          content?: string | null;
          reasoning_content?: string | null;
          tool_calls?: { index: number; id?: string; function?: { name?: string; arguments?: string } }[];
        };
        finish_reason?: string | null;
      }[];
      usage?: {
        prompt_tokens?: number;
        completion_tokens?: number;
        prompt_tokens_details?: { cached_tokens?: number };
        prompt_cache_hit_tokens?: number;
        completion_tokens_details?: { reasoning_tokens?: number };
      } | null;
    };
    try {
      j = JSON.parse(data);
    } catch {
      return;
    }
    const choice = j.choices?.[0];
    const d = choice?.delta;
    if (d?.reasoning_content) {
      this.reasoning += d.reasoning_content;
      onDelta?.("reasoning", d.reasoning_content);
    }
    if (d?.content) {
      this.content += d.content;
      onDelta?.("content", d.content);
    }
    for (const t of d?.tool_calls ?? []) {
      const e = (this.calls[t.index] ??= { id: "", name: "", args: "" });
      if (t.id) e.id = t.id;
      if (t.function?.name) e.name += t.function.name;
      if (t.function?.arguments) e.args += t.function.arguments;
    }
    if (choice?.finish_reason) this.finishReason = choice.finish_reason;
    if (j.usage) {
      this.usage = {
        prompt_tokens: j.usage.prompt_tokens ?? 0,
        completion_tokens: j.usage.completion_tokens ?? 0,
        cached_tokens: j.usage.prompt_cache_hit_tokens ?? j.usage.prompt_tokens_details?.cached_tokens ?? 0,
        reasoning_tokens: j.usage.completion_tokens_details?.reasoning_tokens ?? 0,
      };
    }
  }

  result(): Omit<Completion, "interrupted" | "elapsedMs"> {
    return {
      content: this.content,
      reasoning: this.reasoning,
      toolCalls: this.calls
        .filter((c) => c && c.name)
        .map((c, i) => ({ id: c.id || `call_${i}`, type: "function" as const, function: { name: c.name, arguments: c.args || "{}" } })),
      finishReason: this.finishReason,
      usage: this.usage,
    };
  }
}

export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const t = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => {
      clearTimeout(t);
      reject(signal.reason);
    }, { once: true });
  });
}
