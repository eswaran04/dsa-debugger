import type { ArgInput } from "@dsa/shared";

export type ParseResult = { ok: true; args: ArgInput[] } | { ok: false; error: string };

/** Splits on commas/newlines that are outside brackets, braces and double-quoted strings. */
function splitTopLevel(text: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let inStr = false;
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      if (c === "\\") i++;
      else if (c === '"') inStr = false;
    } else if (c === '"') inStr = true;
    else if (c === "[" || c === "{") depth++;
    else if (c === "]" || c === "}") depth--;
    else if ((c === "," || c === "\n") && depth === 0) {
      parts.push(text.slice(start, i));
      start = i + 1;
    }
  }
  parts.push(text.slice(start));
  return parts.map((p) => p.trim()).filter((p) => p.length > 0);
}

/** Parses `nums = [7, 4, 1], target = 9` into named raw JSON values. */
export function parseNamedArgs(text: string): ParseResult {
  const args: ArgInput[] = [];
  const seen = new Set<string>();
  for (const part of splitTopLevel(text)) {
    const eq = part.indexOf("=");
    if (eq < 0) return { ok: false, error: `Expected name = value, got "${part}"` };
    const name = part.slice(0, eq).trim();
    const raw = part.slice(eq + 1).trim();
    if (!/^[A-Za-z_$][\w$]*$/.test(name)) return { ok: false, error: `Invalid argument name "${name}"` };
    if (seen.has(name)) return { ok: false, error: `Duplicate argument "${name}"` };
    try {
      JSON.parse(raw);
    } catch {
      return { ok: false, error: `Invalid value for ${name}` };
    }
    seen.add(name);
    args.push({ name, raw });
  }
  return { ok: true, args };
}
