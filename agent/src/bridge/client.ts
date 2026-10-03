import { EventEmitter } from "node:events";
import WebSocket from "ws";
import { logger } from "../util/log.ts";

const log = logger("bridge");

export class RpcError extends Error {
  readonly code: number;
  readonly hint?: string;
  constructor(code: number, message: string, hint?: string) {
    super(message);
    this.code = code;
    this.hint = hint;
  }
}

interface Pending {
  resolve(v: unknown): void;
  reject(e: Error): void;
  timer: NodeJS.Timeout;
}

export interface HelloResult {
  bridge_version: string;
  protocol: number;
  minecraft: string;
  neoforge: string | null;
  mods: { id: string; name: string; version: string }[];
  capabilities: string[];
}

export interface BridgeEvent {
  method: string;
  params: Record<string, unknown>;
}

/**
 * JSON-RPC 2.0 client for the bridge mod. Emits "event" for notifications and "close" on disconnect.
 */
export class BridgeClient extends EventEmitter<{ event: [BridgeEvent]; close: [] }> {
  private ws: WebSocket | null = null;
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();
  private readonly url: string;
  private readonly token: string;

  constructor(url: string, token: string) {
    super();
    this.url = url;
    this.token = token;
  }

  get connected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  /** Retries until the bridge is listening (the game may take minutes to start) or the signal aborts. */
  async connect(opts: { retryMs?: number; signal?: AbortSignal } = {}): Promise<HelloResult> {
    const retryMs = opts.retryMs ?? 2000;
    let attempt = 0;
    while (true) {
      opts.signal?.throwIfAborted();
      try {
        await this.open();
        return await this.call<HelloResult>("bridge.hello", { token: this.token, protocol: 1 });
      } catch (e) {
        if (e instanceof RpcError) throw e;
        if (attempt++ % 15 === 0) log.info(`Waiting for bridge at ${this.url} (${(e as Error).message})`);
        await new Promise((r) => setTimeout(r, retryMs));
      }
    }
  }

  private open(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url, { handshakeTimeout: 5000 });
      ws.once("open", () => {
        this.ws = ws;
        ws.on("message", (data) => this.onMessage(data.toString()));
        ws.on("close", () => {
          if (this.ws === ws) this.ws = null;
          for (const [, p] of this.pending) {
            clearTimeout(p.timer);
            p.reject(new Error("Bridge connection closed"));
          }
          this.pending.clear();
          this.emit("close");
        });
        resolve();
      });
      ws.once("error", (e) => {
        if (this.ws !== ws) reject(e);
        else log.warn(`WebSocket error: ${e.message}`);
      });
    });
  }

  call<T = unknown>(method: string, params: Record<string, unknown> = {}, timeoutMs = 15000): Promise<T> {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return Promise.reject(new Error("Bridge not connected"));
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer });
      ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
    });
  }

  close(): void {
    this.ws?.close();
  }

  private onMessage(text: string): void {
    let msg: {
      id?: number | null;
      method?: string;
      params?: Record<string, unknown>;
      result?: unknown;
      error?: { code: number; message: string; data?: { hint?: string } };
    };
    try {
      msg = JSON.parse(text);
    } catch {
      log.warn(`Unparseable message from bridge: ${text.slice(0, 200)}`);
      return;
    }
    if (msg.method && msg.id === undefined) {
      this.emit("event", { method: msg.method, params: msg.params ?? {} });
      return;
    }
    if (typeof msg.id !== "number") {
      if (msg.error) log.warn(`Bridge error without id: ${msg.error.message}`);
      return;
    }
    const p = this.pending.get(msg.id);
    if (!p) return;
    this.pending.delete(msg.id);
    clearTimeout(p.timer);
    if (msg.error) p.reject(new RpcError(msg.error.code, msg.error.message, msg.error.data?.hint));
    else p.resolve(msg.result);
  }
}
