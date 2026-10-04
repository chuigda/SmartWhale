import path from "node:path";
import { z } from "zod";
import type { BridgeClient, BridgeEvent, HelloResult } from "../bridge/client.ts";
import { sleep, type Completion, type LlmClient, type Message, type ToolSpec, type Usage } from "../llm/client.ts";
import { logger } from "../util/log.ts";
import { estimateTokens, History, renderForSummary, SUMMARY_PROMPT } from "./context.ts";
import { classify, EventQueue, formatEvent, RANK, type QueuedEvent, type Urgency } from "./events.ts";
import { gameTools } from "./game-tools.ts";
import { Memory } from "./memory.ts";
import { memoryTools } from "./memory-tools.ts";
import { statusLine, systemPrompt } from "./prompt.ts";
import { runTool, tool, toSpec, type Tool } from "./tools.ts";
import { Transcript } from "./transcript.ts";

const log = logger("agent");

export const MAX_ROUNDS_PER_TURN = 12;
const MIN_TURN_GAP_MS = 1000;
/** A turn that ends without calling wait behaves like wait {until:"event", max_seconds: this}. */
const IMPLICIT_WAIT_S = 30;
const RESPAWN_ATTEMPTS = 10;
const USAGE_LOG_MS = 3600_000;

export interface AgentOptions {
  bridge: BridgeClient;
  hello: HelloResult;
  llm: LlmClient;
  name: string;
  persona?: string;
  aliases: string[];
  maxTokensPerTurn: number;
  dataDir: string;
}

interface Waiter {
  until: "task_done" | "event";
  resolve(reason: string): void;
}

/** The main loop (docs/DESIGN.md §7.3). */
export class Agent {
  private readonly o: AgentOptions;
  private readonly memory: Memory;
  private readonly transcript: Transcript;
  private readonly history = new History();
  private readonly queue = new EventQueue();
  private readonly tools: Map<string, Tool>;
  private readonly specs: ToolSpec[];
  private readonly names: string[];
  private system = "";
  private systemDirty = true;

  /** Abort controller of the LLM request being streamed, and the minimum urgency that interrupts it. */
  private streaming: { controller: AbortController; threshold: Urgency } | null = null;
  /** An urgent event arrived during the turn: finish the current step, then start an urgent turn. */
  private pendingInterrupt: Urgency | null = null;
  private waiter: Waiter | null = null;
  private respawning = false;
  private compactNoticeGiven = false;
  private stopped = false;

  private readonly totals = { requests: 0, prompt: 0, cached: 0, completion: 0, reasoning: 0, since: Date.now() };

  constructor(o: AgentOptions) {
    this.o = o;
    this.names = [o.name, ...o.aliases];
    this.memory = new Memory(path.join(o.dataDir, "memory"));
    this.transcript = new Transcript(path.join(o.dataDir, "transcripts"), new Date().toISOString().replace(/[:.]/g, "-"));
    const all = [...gameTools(o.bridge, o.hello.capabilities), ...memoryTools(this.memory), this.waitTool()];
    this.tools = new Map(all.map((t) => [t.name, t]));
    this.specs = all.map(toSpec);
    o.bridge.on("event", (e) => this.onEvent(e));
    log.info(`Agent ready with ${all.length} tools; transcript ${this.transcript.file}`);
  }

  stop(): void {
    this.stopped = true;
    this.streaming?.controller.abort();
    this.waiter?.resolve("stopped");
  }

  async run(): Promise<void> {
    let urgent: Urgency | null = null;
    let usageTimer = Date.now();
    while (!this.stopped) {
      const started = Date.now();
      try {
        await this.ensureInWorld();
        urgent = await this.turn(urgent);
      } catch (e) {
        if (this.stopped) break;
        log.error(`Turn failed: ${e instanceof Error ? (e.stack ?? e.message) : String(e)}`);
        this.transcript.write("error", { message: String(e) });
        urgent = null;
        await sleep(10000);
      }
      if (Date.now() - usageTimer > USAGE_LOG_MS) {
        this.logUsage();
        usageTimer = Date.now();
      }
      const gap = MIN_TURN_GAP_MS - (Date.now() - started);
      if (gap > 0) await sleep(gap);
    }
    this.logUsage();
  }

