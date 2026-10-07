export type ScannedMethod = { name: string; params: { type: string; name: string }[] };

/** Splits a parameter list on commas outside generic angle brackets. */
function splitParams(list: string): string[] {
  const out: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < list.length; i++) {
    const c = list[i];
    if (c === "<") depth++;
    else if (c === ">") depth--;
    else if (c === "," && depth === 0) {
      out.push(list.slice(start, i));
      start = i + 1;
    }
  }
  out.push(list.slice(start));
  return out.map((s) => s.trim()).filter(Boolean);
}

/** Body of `class Solution { ... }` with nested braces matched, or "" if absent. */
function solutionBody(code: string): string {
  const m = /\bclass\s+Solution\b[^{]*\{/.exec(code);
  if (!m) return "";
  let depth = 1;
  const start = m.index + m[0].length;
  for (let i = start; i < code.length; i++) {
    if (code[i] === "{") depth++;
    else if (code[i] === "}" && --depth === 0) return code.slice(start, i);
  }
  return code.slice(start);
}

/** Top-level (depth 0) text of the class body, so nested class methods are skipped. */
function topLevel(body: string): string {
  let depth = 0;
  let out = "";
  for (const c of body) {
    if (c === "{") {
      if (depth === 0) out += "{";
      depth++;
    } else if (c === "}") {
      depth--;
      if (depth === 0) out += "}";
    } else if (depth === 0) out += c;
  }
  return out;
}

const METHOD = /((?:(?:public|protected|private|static|final|synchronized)\s+)*)([\w<>\[\],.\s?]+?)\s+(\w+)\s*\(([^)]*)\)\s*(?:throws[\w\s,.]+)?\{/g;

/** Light regex scan of non-private methods declared directly in class Solution, in source order. */
export function scanMethods(code: string): ScannedMethod[] {
  const src = topLevel(solutionBody(code.replace(/\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "")));
  const out: ScannedMethod[] = [];
  for (const m of src.matchAll(METHOD)) {
    const mods = m[1] ?? "";
    const name = m[3];
    if (/\bprivate\b/.test(mods) || name === "Solution" || /^(if|for|while|switch|catch|return|new)$/.test(name)) continue;
    const params = splitParams(m[4]).map((p) => {
      const sp = p.replace(/\bfinal\s+/, "").trim();
      const at = sp.lastIndexOf(" ");
      return { type: sp.slice(0, at).trim(), name: sp.slice(at + 1).trim() };
    });
    out.push({ name, params });
  }
  return out;
}
