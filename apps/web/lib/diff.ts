import type { Trace, Value, Var } from "@dsa/shared";

const same = (a: Value, b: Value) => JSON.stringify(a) === JSON.stringify(b);

/** Names of locals in frame `fid` whose value changed since that frame's previous recorded step. */
export function changedVars(trace: Trace, idx: number, fid: number): Set<string> {
  const changed = new Set<string>();
  const now = trace.steps[idx]?.stack.find((f) => f.fid === fid);
  if (!now) return changed;
  let before: Var[] | undefined;
  for (let k = idx - 1; k >= 0 && !before; k--) before = trace.steps[k].stack.find((f) => f.fid === fid)?.locals;
  if (!before) return changed;
  const prev = new Map(before.map((v) => [v.name, v.value]));
  for (const v of now.locals) {
    const p = prev.get(v.name);
    if (!p || !same(p, v.value)) changed.add(v.name);
  }
  return changed;
}
