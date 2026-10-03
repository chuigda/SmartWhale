import crypto from "node:crypto";
import path from "node:path";
import { parseArgs } from "node:util";
import { loadConfig, readPassword } from "./config/load.ts";
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
  --control-port <n>   local HTTP endpoint for manual RPC calls (see src/bridge/control.ts)`;

async function main(): Promise<void> {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      attach: { type: "string" },
      token: { type: "string" },
      "control-port": { type: "string" },
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
}

main().catch((e) => {
  log.error(e instanceof Error ? (e.stack ?? e.message) : String(e));
  process.exit(1);
});
