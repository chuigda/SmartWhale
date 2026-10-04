import type { BridgeEvent } from "../bridge/client.ts";

export type Urgency = "critical" | "urgent" | "wake" | "normal";

export const RANK: Record<Urgency, number> = { normal: 0, wake: 1, urgent: 2, critical: 3 };

export interface QueuedEvent {
  method: string;
  params: Record<string, unknown>;
  at: number;
  urgency: Urgency;
}

const URGENT = new Set(["event.hurt", "event.task_failed", "event.task_finished"]);
/** These interrupt even a turn that was itself started by an interrupt (docs/DESIGN.md §7.3). */
const CRITICAL = new Set(["event.death", "event.disconnected", "event.disconnect_screen"]);
const IGNORED = new Set(["event.respawned"]);
/** Task endings the model caused itself, or that another event already reports. */
const QUIET_TASK_REASONS = new Set(["superseded", "cancelled", "died", "disconnected"]);

export function mentions(message: string, names: string[]): boolean {
  const m = message.toLowerCase();
  return names.some((n) => n && m.includes(n.toLowerCase()));
}

export function classify(e: BridgeEvent, names: string[]): Urgency | null {
  if (IGNORED.has(e.method)) return null;
  if (CRITICAL.has(e.method)) return "critical";
  if (e.method === "event.task_failed" && QUIET_TASK_REASONS.has(String(e.params.reason))) return "normal";
  if (URGENT.has(e.method)) return "urgent";
  if (e.method === "event.chat") {
    const p = e.params as { kind?: string; message?: string; mentions_me?: boolean };
    if (p.kind === "whisper" || p.mentions_me || (p.kind === "player" && mentions(String(p.message ?? ""), names))) return "wake";
  }
  return "normal";
}

/** Events collected between turns, drained into the next [status] message. */
export class EventQueue {
  private items: QueuedEvent[] = [];

  push(e: QueuedEvent): void {
    const last = this.items.at(-1);
    if (e.method === "event.item_picked" && last?.method === "event.item_picked") {
      last.params = mergePicked(last.params, e.params);
      return;
    }
    this.items.push(e);
  }

  get length(): number {
    return this.items.length;
  }

  has(pred: (e: QueuedEvent) => boolean): boolean {
    return this.items.some(pred);
  }

  drain(): QueuedEvent[] {
    const out = this.items;
    this.items = [];
    return out;
  }
}

function mergePicked(a: Record<string, unknown>, b: Record<string, unknown>): Record<string, unknown> {
  const counts = new Map<string, number>();
  for (const p of [a, b]) {
    for (const it of (p.items as { item: string; count: number }[] | undefined) ?? []) counts.set(it.item, (counts.get(it.item) ?? 0) + it.count);
  }
  return { items: [...counts].map(([item, count]) => ({ item, count })) };
}

const fmtPos = (p: unknown): string => {
  const v = p as { x?: number; y?: number; z?: number } | undefined;
  return v && typeof v.x === "number" ? `(${Math.round(v.x)}, ${Math.round(v.y ?? 0)}, ${Math.round(v.z ?? 0)})` : "?";
};

/** One line per event, compact enough to inject every turn. */
export function formatEvent(e: QueuedEvent): string {
  const p = e.params as Record<string, any>;
  const t = new Date(e.at).toISOString().slice(11, 19);
  const name = e.method.replace(/^event\./, "");
  let text: string;
  switch (e.method) {
    case "event.chat": {
      const who = p.sender ?? "server";
      text = p.kind === "whisper" ? `whisper from ${who}: ${p.message}` : p.kind === "system" ? `system message: ${p.raw ?? p.message}` : `<${who}> ${p.message}`;
      break;
    }
    case "event.hurt":
      text = `HURT -${p.amount} hp, now ${p.health} hp, source ${p.source ?? "?"}${p.attacker ? ` by ${p.attacker} (${p.attacker_type}, id ${p.attacker_id})` : ""}`;
      break;
    case "event.death":
      text = `DIED at ${fmtPos(p.pos)} in ${p.dimension ?? "?"}: ${p.message ?? "unknown cause"}`;
      break;
    case "event.task_finished":
    case "event.task_failed": {
      const { task_id, kind, reason, hint, elapsed_s, pos, inventory_delta, ...rest } = p;
      text = `${name === "task_failed" ? "TASK FAILED" : "task finished"}: ${kind} #${task_id} after ${elapsed_s}s at ${fmtPos(pos)}`;
      if (reason) text += `, reason=${reason}`;
      if (hint) text += ` (hint: ${hint})`;
      if (inventory_delta && Object.keys(inventory_delta).length) text += `, inventory ${JSON.stringify(inventory_delta)}`;
      if (Object.keys(rest).length) text += ` ${JSON.stringify(rest)}`;
      break;
    }
    case "event.item_picked":
      text = `picked up ${(p.items as { item: string; count: number }[]).map((i) => `${i.count}x ${i.item}`).join(", ")}`;
      break;
    case "event.player_joined":
    case "event.player_left":
      text = `${p.name} ${name === "player_joined" ? "joined" : "left"} the game`;
      break;
    case "event.time":
      text = `it is ${p.phase} now`;
      break;
    case "event.screen_opened":
      text = `screen opened: ${p.title} (${p.menu_type ?? p.class})`;
      break;
    case "event.screen_closed":
      text = `screen closed: ${p.title}`;
      break;
    default:
      text = `${name} ${JSON.stringify(p)}`;
  }
  return `[${t}] ${text}`;
}
