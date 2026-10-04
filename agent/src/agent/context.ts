import type { Message } from "../llm/client.ts";

/** Tool results flagged bulky are replaced after this many rounds (docs/DESIGN.md §7.5). */
export const AGE_AFTER_ROUNDS = 10;
/** Aging rewrites history (breaking the provider's prefix cache), so it is done in batches. */
export const AGE_BATCH = 5;
export const KEEP_MESSAGES = 20;
export const STORY_PREFIX = "[Story so far]\n";
const AGED = "[old observation removed to save context; observe again if needed]";

interface Entry {
  message: Message;
  round: number;
  bulky: boolean;
}

/** Rough token estimate: ~4 ASCII characters per token, 1 token per other (e.g. CJK) character. */
export function estimateTokens(text: string): number {
  let ascii = 0;
  let other = 0;
  for (let i = 0; i < text.length; i++) {
    if (text.charCodeAt(i) < 128) ascii++;
    else other++;
  }
  return Math.ceil(ascii / 4) + other;
}

export function messageTokens(m: Message): number {
  let n = 4 + estimateTokens(m.content ?? "");
  if (m.role === "assistant") {
    n += estimateTokens(m.reasoning_content ?? "");
    for (const c of m.tool_calls ?? []) n += 8 + estimateTokens(c.function.name + c.function.arguments);
  }
  return n;
}

/** The conversation after the system prompt, with token accounting anchored to the provider's usage numbers. */
export class History {
  private entries: Entry[] = [];
  round = 0;
  /** prompt_tokens reported for the last request, and how many entries that request contained. */
  private anchor: { tokens: number; count: number } | null = null;

  get messages(): Message[] {
    return this.entries.map((e) => e.message);
  }

  get length(): number {
    return this.entries.length;
  }

  push(message: Message, bulky = false): void {
    this.entries.push({ message, round: this.round, bulky });
  }

  /** Records the provider's prompt token count for a request made with the first `count` messages. */
  anchorUsage(promptTokens: number, count: number): void {
    this.anchor = { tokens: promptTokens, count };
  }

  /** Estimated prompt tokens for the next request; `fixed` covers the system prompt and tool specs. */
  estimate(fixed: number): number {
    if (this.anchor && this.anchor.count <= this.entries.length) {
      let n = this.anchor.tokens;
      for (const e of this.entries.slice(this.anchor.count)) n += messageTokens(e.message);
      return n;
    }
    return fixed + this.entries.reduce((n, e) => n + messageTokens(e.message), 0);
  }

  /** Replaces old bulky tool results once enough of them have piled up. Returns how many were aged. */
  age(): number {
    const old = this.entries.filter((e) => e.bulky && this.round - e.round >= AGE_AFTER_ROUNDS);
    if (old.length < AGE_BATCH) return 0;
    for (const e of old) {
      e.message = { ...e.message, content: AGED } as Message;
      e.bulky = false;
    }
    this.anchor = null;
    return old.length;
  }

  /** Index from which the last ~`keep` messages are kept, never starting at a tool result. */
  splitPoint(keep = KEEP_MESSAGES): number {
    let i = Math.max(0, this.entries.length - keep);
    while (i > 0 && this.entries[i]?.message.role === "tool") i--;
    return i;
  }

  /** Messages that `compact` would summarize (including a previous story, which is always first). */
  toSummarize(keep = KEEP_MESSAGES): Message[] {
    return this.messages.slice(0, this.splitPoint(keep));
  }

  /** Replaces everything before the split point with a [Story so far] message. */
  compact(summary: string, keep = KEEP_MESSAGES): void {
    const kept = this.entries.slice(this.splitPoint(keep));
    this.entries = [{ message: { role: "user", content: STORY_PREFIX + summary.trim() }, round: this.round, bulky: false }, ...kept];
    this.anchor = null;
  }

  /** Last resort when even a compacted history exceeds the hard limit: drop the oldest messages (keeping the story). */
  dropOldest(maxTokens: number, fixed: number): number {
    let dropped = 0;
    const hasStory = this.entries[0]?.message.content?.startsWith(STORY_PREFIX) ?? false;
    const first = hasStory ? 1 : 0;
    this.anchor = null;
    while (this.entries.length > first + 1 && this.estimate(fixed) > maxTokens) {
      this.entries.splice(first, 1);
      dropped++;
      while (this.entries.length > first + 1 && this.entries[first]?.message.role === "tool") {
        this.entries.splice(first, 1);
        dropped++;
      }
    }
    return dropped;
  }
}

export const SUMMARY_PROMPT = `You are summarizing the life log of a Minecraft player (you) so that it can continue with a short context.
Write a concise narrative in English (keep chat quotes in their original language) covering:
- what happened, in order, focusing on what still matters;
- current goals and plans, and progress on them;
- important places with coordinates, items you own and where they are stored;
- players you met, what they said or asked, promises made, and how you feel about them;
- dangers, mistakes and lessons learned.
Drop routine details (individual observations, tool errors that were resolved). At most ~1500 words.
If the log starts with an earlier [Story so far], merge it in.`;

/** Plain-text rendering of messages for the summarizer (no tool-call protocol, reasoning omitted). */
export function renderForSummary(messages: Message[]): string {
  const lines: string[] = [];
  for (const m of messages) {
    if (m.role === "user") lines.push(m.content);
    else if (m.role === "assistant") {
      if (m.content) lines.push(`(me) ${m.content}`);
      for (const c of m.tool_calls ?? []) lines.push(`(me) -> ${c.function.name} ${c.function.arguments}`);
    } else if (m.role === "tool") lines.push(`   <- ${m.content.length > 600 ? `${m.content.slice(0, 600)}…` : m.content}`);
  }
  return lines.join("\n");
}
