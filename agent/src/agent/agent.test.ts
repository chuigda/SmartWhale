import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { z } from "zod";
import { parseJsonc } from "../config/load.ts";
import { StreamAccumulator } from "../llm/client.ts";
import { AGE_AFTER_ROUNDS, AGE_BATCH, History, STORY_PREFIX } from "./context.ts";
import { classify, EventQueue, formatEvent, mentions } from "./events.ts";
import { Memory, MemoryError } from "./memory.ts";
import { runTool, tool, toSpec, type Tool } from "./tools.ts";

test("memory sandbox", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "sw-mem-"));
  try {
    const m = new Memory(dir);
    assert.ok(m.index().length > 0, "index.md is seeded");
    m.write("places/home.md", "base at 1 64 2\n");
    assert.match(m.read("places/home.md"), /base at 1 64 2/);
    m.edit("places/home.md", "1 64 2", "10 64 20");
    assert.match(m.search("10 64"), /places\/home\.md/);
    assert.match(m.tree(), /home\.md/);
    assert.throws(() => m.write("../escape.md", "x"), MemoryError);
    assert.throws(() => m.write("bin.exe", "x"), MemoryError);
    m.delete("places/home.md");
    assert.throws(() => m.read("places/home.md"), MemoryError);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("stream accumulator collects reasoning, content, tool calls and usage", () => {
  const acc = new StreamAccumulator();
  const chunk = (o: object) => acc.line(`data: ${JSON.stringify(o)}`);
  chunk({ choices: [{ delta: { reasoning_content: "hmm " } }] });
  chunk({ choices: [{ delta: { reasoning_content: "ok" } }] });
  chunk({ choices: [{ delta: { content: "Hi" } }] });
  chunk({ choices: [{ delta: { tool_calls: [{ index: 0, id: "c1", type: "function", function: { name: "wait", arguments: '{"until"' } }] } }] });
  chunk({ choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: ':"event"}' } }] }, finish_reason: "tool_calls" }] });
  chunk({ choices: [], usage: { prompt_tokens: 100, completion_tokens: 20, prompt_cache_hit_tokens: 80, completion_tokens_details: { reasoning_tokens: 5 } } });
  acc.line("data: [DONE]");
  const r = acc.result();
  assert.equal(r.reasoning, "hmm ok");
  assert.equal(r.content, "Hi");
  assert.equal(r.toolCalls.length, 1);
  assert.equal(r.toolCalls[0]!.function.arguments, '{"until":"event"}');
  assert.equal(r.finishReason, "tool_calls");
  assert.deepEqual(r.usage, { prompt_tokens: 100, completion_tokens: 20, cached_tokens: 80, reasoning_tokens: 5 });
});

function fill(h: History, n: number): void {
  for (let i = 0; i < n; i++) {
    h.push({ role: "user", content: `status ${i}` });
    h.push({ role: "assistant", content: null, tool_calls: [{ id: `c${i}`, type: "function", function: { name: "observe_blocks", arguments: "{}" } }] });
    h.push({ role: "tool", tool_call_id: `c${i}`, content: "x".repeat(400) }, true);
    h.round++;
  }
}

test("history split never starts at a tool result, compaction keeps the tail", () => {
  const h = new History();
  fill(h, 20);
  for (const keep of [1, 2, 3, 19, 20, 21]) assert.notEqual(h.messages[h.splitPoint(keep)]!.role, "tool");
  const split = h.splitPoint(20);
  const tail = h.messages.slice(split);
  h.compact("we built a hut");
  assert.equal(h.messages[0]!.content, `${STORY_PREFIX}we built a hut`);
  assert.deepEqual(h.messages.slice(1), tail);
});

