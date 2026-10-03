import fs from "node:fs";
import path from "node:path";
import type { Config } from "../config/schema.ts";
import { resolveFromRoot } from "../util/paths.ts";
import { logger } from "../util/log.ts";

const log = logger("instance");

export interface InstanceLayout {
  /** run/<bot>/ ? per-bot scratch directory. */
  runDir: string;
  /** run/<bot>/game ? Minecraft game directory. */
  gameDir: string;
}

/** Keys forced into options.txt for an unattended client. */
const BOT_OPTIONS: Record<string, string | number | boolean> = {
  pauseOnLostFocus: false,
  onboardAccessibility: false,
  skipMultiplayerWarning: true,
  joinedFirstServer: true,
  tutorialStep: "none",
  narrator: 0,
  renderDistance: 8,
  maxFps: 10,
  enableVsync: false,
  soundCategory_master: 0.0,
  fullscreen: false,
};

export function prepareInstance(config: Config): InstanceLayout {
  const runDir = resolveFromRoot(path.join("run", config.name));
  const layout: InstanceLayout = { runDir, gameDir: path.join(runDir, "game") };
  fs.mkdirSync(layout.gameDir, { recursive: true });
  syncMods(config, layout.gameDir);
  copyInstanceDirs(config, layout.gameDir);
  writeOptions(config, layout.gameDir);
  disableEarlyWindow(layout.gameDir);
  return layout;
}

/** FML's early loading window is created before any mod can hide it. */
function disableEarlyWindow(gameDir: string): void {
  const file = path.join(gameDir, "config", "fml.toml");
  fs.mkdirSync(path.dirname(file), { recursive: true });
  let text = fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
  if (/^\s*earlyWindowControl\s*=/m.test(text)) {
    text = text.replace(/^(\s*earlyWindowControl\s*=\s*)\S+/m, "$1false");
  } else {
    text += (text && !text.endsWith("\n") ? "\n" : "") + "earlyWindowControl = false\n";
  }
  fs.writeFileSync(file, text);
}

function globToRegExp(glob: string): RegExp {
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".");
  return new RegExp(`^${escaped}$`, "i");
}

/** Mirrors source mods (minus excludes) plus the bridge jar and extras; removes stale jars. */
function syncMods(config: Config, gameDir: string): void {
  const modsDir = path.join(gameDir, "mods");
  fs.mkdirSync(modsDir, { recursive: true });
  const exclude = config.minecraft.mods.exclude.map(globToRegExp);
  const wanted = new Map<string, string>();

  for (const name of fs.readdirSync(config.minecraft.mods.source)) {
    if (!name.endsWith(".jar")) continue;
    if (exclude.some((re) => re.test(name))) continue;
    wanted.set(name, path.join(config.minecraft.mods.source, name));
  }
  for (const extra of [config.bridge.jar, ...config.minecraft.mods.extra]) {
    const p = resolveFromRoot(extra);
    if (!fs.existsSync(p)) throw new Error(`Mod jar not found: ${p}`);
    wanted.set(path.basename(p), p);
  }

  for (const name of fs.readdirSync(modsDir)) {
    if (!wanted.has(name)) fs.rmSync(path.join(modsDir, name), { force: true, recursive: true });
  }
  for (const [name, src] of wanted) {
    const dst = path.join(modsDir, name);
    const s = fs.statSync(src);
    const d = fs.existsSync(dst) ? fs.statSync(dst) : null;
    if (!d || d.size !== s.size || d.mtimeMs < s.mtimeMs) fs.copyFileSync(src, dst);
  }
  log.info(`Mods: ${[...wanted.keys()].join(", ")}`);
}

function copyInstanceDirs(config: Config, gameDir: string): void {
  for (const dir of config.minecraft.copyFromInstance) {
    const src = path.join(config.minecraft.instance, dir);
    if (!fs.existsSync(src)) continue;
    fs.cpSync(src, path.join(gameDir, dir), { recursive: true, force: false, errorOnExist: false });
  }
}

function writeOptions(config: Config, gameDir: string): void {
  const file = path.join(gameDir, "options.txt");
  const options = new Map<string, string>();
  const base = fs.existsSync(file) ? file : path.join(config.minecraft.instance, "options.txt");
  if (fs.existsSync(base)) {
    for (const line of fs.readFileSync(base, "utf8").split(/\r?\n/)) {
      const i = line.indexOf(":");
      if (i > 0) options.set(line.slice(0, i), line.slice(i + 1));
    }
  }
  for (const [k, v] of Object.entries({ ...BOT_OPTIONS, ...config.minecraft.options })) {
    options.set(k, String(v));
  }
  fs.writeFileSync(file, [...options].map(([k, v]) => `${k}:${v}`).join("\n") + "\n");
}
