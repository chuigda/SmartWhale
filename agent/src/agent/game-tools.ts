import { z } from "zod";
import { RpcError, type BridgeClient } from "../bridge/client.ts";
import { Ids, Pos, tool, ToolError, type Tool } from "./tools.ts";

const MC_CHAT_MAX = 256;
const Hand = z.enum(["main", "off"]).optional().describe("Default: main (interact tools try main, then off)");
const Face = z.enum(["down", "up", "north", "south", "west", "east"]).optional();
const Timeout = z.number().int().min(5).max(3600).optional().describe("Seconds before the task fails; default 300");
const Selectors = z
  .array(z.union([z.string(), z.number().int()]))
  .min(1)
  .describe('Entity ids, entity type ids ("minecraft:zombie"), tags ("#hostile", "#players") or "player:<name>"');

const TASK_HINT = 'Returns {task_id} at once; the result arrives later as a task_finished/task_failed event. Call wait {until:"task_done"} afterwards.';

const Roe = z
  .object({
    radius: z.number().int().min(1).max(64).optional().describe("Search radius for targets around you; default 16"),
    leash: z
      .object({
        center: z.union([z.literal("start"), Pos]).optional(),
        max_distance: z.number().int().min(1).max(128).optional(),
      })
      .optional()
      .describe("Never chase farther than max_distance from center; default start, 24"),
    retaliate: z.boolean().optional().describe("Also fight anything that attacks you (still subject to never_attack); default true"),
    never_attack: Selectors.optional().describe('Never hit these; highest priority. Default ["#players"] unless targets names a player'),
    weapon: z.string().optional().describe('"auto" (best melee weapon, never guns) or an item id; default auto'),
    stop_if: z
      .object({
        health_below: z.number().min(0).optional().describe("Default 6"),
        targets_more_than: z.number().int().min(1).optional(),
        seen: Selectors.optional().describe('Stop if any of these appear in range, e.g. ["minecraft:creeper"]'),
        duration_s: z.number().int().min(1).max(600).optional().describe("Default 60"),
        no_target_for_s: z.number().int().min(1).max(120).optional().describe("Fight is over after this long without a target; default 5"),
      })
      .optional(),
    on_stop: z
      .union([z.enum(["stand", "flee"]), z.object({ retreat_to: Pos })])
      .optional()
      .describe("What to do when a stop_if rule fires; default stand"),
  })
  .optional()
  .describe("Rules of engagement for this fight only; omitted fields use defaults");

