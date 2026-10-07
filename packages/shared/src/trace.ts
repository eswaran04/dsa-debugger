export type PrimName = "int"|"long"|"double"|"float"|"boolean"|"char"|"byte"|"short";
export type Value =
  | { t: PrimName; v: number | boolean | string }        // char -> 1-char string; long -> number (string if > 2^53)
  | { t: "null" } | { t: "void" }
  | { t: "str"; id: number; v: string }
  | { t: "array"; id: number; elem: string; len: number; v: Value[]; truncated?: true }
  | { t: "list" | "set"; id: number; cls: string; len: number; v: Value[]; truncated?: true }
  | { t: "map"; id: number; cls: string; len: number; entries: [Value, Value][]; truncated?: true }
  | { t: "obj"; id: number; cls: string; fields: Var[] }
  | { t: "ref"; id: number; cls: string };                  // depth cut-off
export type Var = { name: string; value: Value };
export type Frame = { fid: number; cls: string; method: string; line: number; locals: Var[] };
export type Step = { line: number; depth: number; event: "line"|"return"|"exception";
  stack: Frame[]; stdout?: string; returnValue?: Value; method?: string };   // stack[0] = top
export type TraceStatus = "ok"|"compile_error"|"runtime_error"|"timeout"|"step_limit";
export type Trace = { status: TraceStatus;
  compileErrors?: { line: number; col: number; message: string }[];
  entry?: { method: string; params: { name: string; type: string }[] };
  steps: Step[]; result?: Value; finalArgs?: Var[];
  exception?: { type: string; message: string; line: number };
  error?: string;                                           // resolution/arg errors, human readable
  stdout: string;                                           // full stdout (capped)
  stats: { steps: number; ms: number; truncated: boolean } };
