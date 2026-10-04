import fs from "node:fs";
import path from "node:path";

/** Append-only JSONL log of everything the agent does (docs/DESIGN.md §7.7). */
export class Transcript {
  readonly file: string;
  private readonly fd: number;

  constructor(dir: string, session: string) {
    fs.mkdirSync(dir, { recursive: true });
    this.file = path.join(dir, `transcript-${session}.jsonl`);
    this.fd = fs.openSync(this.file, "a");
  }

  write(type: string, data: Record<string, unknown>): void {
    fs.writeSync(this.fd, `${JSON.stringify({ t: new Date().toISOString(), type, ...data })}\n`);
  }
}