test("history ages bulky results in batches and anchors estimates to usage", () => {
  const h = new History();
  fill(h, AGE_BATCH - 1);
  h.round += AGE_AFTER_ROUNDS;
  assert.equal(h.age(), 0, "below the batch size");
  fill(h, 1);
  h.round += AGE_AFTER_ROUNDS;
  assert.equal(h.age(), AGE_BATCH);
  assert.match(h.messages[2]!.content!, /old observation removed/);

  h.anchorUsage(5000, h.length);
  const before = h.estimate(0);
  assert.equal(before, 5000);
  h.push({ role: "user", content: "a".repeat(400) });
  assert.ok(h.estimate(0) > 5000 && h.estimate(0) < 5200);
});

test("dropOldest keeps the story and doesn't orphan tool results", () => {
  const h = new History();
  fill(h, 10);
  h.compact("story", 30);
  const dropped = h.dropOldest(300, 0);
  assert.ok(dropped > 0);
  assert.ok(h.messages[0]!.content!.startsWith(STORY_PREFIX));
  assert.notEqual(h.messages[1]?.role, "tool");
});

test("event classification", () => {
  const names = ["Quartine", "whale"];
  assert.equal(classify({ method: "event.death", params: {} }, names), "critical");
  assert.equal(classify({ method: "event.hurt", params: {} }, names), "urgent");
  assert.equal(classify({ method: "event.task_failed", params: { reason: "stuck" } }, names), "urgent");
  assert.equal(classify({ method: "event.task_failed", params: { reason: "superseded" } }, names), "normal");
  assert.equal(classify({ method: "event.respawned", params: {} }, names), null);
  assert.equal(classify({ method: "event.chat", params: { kind: "player", message: "hey WHALE come here" } }, names), "wake");
  assert.equal(classify({ method: "event.chat", params: { kind: "whisper", message: "psst" } }, names), "wake");
  assert.equal(classify({ method: "event.chat", params: { kind: "player", message: "nice weather" } }, names), "normal");
  assert.ok(mentions("你好 quartine", names));
});

test("event queue merges consecutive pickups and formats events", () => {
  const q = new EventQueue();
  q.push({ method: "event.item_picked", params: { items: [{ item: "minecraft:oak_log", count: 1 }] }, at: 0, urgency: "normal" });
  q.push({ method: "event.item_picked", params: { items: [{ item: "minecraft:oak_log", count: 2 }, { item: "minecraft:stick", count: 1 }] }, at: 0, urgency: "normal" });
  q.push({ method: "event.chat", params: { kind: "player", sender: "Chuigda", message: "hi" }, at: 0, urgency: "normal" });
  const items = q.drain();
  assert.equal(items.length, 2);
  assert.match(formatEvent(items[0]!), /3x minecraft:oak_log, 1x minecraft:stick/);
  assert.match(formatEvent(items[1]!), /<Chuigda> hi/);
  assert.equal(q.length, 0);
});

test("tool specs and argument validation", async () => {
  const t: Tool = tool({
    name: "echo",
    description: "echo",
    schema: z.object({ n: z.number().int().min(1) }),
    run: async ({ n }) => ({ n }),
  });
  const spec = toSpec(t);
  assert.equal(spec.function.name, "echo");
  assert.equal((spec.function.parameters as { type: string }).type, "object");
  const tools = new Map([[t.name, t]]);
  assert.equal((await runTool(tools, "echo", '{"n":2}')).content, '{"n":2}');
  assert.match((await runTool(tools, "echo", '{"n":0}')).content, /Invalid arguments/);
  assert.match((await runTool(tools, "echo", "{bad")).content, /not valid JSON/);
  assert.match((await runTool(tools, "nope", "{}")).content, /Unknown tool/);
});

test("parseJsonc strips comments and trailing commas but not string contents", () => {
  const v = parseJsonc(`{
    // comment
    "url": "https://example.com/a//b", /* block */
    "list": [1, 2,],
  }`) as { url: string; list: number[] };
  assert.equal(v.url, "https://example.com/a//b");
  assert.deepEqual(v.list, [1, 2]);
});