  // ---- events ----

  private onEvent(e: BridgeEvent): void {
    const urgency = classify(e, this.names);
    if (!urgency) return;
    this.transcript.write("event", { method: e.method, params: e.params, urgency });
    if (e.method === "event.death") void this.respawn();
    if (e.method === "event.disconnect_screen" || e.method === "event.world_ready") this.systemDirty = true;
    const q: QueuedEvent = { method: e.method, params: e.params, at: Date.now(), urgency };
    this.queue.push(q);

    if (this.waiter) {
      const taskDone = e.method === "event.task_finished" || e.method === "event.task_failed";
      if (RANK[urgency] >= RANK.wake || (this.waiter.until === "task_done" && taskDone)) this.waiter.resolve(urgency);
    }
    if (RANK[urgency] >= RANK.urgent) {
      if (this.streaming && RANK[urgency] >= RANK[this.streaming.threshold]) {
        log.info(`Interrupting the model: ${e.method}`);
        this.pendingInterrupt = maxUrgency(this.pendingInterrupt, urgency);
        this.streaming.controller.abort();
      } else if (!this.streaming) {
        this.pendingInterrupt = maxUrgency(this.pendingInterrupt, urgency);
      }
    }
  }

  private async respawn(): Promise<void> {
    if (this.respawning) return;
    this.respawning = true;
    try {
      for (let i = 0; i < RESPAWN_ATTEMPTS && !this.stopped; i++) {
        await sleep(1000);
        try {
          await this.o.bridge.call("action.respawn", {});
          log.info("Respawned");
          return;
        } catch (e) {
          log.warn(`Respawn attempt ${i + 1} failed: ${(e as Error).message}`);
        }
      }
    } finally {
      this.respawning = false;
    }
  }

  private async ensureInWorld(): Promise<void> {
    let logged = false;
    while (!this.stopped) {
      const s = await this.status().catch(() => null);
      if (s?.in_world) return;
      if (!logged) log.info("Not in a world; waiting");
      logged = true;
      await sleep(3000);
    }
  }

  private status(): Promise<Record<string, any>> {
    return this.o.bridge.call<Record<string, any>>("observe.status", {});
  }

  // ---- turns ----

