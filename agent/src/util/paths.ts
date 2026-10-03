import path from "node:path";

/** Repository root (…/SmartWhale). Relative paths in config files resolve against it. */
export const PROJECT_ROOT = path.resolve(import.meta.dirname, "..", "..", "..");

export function resolveFromRoot(p: string): string {
  return path.isAbsolute(p) ? p : path.resolve(PROJECT_ROOT, p);
}
