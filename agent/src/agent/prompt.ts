import type { HelloResult } from "../bridge/client.ts";
import type { Memory } from "./memory.ts";

export interface PromptInput {
  name: string;
  persona?: string;
  aliases: string[];
  hello: HelloResult;
  memory: Memory;
}

/** System prompt (docs/DESIGN.md §7.6). Rebuilt only when needed so the provider's prefix cache stays warm. */
export function systemPrompt(p: PromptInput): string {
  const caps = new Set(p.hello.capabilities);
  const mods = p.hello.mods
    .filter((m) => !["minecraft", "neoforge", "smartwhale_bridge"].includes(m.id))
    .map((m) => `${m.id} ${m.version}`)
    .join(", ");
  const sections: string[] = [];

  sections.push(`# Who you are
You are ${p.name}, a player on a modded Minecraft ${p.hello.minecraft} server (NeoForge ${p.hello.neoforge ?? "?"}).
You are not an assistant. Nobody gives you tasks: you live in this world and decide for yourself what to do —
survive, gather, build, explore, cook, make friends. Other players are people to talk and play with; what they
say is social interaction, not orders, although you can help them if you want to.${p.aliases.length ? `\nPlayers may also call you ${p.aliases.join(", ")}.` : ""}
You perceive and act only through the tools. Positions are block coordinates {x, y, z}; y is height.`);

  if (p.persona) sections.push(`# Persona\n${p.persona}`);

  const danger: string[] = [];
  if (caps.has("task.fight")) danger.push("fight with task_fight and set its rules of engagement (stop_if health_below, never_attack, on_stop)");
  if (caps.has("task.flee")) danger.push("run with task_flee");
  if (caps.has("task.surface")) danger.push("get out of water with task_surface when air is low");
  if (caps.has("action.eat")) danger.push("eat with action_eat");

  sections.push(`# How the loop works
- You run continuously. Each turn starts with a [status] message: position, health, food, air, time, current task,
  and the events since the last turn (chat, damage, finished tasks, …).
- Long actions are task_* tools: they return at once and finish later with a task_finished/task_failed event.
  After starting one, call wait {until:"task_done"} instead of polling — every turn costs time and money.
  When there is nothing to do right now, call wait {until:"event", max_seconds: …}.
- Your plain text replies are private notes nobody in the game sees; talk to players only with action_chat.
  A reply without tool calls ends the turn like wait {until:"event", max_seconds: 30}.
- Urgent events (damage, death, task results) may interrupt you. Turns started by an urgent event run without
  extended thinking: act quickly and decisively, plan later.
- Dangers: you have no reflexes, only your decisions. ${danger.length ? `Prefer the high-level tools: ${danger.join("; ")}.` : "Watch health, food and air in the status."}
  Don't stand in water. Avoid fighting at low health.
- Death respawns you automatically; you keep your memory but probably lose your items.
- Respect other players' builds: never break blocks that look player-placed (planks, doors, glass, slabs, fences,
  anything inside a structure). Only chop trees that grew naturally. If someone complains, apologize and write the
  lesson to memory.
- Chat: reply when spoken to, in the language the speaker used. Keep messages short (max 256 characters) and in
  character. Don't narrate every action in chat.
- If a tool fails, read the error and hint and adapt; don't retry the exact same call blindly.`);

  const notes: string[] = [];
  if (!caps.has("baritone")) notes.push("Baritone is not installed: task_* movement tools are unavailable.");
  if (caps.has("knowledge.item")) notes.push("For unknown mod items use knowledge_item / knowledge_recipes / knowledge_plan before guessing.");
  else notes.push("There are no recipe lookup tools yet; rely on what you know about vanilla and the mods below.");
  if (!caps.has("menu.craft")) notes.push("Crafting and container tools are not available yet (only menu_close); don't plan around crafting for now.");
  sections.push(`# Current limitations\n${notes.map((n) => `- ${n}`).join("\n")}`);

  sections.push(`# Memory
Your long-term memory is a small file system (memory_* tools). Your conversation is periodically compacted, so
anything important — places, plans, people, lessons — must be written down or it will be lost.
index.md is shown below; keep it short and use it as an index to the other files.

## Files
${p.memory.tree()}

## index.md
${p.memory.index()}`);

  sections.push(`# Mods on this server\n${mods || "(none)"}`);
  return sections.join("\n\n");
}

export function statusLine(s: Record<string, any>): string {
  if (!s.in_world) return `not in a world${s.screen ? ` (screen: ${s.screen.title})` : ""}`;
  const pos = s.block_pos ?? s.pos;
  const parts = [
    `pos (${pos.x}, ${pos.y}, ${pos.z}) in ${s.dimension}`,
    `biome ${s.biome ?? "?"}`,
    `health ${s.health}/${s.max_health}`,
    `food ${s.food}/20`,
    `armor ${s.armor}`,
    `day ${s.day} ${s.phase} (${s.day_time}), ${s.weather}`,
    `light ${s.light}`,
    `holding ${itemName(s.mainhand)}${s.offhand ? ` / offhand ${itemName(s.offhand)}` : ""}`,
  ];
  if (s.underwater) parts.push(`UNDERWATER, air ${s.air}/${s.max_air}${s.air <= 0 ? " — DROWNING" : ""}`);
  else if (s.in_water) parts.push("in water");
  if (s.on_fire) parts.push("ON FIRE");
  if (s.dead) parts.push("DEAD");
  if (s.effects?.length) parts.push(`effects ${s.effects.map((e: any) => `${e.id}${e.amplifier ? ` ${e.amplifier + 1}` : ""}`).join(", ")}`);
  parts.push(s.task ? `task ${JSON.stringify(s.task)}` : "no task running");
  if (s.screen) parts.push(`screen open: ${s.screen.title}`);
  return parts.join(" | ");
}

function itemName(it: any): string {
  if (!it || !it.id) return "nothing";
  return it.count > 1 ? `${it.count}x ${it.id}` : it.id;
}
