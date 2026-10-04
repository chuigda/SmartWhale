import fs from "node:fs";
import path from "node:path";

export const MAX_FILE_BYTES = 64 * 1024;
export const MAX_TOTAL_BYTES = 4 * 1024 * 1024;
export const INDEX_MAX_CHARS = 8000;
const TEXT_EXT = new Set([".md", ".txt", ".json", ".csv", ".yaml", ".yml"]);

const INDEX_SEED = `# index.md

This is your own notebook. Nobody else writes here. It survives restarts and context compaction;
everything you don't write down will eventually be forgotten.

This file is loaded into your system prompt every turn, so keep it short (under ${INDEX_MAX_CHARS} characters):
core facts about yourself, current goals, and pointers to other files. Put details in other files, e.g.
- places.md      coordinates of home, mines, villages, chests
- players.md     who you've met and what you think of them
- lessons.md     mistakes you've made and how to avoid them
- projects/...   plans for bigger builds

Organize it however you like.
`;

export class MemoryError extends Error {}

/** Sandboxed text-file store under data/<bot>/memory/ (docs/DESIGN.md §7.4). */
export class Memory {
  readonly root: string;

  constructor(root: string) {
    this.root = path.resolve(root);
    fs.mkdirSync(this.root, { recursive: true });
    const index = path.join(this.root, "index.md");
    if (!fs.existsSync(index)) fs.writeFileSync(index, INDEX_SEED);
  }

  private resolve(p: string | undefined, forWrite = false): string {
    const rel = (p ?? "").replace(/\\/g, "/").replace(/^\/+/, "");
    const full = path.resolve(this.root, rel);
    if (full !== this.root && !full.startsWith(this.root + path.sep)) throw new MemoryError(`Path escapes the memory directory: ${p}`);
    if (forWrite) {
      if (full === this.root) throw new MemoryError("Path must name a file");
      if (!TEXT_EXT.has(path.extname(full).toLowerCase())) throw new MemoryError(`Only text files are allowed (${[...TEXT_EXT].join(", ")})`);
    }
    return full;
  }

  private rel(full: string): string {
    return path.relative(this.root, full).split(path.sep).join("/") || ".";
  }

  list(p?: string): string {
    const dir = this.resolve(p);
    if (!fs.existsSync(dir)) throw new MemoryError(`No such directory: ${p ?? "."}`);
    if (!fs.statSync(dir).isDirectory()) throw new MemoryError(`Not a directory: ${p}`);
    const lines: string[] = [];
    for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const full = path.join(dir, e.name);
      const st = fs.statSync(full);
      lines.push(e.isDirectory() ? `${this.rel(full)}/` : `${this.rel(full)}  ${st.size} B  ${st.mtime.toISOString().slice(0, 16)}`);
    }
    return lines.length ? lines.join("\n") : "(empty)";
  }

  read(p: string, start?: number, end?: number): string {
    const file = this.resolve(p);
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) throw new MemoryError(`No such file: ${p}`);
    const text = fs.readFileSync(file, "utf8");
    if (start === undefined && end === undefined) return text;
    const lines = text.split("\n");
    const s = Math.max(1, start ?? 1);
    const e = Math.min(lines.length, end ?? lines.length);
    return lines.slice(s - 1, e).map((l, i) => `${s + i}: ${l}`).join("\n");
  }

  write(p: string, content: string): string {
    const file = this.resolve(p, true);
    const bytes = Buffer.byteLength(content);
    if (bytes > MAX_FILE_BYTES) throw new MemoryError(`File too large (${bytes} B > ${MAX_FILE_BYTES} B); split it into several files`);
    const old = fs.existsSync(file) ? fs.statSync(file).size : 0;
    const total = this.totalBytes() - old + bytes;
    if (total > MAX_TOTAL_BYTES) throw new MemoryError(`Memory full (${total} B > ${MAX_TOTAL_BYTES} B); delete or condense old files`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content);
    return `Wrote ${this.rel(file)} (${bytes} B)`;
  }

  edit(p: string, oldStr: string, newStr: string): string {
    if (!oldStr) throw new MemoryError("old_str must not be empty");
    const text = this.read(p);
    const first = text.indexOf(oldStr);
    if (first < 0) throw new MemoryError("old_str not found");
    if (text.indexOf(oldStr, first + 1) >= 0) throw new MemoryError("old_str occurs more than once; include more context");
    this.write(p, text.slice(0, first) + newStr + text.slice(first + oldStr.length));
    return `Edited ${p}`;
  }

  delete(p: string): string {
    const full = this.resolve(p);
    if (full === this.root) throw new MemoryError("Cannot delete the memory root");
    if (!fs.existsSync(full)) throw new MemoryError(`No such file: ${p}`);
    if (fs.statSync(full).isDirectory()) {
      if (fs.readdirSync(full).length) throw new MemoryError("Directory not empty");
      fs.rmdirSync(full);
    } else {
      fs.unlinkSync(full);
    }
    return `Deleted ${this.rel(full)}`;
  }

  search(query: string, limit = 30): string {
    const q = query.toLowerCase();
    if (!q) throw new MemoryError("Empty query");
    const hits: string[] = [];
    for (const file of this.files()) {
      const lines = fs.readFileSync(file, "utf8").split("\n");
      for (let i = 0; i < lines.length && hits.length < limit; i++) {
        if (lines[i]!.toLowerCase().includes(q)) hits.push(`${this.rel(file)}:${i + 1}: ${lines[i]!.slice(0, 200)}`);
      }
      if (hits.length >= limit) break;
    }
    return hits.length ? hits.join("\n") : "No matches";
  }

  /** Directory tree for the system prompt. */
  tree(): string {
    const lines: string[] = [];
    const walk = (dir: string, depth: number) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) {
          lines.push(`${"  ".repeat(depth)}${e.name}/`);
          walk(full, depth + 1);
        } else {
          lines.push(`${"  ".repeat(depth)}${e.name} (${fs.statSync(full).size} B)`);
        }
      }
    };
    walk(this.root, 0);
    return lines.join("\n");
  }

  index(): string {
    const file = path.join(this.root, "index.md");
    const text = fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "";
    if (text.length <= INDEX_MAX_CHARS) return text;
    return `${text.slice(0, INDEX_MAX_CHARS)}\n\n[index.md truncated at ${INDEX_MAX_CHARS} characters; condense it and move details to other files]`;
  }

  private files(): string[] {
    const out: string[] = [];
    const walk = (dir: string) => {
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) walk(full);
        else out.push(full);
      }
    };
    walk(this.root);
    return out.sort();
  }

  private totalBytes(): number {
    return this.files().reduce((n, f) => n + fs.statSync(f).size, 0);
  }
}
