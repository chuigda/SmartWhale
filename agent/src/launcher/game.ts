import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import path from "node:path";
import readline from "node:readline";
import type { Config } from "../config/schema.ts";
import type { InstanceLayout } from "./instance.ts";
import type { Session } from "./yggdrasil.ts";
import { resolveFromRoot } from "../util/paths.ts";
import { logger } from "../util/log.ts";

const log = logger("mc");

export interface LaunchOptions {
  config: Config;
  layout: InstanceLayout;
  session: Session;
  prefetched: string;
  bridgePort: number;
  bridgeToken: string;
  logFile: string;
}

export interface GameProcess {
  child: ChildProcess;
  exited: Promise<number | null>;
  kill(): void;
}

export async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const port = (srv.address() as net.AddressInfo).port;
      srv.close(() => resolve(port));
    });
  });
}

/** Lines worth echoing to the console from the (very chatty) game log. */
const INTERESTING = /SmartWhale|ERROR|FATAL|Exception|Crash|Connecting to|Disconnected|authlib-injector\] \[(WARNING|ERROR)/;

type Rule = { action: "allow" | "disallow"; os?: { name?: string; arch?: string }; features?: Record<string, boolean> };
type Arg = string | { rules?: Rule[]; value: string | string[] };
interface Library {
  name: string;
  rules?: Rule[];
  natives?: unknown;
  downloads?: { artifact?: { path: string } };
}
interface VersionJson {
  id: string;
  jar?: string;
  inheritsFrom?: string;
  mainClass: string;
  assetIndex: { id: string };
  libraries: Library[];
  arguments: { jvm: Arg[]; game: Arg[] };
}

const OS_NAME: Record<string, string> = { win32: "windows", darwin: "osx", linux: "linux" };
const FEATURES: Record<string, boolean> = { has_custom_resolution: true };

function osMatches(os: NonNullable<Rule["os"]>): boolean {
  // HMCL writes `name: "universal"` for "any OS" (vanilla omits the name).
  if (os.name && os.name !== "universal" && os.name !== OS_NAME[process.platform]) return false;
  if (os.arch && os.arch !== (process.arch === "ia32" ? "x86" : process.arch)) return false;
  return true;
}

function rulesAllow(rules: Rule[] | undefined): boolean {
  if (!rules?.length) return true;
  let allowed = false;
  for (const rule of rules) {
    let match = !rule.os || osMatches(rule.os);
    if (rule.features) match &&= Object.entries(rule.features).every(([k, v]) => (FEATURES[k] ?? false) === v);
    if (match) allowed = rule.action === "allow";
  }
  return allowed;
}

function collectArgs(args: Arg[]): string[] {
  const out: string[] = [];
  for (const a of args) {
    if (typeof a === "string") out.push(a);
    else if (rulesAllow(a.rules)) out.push(...(Array.isArray(a.value) ? a.value : [a.value]));
  }
  return out;
}

function mavenPath(coords: string): string {
  const [spec = "", ext = "jar"] = coords.split("@");
  const [group = "", artifact = "", version = "", classifier] = spec.split(":");
  return path.join(...group.split("."), artifact, version, `${artifact}-${version}${classifier ? `-${classifier}` : ""}.${ext}`);
}

/** Builds the java command line from a launcher-installed (merged, no `inheritsFrom`) version JSON. */
export function buildCommand(opts: LaunchOptions): string[] {
  const { config, layout, session } = opts;
  const root = config.minecraft.root;
  const versionDir = path.join(root, "versions", config.minecraft.version);
  const version = JSON.parse(fs.readFileSync(path.join(versionDir, `${config.minecraft.version}.json`), "utf8")) as VersionJson;
  if (version.inheritsFrom) throw new Error(`${version.id}: inheritsFrom is not supported; point to a merged version JSON`);

  const libDir = path.join(root, "libraries");
  const jarName = `${version.jar ?? version.id}.jar`;
  const classpath: string[] = [];
  for (const lib of version.libraries) {
    if (lib.natives || !rulesAllow(lib.rules)) continue;
    const p = path.join(libDir, lib.downloads?.artifact?.path ?? mavenPath(lib.name));
    if (!fs.existsSync(p)) log.warn(`Missing library ${p}`);
    if (!classpath.includes(p)) classpath.push(p);
  }
  classpath.push(path.join(versionDir, jarName));

  // HMCL pre-extracts natives here; LWJGL can also extract from the natives jars on the classpath.
  let nativesDir = path.join(versionDir, `natives-windows-${process.arch === "x64" ? "x86_64" : process.arch}`);
  if (process.platform !== "win32" || !fs.existsSync(nativesDir)) {
    nativesDir = path.join(layout.runDir, "natives");
    fs.mkdirSync(nativesDir, { recursive: true });
  }

  const vars: Record<string, string> = {
    natives_directory: nativesDir,
    launcher_name: "SmartWhale",
    launcher_version: "0.1.0",
    classpath: classpath.join(path.delimiter),
    classpath_separator: path.delimiter,
    library_directory: libDir,
    version_name: version.id,
    primary_jar_name: jarName,
    auth_player_name: session.profile.name,
    game_directory: layout.gameDir,
    assets_root: path.join(root, "assets"),
    assets_index_name: version.assetIndex.id,
    auth_uuid: session.profile.id,
    auth_access_token: session.accessToken,
    clientid: "0",
    auth_xuid: "0",
    user_type: "msa",
    version_type: "SmartWhale",
    resolution_width: "320",
    resolution_height: "240",
  };
  const substitute = (s: string) => s.replace(/\$\{(\w+)\}/g, (m, k: string) => vars[k] ?? m);

  return [
    `-javaagent:${resolveFromRoot(config.tools.authlibInjector)}=${session.apiRoot}`,
    "-Dauthlibinjector.side=client",
    `-Dauthlibinjector.yggdrasil.prefetched=${opts.prefetched}`,
    `-Dsmartwhale.bridge.port=${opts.bridgePort}`,
    `-Dsmartwhale.bridge.token=${opts.bridgeToken}`,
    `-Dsmartwhale.bridge.autoconnect=${config.minecraft.server}`,
    "-Dsmartwhale.bridge.headless=true",
    `-Dsmartwhale.bridge.perception=${config.bridge.perception}`,
    // We decode the piped output as UTF-8; the JVM default on Chinese Windows is GBK.
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "-Dlog4j2.formatMsgNoLookups=true",
    "-Dfml.ignoreInvalidMinecraftCertificates=true",
    "-Dfml.ignorePatchDiscrepancies=true",
    `-Dminecraft.client.jar=${path.join(versionDir, jarName)}`,
    ...config.minecraft.jvmArgs,
    ...collectArgs(version.arguments.jvm).map(substitute),
    version.mainClass,
    ...collectArgs(version.arguments.game).map(substitute),
  ];
}

export function launch(opts: LaunchOptions): GameProcess {
  const { config, layout, session } = opts;
  const args = buildCommand(opts);

  fs.mkdirSync(path.dirname(opts.logFile), { recursive: true });
  const logStream = fs.createWriteStream(opts.logFile, { flags: "a" });
  logStream.write(`\n===== launch ${new Date().toISOString()} =====\n`);

  log.info(`Launching ${config.minecraft.version} as ${session.profile.name} (bridge port ${opts.bridgePort})`);
  const child = spawn(config.minecraft.java, args, { cwd: layout.gameDir, stdio: ["ignore", "pipe", "pipe"], windowsHide: true });

  for (const stream of [child.stdout!, child.stderr!]) {
    const rl = readline.createInterface({ input: stream });
    rl.on("line", (line) => {
      logStream.write(redact(line, session.accessToken) + "\n");
      if (INTERESTING.test(line)) log.info(redact(line, session.accessToken).slice(0, 400));
    });
  }

  const exited = new Promise<number | null>((resolve) => {
    child.on("exit", (code) => {
      log.warn(`Game process exited with code ${code}`);
      logStream.end();
      resolve(code);
    });
  });

  return {
    child,
    exited,
    kill() {
      if (child.pid === undefined || child.exitCode !== null) return;
      if (process.platform === "win32") {
        spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore" });
      } else {
        child.kill("SIGTERM");
      }
    },
  };
}

function redact(line: string, token: string): string {
  return token ? line.replaceAll(token, "<token>") : line;
}
