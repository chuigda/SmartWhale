/**
 * M1 acceptance script: walk to a tree, chop 5 logs, pick up the drops.
 * Needs a running agent started with --control-port (default 25590).
 *   node scripts/m1-demo.ts [port]
 */
const base = `http://127.0.0.1:${process.argv[2] ?? 25590}`;
let lastSeq = 0;

async function rpc<T = any>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  const res = await fetch(`${base}/rpc`, { method: "POST", body: JSON.stringify({ method, params }) });
  const body: any = await res.json();
  if (body.error) throw new Error(`${method}: ${body.error.message}${body.error.hint ? ` (${body.error.hint})` : ""}`);
  return body.result as T;
}

async function waitTask(taskId: string, timeoutS = 300): Promise<any> {
  const deadline = Date.now() + timeoutS * 1000;
  while (Date.now() < deadline) {
    const body: any = await (await fetch(`${base}/events?after=${lastSeq}&wait=30`)).json();
    lastSeq = body.last;
    for (const e of body.events) {
      console.log(`  event ${e.method}`, JSON.stringify(e.params));
      if ((e.method === "event.task_finished" || e.method === "event.task_failed") && e.params.task_id === taskId) return e;
    }
  }
  throw new Error(`task ${taskId} did not finish in ${timeoutS}s`);
}

const logs = (inv: any) => Object.entries(inv.totals ?? {}).filter(([k]) => k.endsWith("_log"));

const before = await rpc("observe.inventory");
lastSeq = ((await (await fetch(`${base}/events?after=999999999`)).json()) as any).last;
console.log("logs before:", logs(before));

const found = await rpc("observe.find_blocks", { blocks: ["#minecraft:logs"], radius: 48, limit: 5 });
console.log("nearest logs:", JSON.stringify(found));

const mine = await rpc("task.mine", { blocks: ["#minecraft:logs"], count: 5 });
console.log("task.mine started:", JSON.stringify(mine));
const done = await waitTask(mine.task_id);
console.log(done.method, JSON.stringify(done.params));

const collect = await rpc("task.collect_items", { radius: 8 });
console.log("task.collect_items:", JSON.stringify(collect));
if (collect.task_id) console.log((await waitTask(collect.task_id, 60)).method);

console.log("logs after:", logs(await rpc("observe.inventory")));
