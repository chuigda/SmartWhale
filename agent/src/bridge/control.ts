import http from "node:http";
import { RpcError, type BridgeClient, type BridgeEvent } from "./client.ts";
import { logger } from "../util/log.ts";

const log = logger("control");

interface Stored extends BridgeEvent {
  seq: number;
  time: string;
}

/**
 * Local debugging endpoint (127.0.0.1 only) for driving the bot by hand or from scripts while the agent
 * owns the single bridge connection:
 *   POST /rpc     {"method": "...", "params": {...}, "timeout_ms"?: n}  → {"result"} | {"error"}
 *   GET  /events?after=<seq>&wait=<seconds>                              → {"events": [...], "last": seq}
 */
export function startControlServer(bridge: BridgeClient, port: number): http.Server {
  const events: Stored[] = [];
  let seq = 0;
  const waiters = new Set<() => void>();
  bridge.on("event", (e) => {
    events.push({ ...e, seq: ++seq, time: new Date().toISOString() });
    if (events.length > 500) events.shift();
    for (const w of waiters) w();
  });

  const server = http.createServer(async (req, res) => {
    const reply = (status: number, body: unknown) => {
      res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
      res.end(JSON.stringify(body));
    };
    try {
      const url = new URL(req.url ?? "/", "http://127.0.0.1");
      if (req.method === "POST" && url.pathname === "/rpc") {
        const chunks: Buffer[] = [];
        for await (const c of req) chunks.push(c as Buffer);
        const body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
        try {
          const result = await bridge.call(body.method, body.params ?? {}, body.timeout_ms ?? 130_000);
          reply(200, { result });
        } catch (e) {
          if (e instanceof RpcError) reply(200, { error: { code: e.code, message: e.message, hint: e.hint } });
          else reply(502, { error: { message: (e as Error).message } });
        }
        return;
      }
      if (req.method === "GET" && url.pathname === "/events") {
        const after = Number(url.searchParams.get("after") ?? 0);
        const wait = Math.min(Number(url.searchParams.get("wait") ?? 0), 120) * 1000;
        const pick = () => events.filter((e) => e.seq > after);
        if (pick().length === 0 && wait > 0) {
          await new Promise<void>((resolve) => {
            const done = () => {
              clearTimeout(timer);
              waiters.delete(done);
              resolve();
            };
            const timer = setTimeout(done, wait);
            waiters.add(done);
          });
        }
        reply(200, { events: pick(), last: seq });
        return;
      }
      reply(404, { error: { message: "POST /rpc or GET /events" } });
    } catch (e) {
      reply(400, { error: { message: (e as Error).message } });
    }
  });
  server.listen(port, "127.0.0.1", () => log.info(`Control endpoint on http://127.0.0.1:${port} (POST /rpc, GET /events)`));
  return server;
}