  /** Runs one turn. Returns the urgency that should start the next turn, if any. */
  private async turn(startUrgency: Urgency | null): Promise<Urgency | null> {
    // Urgent events that arrived between turns also make this an urgent turn.
    const start = this.pendingInterrupt ? maxUrgency(startUrgency, this.pendingInterrupt) : startUrgency;
    this.pendingInterrupt = null;
    const urgentMode = start !== null && RANK[start] >= RANK.urgent;
    if (this.systemDirty) this.rebuildSystem();

    const status = await this.status();
    const events = this.queue.drain();
    let text = `${urgentMode ? "[URGENT] " : ""}[status] ${statusLine(status)}`;
    text += events.length ? `\n[events]\n${events.map(formatEvent).join("\n")}` : "\n[events] none";
    if (await this.manageContext()) text += `\n[context] ${this.compactNotice()}`;
    this.history.push({ role: "user", content: text });
    this.transcript.write("turn", { urgent: urgentMode, text });

    for (let round = 0; round < MAX_ROUNDS_PER_TURN && !this.stopped; round++) {
      await this.enforceHardLimit();
      const completion = await this.request(urgentMode);
      this.history.round++;
      const calls = completion.interrupted ? [] : completion.toolCalls;
      this.history.push({
        role: "assistant",
        content: completion.interrupted ? `${completion.content}[interrupted]` : completion.content || null,
        reasoning_content: completion.reasoning || undefined,
        tool_calls: calls.length ? calls : undefined,
      });
      if (completion.content) log.info(`model: ${completion.content.slice(0, 300)}`);
      if (completion.interrupted) return this.pendingInterrupt ?? "urgent";
      if (!calls.length) {
        const reason = await this.wait("event", IMPLICIT_WAIT_S);
        return RANK[reason as Urgency] >= RANK.urgent ? (reason as Urgency) : null;
      }

      for (let i = 0; i < calls.length; i++) {
        const call = calls[i]!;
        if (this.pendingInterrupt) {
          this.pushToolResult(call.id, call.function.name, JSON.stringify({ skipped: "interrupted by an urgent event, see the next [status]" }));
          continue;
        }
        if (call.function.name === "wait") {
          const args = parseWaitArgs(call.function.arguments);
          const started = Date.now();
          const reason = typeof args === "string" ? null : await this.wait(args.until, args.max_seconds);
          const result = typeof args === "string" ? { error: "Invalid arguments", hint: args } : { waited_s: Math.round((Date.now() - started) / 1000), woken_by: reason };
          this.pushToolResult(call.id, "wait", JSON.stringify(result));
          for (const rest of calls.slice(i + 1)) this.pushToolResult(rest.id, rest.function.name, JSON.stringify({ skipped: "calls after wait are not run; call wait last" }));
          if (reason === null) break;
          return RANK[reason as Urgency] >= RANK.urgent ? (reason as Urgency) : null;
        }
        log.info(`tool ${call.function.name} ${call.function.arguments.slice(0, 200)}`);
        const result = await runTool(this.tools, call.function.name, call.function.arguments);
        this.pushToolResult(call.id, call.function.name, result.content, result.bulky);
      }
      if (this.pendingInterrupt) return this.pendingInterrupt;
      if (this.history.age()) log.info("Aged old observations");
    }
    return null;
  }

  private pushToolResult(id: string, name: string, content: string, bulky = false): void {
    this.history.push({ role: "tool", tool_call_id: id, content }, bulky);
    this.transcript.write("tool", { name, content });
  }

  private async request(urgentMode: boolean): Promise<Completion> {
    const controller = new AbortController();
    this.streaming = { controller, threshold: urgentMode ? "critical" : "urgent" };
    const messages: Message[] = [{ role: "system", content: this.system }, ...this.history.messages];
    try {
      const c = await this.o.llm.complete({
        messages,
        tools: this.specs,
        maxTokens: this.o.maxTokensPerTurn,
        thinking: urgentMode ? false : undefined,
        signal: controller.signal,
      });
      if (c.usage) {
        this.history.anchorUsage(c.usage.prompt_tokens, this.history.length);
        this.count(c.usage);
      }
      this.transcript.write("completion", {
        urgent: urgentMode,
        interrupted: c.interrupted,
        reasoning: c.reasoning,
        content: c.content,
        tool_calls: c.toolCalls,
        usage: c.usage,
        ms: c.elapsedMs,
      });
      return c;
    } catch (e) {
      if (controller.signal.aborted) {
        // Aborted before any output arrived.
        return { content: "", reasoning: "", toolCalls: [], finishReason: null, usage: null, interrupted: true, elapsedMs: 0 };
      }
      throw e;
    } finally {
      this.streaming = null;
    }
  }

  private wait(until: "task_done" | "event", maxSeconds: number): Promise<string> {
    if (this.queue.has((e) => RANK[e.urgency] >= RANK.wake)) return Promise.resolve("pending_events");
    if (this.pendingInterrupt) return Promise.resolve(this.pendingInterrupt);
    return new Promise((resolve) => {
      const done = (reason: string) => {
        clearTimeout(timer);
        this.waiter = null;
        resolve(reason);
      };
      const timer = setTimeout(() => done("timeout"), maxSeconds * 1000);
      this.waiter = { until, resolve: done };
      if (until === "task_done") {
        this.o.bridge
          .call<{ current?: unknown }>("task.status", {})
          .then((s) => {
            if (!s.current && this.waiter?.resolve === done) done("no_task_running");
          })
          .catch(() => {});
      }
    });
  }