/** Game tools mapping 1:1 to bridge methods (docs/DESIGN.md §7.2). Only methods the bridge reports are exposed. */
export function gameTools(bridge: BridgeClient, capabilities: string[]): Tool[] {
  const call = async (method: string, params: object = {}, timeoutMs = 15000) => {
    try {
      return await bridge.call(method, params as Record<string, unknown>, timeoutMs);
    } catch (e) {
      if (e instanceof RpcError) throw new ToolError(e.message, e.hint);
      throw e;
    }
  };
  const all: { method: string; tool: Tool }[] = [];
  const add = (method: string, name: string, description: string, schema: z.ZodObject = z.object({}), opts: { timeoutMs?: number; bulky?: boolean } = {}) =>
    all.push({ method, tool: tool({ name, description, schema, bulky: opts.bulky, run: (a) => call(method, a as object, opts.timeoutMs) }) });

  add("observe.status", "observe_status",
    "Full status: position, health, food, air, xp, armor, effects, biome, light, time, weather, held items, current task. A short status is already given each turn; call this only when you need more.");
  add("observe.inventory", "observe_inventory",
    "Inventory: hotbar (selected slot marked), main inventory, armor, offhand, totals per item. Slots: 0-8 hotbar, 9-35 main, 36-39 armor (feet→head), 40 offhand.",
    z.object({}), { bulky: true });
  add("observe.blocks", "observe_blocks", "Blocks around you. mode=summary: count and nearest position per block type; mode=list: positions.",
    z.object({
      radius: z.number().int().min(1).max(16).optional().describe("Default 8"),
      blocks: Ids.optional().describe("Only these block ids/#tags"),
      mode: z.enum(["summary", "list"]).optional(),
      limit: z.number().int().min(1).max(500).optional(),
    }), { bulky: true });
  add("observe.find_blocks", "observe_find_blocks", "Nearest blocks of the given types that you can perceive.",
    z.object({ blocks: Ids, radius: z.number().int().min(1).max(64).optional().describe("Default 32"), limit: z.number().int().min(1).max(64).optional().describe("Default 10") }),
    { bulky: true });
  add("observe.block", "observe_block", "One block: id, blockstate properties, hardness, whether it needs a tool, has a block entity, is visible/reachable.",
    z.object({ pos: Pos }));
  add("observe.entities", "observe_entities", "Nearby entities with id, type, name, distance, position, health, hostile/player flags, visible/reachable.",
    z.object({
      radius: z.number().int().min(1).max(64).optional().describe("Default 16"),
      filter: z.string().optional().describe("hostile | player | item | living | an entity type id"),
      limit: z.number().int().min(1).max(200).optional(),
    }), { bulky: true });
  add("observe.players", "observe_players", "Online players (tab list), nearby ones marked.");
  add("observe.screen", "observe_screen", "The open screen/GUI: menu type, title, slots with items, clickable widgets, progress data.", z.object({}), { bulky: true });

  all.push({
    method: "action.chat",
    tool: tool({
      name: "action_chat",
      description: `Send a public chat message (max ${MC_CHAT_MAX} characters), or a command: only /msg, /tell, /w, /r, /me. Reply in the language the other player used.`,
      schema: z.object({ message: z.string().min(1) }),
      run: async ({ message }) => {
        if (message.length > MC_CHAT_MAX) throw new ToolError(`Message is ${message.length} characters; the limit is ${MC_CHAT_MAX}`, "Say it shorter");
        return call("action.chat", { message });
      },
    }),
  });
  add("action.look", "action_look", "Turn to look at a position, an entity, or a yaw/pitch.",
    z.object({ pos: z.object({ x: z.number(), y: z.number(), z: z.number() }).optional(), entity_id: z.number().int().optional(), yaw: z.number().optional(), pitch: z.number().optional() }));
  add("action.select_hotbar", "action_select_hotbar", "Select a hotbar slot (0-8).", z.object({ slot: z.number().int().min(0).max(8) }));
  add("action.equip", "action_equip", "Move an item from your inventory to a hand or armor slot.",
    z.object({ item: Ids, slot: z.enum(["mainhand", "offhand", "head", "chest", "legs", "feet"]).optional().describe("Default mainhand") }));
  add("action.drop", "action_drop", "Drop items, by item id or inventory slot.",
    z.object({ item: Ids.optional(), slot: z.number().int().min(0).max(40).optional(), count: z.number().int().min(1).optional().describe("Default: the whole stack(s)") }));
  add("action.eat", "action_eat",
    "Eat: picks food from your inventory (prefer lists item ids to try first), eats it and puts your previous item back in hand.",
    z.object({ item: Ids.optional().describe("Eat exactly this"), prefer: z.array(z.string()).optional() }), { timeoutMs: 15000 });
  add("action.use_item", "action_use_item",
    "Right-click with the held item (drink, draw a bow, use a mod item). Usable items are held until finished, or for duration_ticks.",
    z.object({ hand: Hand, duration_ticks: z.number().int().min(1).max(1200).optional() }), { timeoutMs: 75000 });
  add("action.interact_block", "action_interact_block",
    "Right-click a block within reach: open/close doors and gates, open chests and machines, press buttons, use a bed.",
    z.object({ pos: Pos, face: Face, hand: Hand }));
  add("action.break_block", "action_break_block",
    "Break one block within reach (auto-selects the right tool, or bare hand). For many blocks use task_mine.",
    z.object({ pos: Pos, auto_tool: z.boolean().optional() }), { timeoutMs: 60000 });
  add("action.place_block", "action_place_block", "Place a block from your inventory at pos (must be within reach).",
    z.object({ item: Ids, pos: Pos, against: Face.describe("Which neighbour to place against; default automatic") }));
  add("action.interact_entity", "action_interact_entity", "Right-click an entity within reach (trade, ride, feed, shear).",
    z.object({ entity_id: z.number().int(), hand: Hand }));
  add("action.attack", "action_attack",
    "Attack an entity within reach once (waits for the attack cooldown). For a real fight use task_fight instead.",
    z.object({ entity_id: z.number().int() }), { timeoutMs: 10000 });
  add("action.respawn", "action_respawn", "Respawn after death (normally done automatically).");
  add("menu.close", "menu_close", "Close the open screen.");

  add("task.goto", "task_goto", `Walk (pathfinding) to a position. Give pos (with optional range), or xz, or y. ${TASK_HINT}`,
    z.object({
      pos: Pos.optional(),
      range: z.number().int().min(0).max(64).optional().describe("Stop within this many blocks of pos"),
      xz: z.object({ x: z.number().int(), z: z.number().int() }).optional(),
      y: z.number().int().optional(),
      timeout_s: Timeout,
    }));
  add("task.goto_block", "task_goto_block", `Walk next to the nearest perceivable block of the given types. ${TASK_HINT}`,
    z.object({ blocks: Ids, radius: z.number().int().min(1).max(64).optional(), timeout_s: Timeout }));
  add("task.mine", "task_mine",
    `Mine count blocks of the given types nearby (walks there, picks the right tool, collects drops). Fails with reason=no_tool if the drop needs a tool you lack. ${TASK_HINT}`,
    z.object({ blocks: Ids, count: z.number().int().min(1).max(256).optional().describe("Default 1"), radius: z.number().int().min(4).max(64).optional().describe("Default 32"), timeout_s: Timeout }));
  add("task.fight", "task_fight",
    `Melee fight: chase and attack targets until they are dead or a rules-of-engagement stop condition fires (then fails with reason=roe:<rule>). Equips the best melee weapon. ${TASK_HINT}`,
    z.object({ targets: Selectors, roe: Roe, timeout_s: Timeout }));
  add("task.flee", "task_flee", `Run away from threats (default: all hostile mobs nearby) until they are all farther than distance. ${TASK_HINT}`,
    z.object({ from: Selectors.optional(), distance: z.number().int().min(4).max(128).optional().describe("Default 24"), timeout_s: Timeout }));
  add("task.surface", "task_surface", `Swim to the nearest air or land (prefers land). Use it when underwater and low on air. ${TASK_HINT}`,
    z.object({ timeout_s: Timeout }));
  add("task.follow", "task_follow", `Follow a player or entity. ${TASK_HINT}`,
    z.object({ player: z.string().optional(), entity_id: z.number().int().optional(), duration_s: z.number().int().min(5).max(3600).optional().describe("Default 300"), timeout_s: Timeout }));
  add("task.explore", "task_explore", `Wander towards unexplored chunks. ${TASK_HINT}`,
    z.object({ origin: z.object({ x: z.number().int(), z: z.number().int() }).optional(), duration_s: z.number().int().min(5).max(1800).optional().describe("Default 60"), timeout_s: Timeout }));
  add("task.farm", "task_farm", `Harvest and replant mature crops nearby. ${TASK_HINT}`,
    z.object({ range: z.number().int().min(4).max(64).optional().describe("Default 16"), duration_s: z.number().int().min(5).max(1800).optional().describe("Default 120"), timeout_s: Timeout }));
  add("task.collect_items", "task_collect_items", `Pick up dropped items nearby. ${TASK_HINT}`,
    z.object({ radius: z.number().int().min(1).max(32).optional().describe("Default 8"), timeout_s: Timeout }));
  add("task.status", "task_status", "The current task and its progress.");
  add("task.cancel", "task_cancel", "Cancel the current task and stop moving.");

  const caps = new Set(capabilities);
  return all.filter((t) => caps.has(t.method)).map((t) => t.tool);
}
