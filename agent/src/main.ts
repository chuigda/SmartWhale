import crypto from "node:crypto";
import path from "node:path";
import { parseArgs } from "node:util";
import { loadConfig, loadLlmConfig, readPassword } from "./config/load.ts";
import { LlmClient } from "./llm/client.ts";
import { Agent } from "./agent/agent.ts";
import { prepareInstance } from "./launcher/instance.ts";
import { obtainSession, prefetchMetadata } from "./launcher/yggdrasil.ts";
import { freePort, launch, type GameProcess } from "./launcher/game.ts";
import { BridgeClient } from "./bridge/client.ts";
import { startControlServer } from "./bridge/control.ts";
import { resolveFromRoot } from "./util/paths.ts";
import { logger } from "./util/log.ts";

const log = logger("main");

const USAGE = `Usage:
  node src/main.ts <config.json>                         launch the headless client and attach
  node src/main.ts <config.json> --attach <ws-url> --token <t>   attach to a running client
Options:
  --control-port <n>   local HTTP endpoint for manual RPC calls (see src/bridge/control.ts)
  --no-agent           connect only; don't start the LLM agent`;

async function main(): Promise<void> {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      attach: { type: "string" },
      token: { type: "string" },
      "control-port": { type: "string" },
      "no-agent": { type: "boolean" },
      help: { type: "boolean", short: "h" },
    },
  });
  const configFile = positionals[0];
  if (values.help || !configFile) {
    console.log(USAGE);
    process.exit(configFile ? 0 : 1);
  }
  const config = loadConfig(configFile);
  const abort = new AbortController();
  let game: GameProcess | null = null;

  const shutdown = () => {
    log.info("Shutting down");
    abort.abort();
    game?.kill();
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);

  let url: string;
  let token: string;
  if (values.attach) {
    url = values.attach;
    token = values.token ?? "dev";
  } else {
    const dataDir = resolveFromRoot(path.join("data", config.name));
    const session = await obtainSession(config.auth.apiRoot, path.join(dataDir, "auth.json"), {
      username: config.auth.username,
      profile: config.auth.profile,
      password: () => readPassword(config),
    });
    const prefetched = await prefetchMetadata(config.auth.apiRoot);
    const layout = prepareInstance(config);
    const port = config.bridge.port || (await freePort());
    token = crypto.randomBytes(24).toString("hex");
    url = `ws://127.0.0.1:${port}/rpc`;
    game = launch({
      config,
      layout,
      session,
      prefetched,
      bridgePort: port,
      bridgeToken: token,
      logFile: path.join(dataDir, "logs", "minecraft.log"),
    });
    game.exited.then(() => {
      if (!abort.signal.aborted) {
        log.error("Minecraft exited; stopping (supervision/restart arrives in M4)");
        process.exit(1);
      }
    });
  }

  const bridge = new BridgeClient(url, token);
  const hello = await bridge.connect({ signal: abort.signal });
  log.info(`Bridge ${hello.bridge_version} | MC ${hello.minecraft} | NeoForge ${hello.neoforge} | ${hello.mods.length} mods`);
  log.info(`Mods: ${hello.mods.map((m) => m.id).join(", ")}`);

  bridge.on("event", (e) => log.info(`event ${e.method}`, e.params));
  bridge.on("close", () => log.warn("Bridge connection closed"));
  if (values["control-port"]) startControlServer(bridge, Number(values["control-port"]));

  if (values["no-agent"]) return;
  const llmConfig = loadLlmConfig(config);
  if (!llmConfig) {
    log.warn('No "llm" section in the config; running without the agent (see config/bot.example.json)');
    return;
  }
  const llm = new LlmClient(llmConfig);
  log.info(`LLM ${llm.config.model} at ${llm.config.baseUrl}`);
  const { name } = await bridge.call<{ name: string }>("observe.status", {});
  const agent = new Agent({
    bridge,
    hello,
    llm,
    name,
    persona: config.agent.persona,
    aliases: config.agent.aliases,
    maxTokensPerTurn: config.agent.maxTokensPerTurn,
    dataDir: resolveFromRoot(path.join("data", config.name)),
  });
  abort.signal.addEventListener("abort", () => agent.stop());
  await agent.run();
}

main().catch((e) => {
  log.error(e instanceof Error ? (e.stack ?? e.message) : String(e));
  process.exit(1);
});
