import { z } from "zod";
import { MemoryError, type Memory } from "./memory.ts";
import { tool, ToolError, type Tool } from "./tools.ts";

const Path = z.string().describe('Path relative to the memory root, e.g. "places.md" or "projects/house.md"');

/** Memory tools (docs/DESIGN.md §7.4). */
export function memoryTools(memory: Memory): Tool[] {
  const wrap = <T>(f: () => T): T => {
    try {
      return f();
    } catch (e) {
      if (e instanceof MemoryError) throw new ToolError(e.message);
      throw e;
    }
  };
  return [
    tool({
      name: "memory_list",
      description: "List a directory of your memory (size and modification time).",
      schema: z.object({ path: Path.optional() }),
      run: async ({ path }) => wrap(() => memory.list(path)),
    }),
    tool({
      name: "memory_read",
      description: "Read a memory file, optionally only some lines (1-based, inclusive; numbered output).",
      schema: z.object({ path: Path, start_line: z.number().int().min(1).optional(), end_line: z.number().int().min(1).optional() }),
      run: async ({ path, start_line, end_line }) => wrap(() => memory.read(path, start_line, end_line)),
    }),
    tool({
      name: "memory_write",
      description: "Create or overwrite a memory file (.md/.txt/.json…). Directories are created as needed.",
      schema: z.object({ path: Path, content: z.string() }),
      run: async ({ path, content }) => wrap(() => memory.write(path, content)),
    }),
    tool({
      name: "memory_edit",
      description: "Replace one exact, unique occurrence of old_str with new_str in a memory file.",
      schema: z.object({ path: Path, old_str: z.string(), new_str: z.string() }),
      run: async ({ path, old_str, new_str }) => wrap(() => memory.edit(path, old_str, new_str)),
    }),
    tool({
      name: "memory_delete",
      description: "Delete a memory file or an empty directory.",
      schema: z.object({ path: Path }),
      run: async ({ path }) => wrap(() => memory.delete(path)),
    }),
    tool({
      name: "memory_search",
      description: "Case-insensitive full-text search over all memory files.",
      schema: z.object({ query: z.string().min(1) }),
      run: async ({ query }) => wrap(() => memory.search(query)),
    }),
  ];
}