  private waitTool(): Tool {
    return tool({
      name: "wait",
      description:
        'Wait without spending tokens. until="task_done": until the current task finishes (or an important event); until="event": until something happens (chat addressed to you, damage, task result). Returns at once if such events are already pending. Ends your turn: call it last.',
      schema: WaitArgs,
      run: async () => ({ error: "wait is handled by the agent loop" }),
    });
  }

  // ---- context ----

  private rebuildSystem(): void {
    this.system = systemPrompt({ name: this.o.name, persona: this.o.persona, aliases: this.o.aliases, hello: this.o.hello, memory: this.memory });
    this.systemDirty = false;
  }

  private fixedTokens(): number {
    return estimateTokens(this.system) + estimateTokens(JSON.stringify(this.specs));
  }

  /**
   * Above practicalContextWindow: first give the model one turn with a notice (returns true), then compact
   * at the start of the following turn.
   */
  private async manageContext(): Promise<boolean> {
    const est = this.history.estimate(this.fixedTokens());
    if (est <= this.o.llm.config.practicalContextWindow) {
      this.compactNoticeGiven = false;
      return false;
    }
    if (!this.compactNoticeGiven) {
      this.compactNoticeGiven = true;
      return true;
    }
    await this.compact();
    this.compactNoticeGiven = false;
    return false;
  }

  private compactNotice(): string {
    return "Your context is almost full. After this turn older messages will be replaced by a short summary. Write anything you must not forget (plans, places, people, promises) to memory now.";
  }

  private async enforceHardLimit(): Promise<void> {
    const limit = this.o.llm.config.contextWindow - this.o.maxTokensPerTurn;
    if (this.history.estimate(this.fixedTokens()) <= limit) return;
    log.warn("Context over the hard limit; compacting now");
    await this.compact();
    const dropped = this.history.dropOldest(limit, this.fixedTokens());
    if (dropped) log.warn(`Dropped ${dropped} oldest messages`);
  }

  private async compact(): Promise<void> {
    const old = this.history.toSummarize();
    if (!old.length) return;
    log.info(`Compacting ${old.length} messages`);
    const c = await this.o.llm.complete({
      messages: [
        { role: "system", content: SUMMARY_PROMPT },
        { role: "user", content: renderForSummary(old) },
      ],
      maxTokens: 4096,
      thinking: false,
    });
    if (c.usage) this.count(c.usage);
    this.history.compact(c.content);
    this.systemDirty = true;
    this.transcript.write("compact", { messages: old.length, summary: c.content });
  }

  // ---- usage ----

  private count(u: Usage): void {
    this.totals.requests++;
    this.totals.prompt += u.prompt_tokens;
    this.totals.cached += u.cached_tokens;
    this.totals.completion += u.completion_tokens;
    this.totals.reasoning += u.reasoning_tokens;
  }

  private logUsage(): void {
    const t = this.totals;
    const min = Math.round((Date.now() - t.since) / 60000);
    log.info(`Usage over ${min} min: ${t.requests} requests, prompt ${t.prompt} (cached ${t.cached}), completion ${t.completion} (reasoning ${t.reasoning})`);
    this.transcript.write("usage", { ...t });
  }
}

const WaitArgs = z.object({
  until: z.enum(["task_done", "event"]),
  max_seconds: z.number().int().min(1).max(600).optional().describe("Default 60"),
});

function parseWaitArgs(raw: string): { until: "task_done" | "event"; max_seconds: number } | string {
  try {
    const r = WaitArgs.safeParse(raw.trim() ? JSON.parse(raw) : {});
    if (!r.success) return z.prettifyError(r.error);
    return { until: r.data.until, max_seconds: r.data.max_seconds ?? 60 };
  } catch (e) {
    return `Arguments are not valid JSON: ${(e as Error).message}`;
  }
}

function maxUrgency(a: Urgency | null, b: Urgency): Urgency {
  return a && RANK[a] >= RANK[b] ? a : b;
}
