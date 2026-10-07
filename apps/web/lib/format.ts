import type { Trace, Value } from "@dsa/shared";

const more = (truncated?: true) => (truncated ? ", …" : "");

/** Compact one-line rendering of a traced value, Java-ish. */
export function formatValue(v: Value): string {
  switch (v.t) {
    case "null":
      return "null";
    case "void":
      return "void";
    case "char":
      return `'${v.v}'`;
    case "str":
      return JSON.stringify(v.v);
    case "array":
    case "list":
    case "set":
      return `[${v.v.map(formatValue).join(", ")}${more(v.truncated)}]`;
    case "map":
      return `{${v.entries.map(([k, val]) => `${formatValue(k)}=${formatValue(val)}`).join(", ")}${more(v.truncated)}}`;
    case "obj":
      return `${shortName(v.cls)}{${v.fields.map((f) => `${f.name}=${formatValue(f.value)}`).join(", ")}}`;
    case "ref":
      return `${shortName(v.cls)}@${v.id}`;
    default:
      return String(v.v);
  }
}

export function shortName(cls: string): string {
  return cls.slice(cls.lastIndexOf(".") + 1);
}

/** Lines for the Result panel: the return value, or the final argument values for a void method. */
export function resultLines(trace: Trace): string[] {
  if (!trace.result) return [];
  if (trace.result.t === "void") return (trace.finalArgs ?? []).map((a) => `${a.name} = ${formatValue(a.value)}`);
  return [formatValue(trace.result)];
}

export const isRef = (v: Value): v is Extract<Value, { id: number }> => "id" in v;
