type Level = "debug" | "info" | "warn" | "error";

const order: Record<Level, number> = { debug: 10, info: 20, warn: 30, error: 40 };
const threshold = order[(process.env.SMARTWHALE_LOG_LEVEL as Level | undefined) ?? "info"] ?? order.info;

function emit(level: Level, scope: string, msg: string, extra?: unknown): void {
  if (order[level] < threshold) return;
  const time = new Date().toISOString().slice(11, 23);
  const line = `${time} ${level.toUpperCase().padEnd(5)} [${scope}] ${msg}`;
  const stream = level === "error" || level === "warn" ? process.stderr : process.stdout;
  stream.write(extra === undefined ? `${line}\n` : `${line} ${typeof extra === "string" ? extra : JSON.stringify(extra)}\n`);
}

export interface Logger {
  debug(msg: string, extra?: unknown): void;
  info(msg: string, extra?: unknown): void;
  warn(msg: string, extra?: unknown): void;
  error(msg: string, extra?: unknown): void;
}

export function logger(scope: string): Logger {
  return {
    debug: (m, e) => emit("debug", scope, m, e),
    info: (m, e) => emit("info", scope, m, e),
    warn: (m, e) => emit("warn", scope, m, e),
    error: (m, e) => emit("error", scope, m, e),
  };
}
