"use client";

import { motion } from "framer-motion";
import { useState } from "react";
import type { Frame, Trace, Value } from "@dsa/shared";
import { formatValue } from "@/lib/format";
import { pointerVars, type Pointer } from "@/lib/pointers";
import { currentStep, selectedFrame } from "@/lib/selectors";
import { useDebugStore } from "@/store/debugStore";

type Seq = Extract<Value, { t: "array" | "list" }>;
const isSeq = (v: Value): v is Seq => v.t === "array" || v.t === "list";
const isLeaf = (v: Value) => !("v" in v) || !Array.isArray((v as { v: unknown }).v);
const cell = (v: Value) => (v.t === "str" ? v.v : v.t === "char" ? String(v.v) : formatValue(v));

/** The same local in the same frame at the previous step, for change pulses. */
function previous(trace: Trace, idx: number, fid: number, name: string): Value | undefined {
  for (let k = idx - 1; k >= 0; k--) {
    const f = trace.steps[k].stack.find((x) => x.fid === fid);
    if (f) return f.locals.find((l) => l.name === name)?.value;
  }
  return undefined;
}

const PAIRS: [string, string][] = [["r", "c"], ["row", "col"], ["i", "j"], ["x", "y"]];

function intLocal(frame: Frame, name: string): number | null {
  const v = frame.locals.find((l) => l.name === name)?.value;
  return v && v.t === "int" && typeof v.v === "number" ? v.v : null;
}

function Badges({ ptrs, out }: { ptrs: Pointer[]; out?: boolean }) {
  return (
    <div className="flex min-h-5 flex-col items-center gap-0.5 pt-1">
      {ptrs.map((p) => (
        <motion.span
          layoutId={`ptr-${p.name}`}
          key={p.name}
          className={`rounded px-1 font-mono text-[10px] leading-4 ${out ? "bg-red-500/20 text-red-300" : "bg-accent/20 text-accent"}`}
        >
          {p.name}
        </motion.span>
      ))}
    </div>
  );
}

function Row({ name, value, prev, frame, hidden }: { name: string; value: Seq; prev?: Value; frame: Frame; hidden: Set<string> }) {
  const ptrs = pointerVars(frame, value.len).filter((p) => !hidden.has(p.name));
  const at = (i: number) => ptrs.filter((p) => p.index === i);
  const before = prev && isSeq(prev) ? prev.v : [];
  return (
    <div data-testid={`array-${name}`} className="mb-4">
      <div className="mb-1 font-mono text-xs text-sky-300">
        {name} <span className="text-muted">len {value.len}</span>
      </div>
      <div className="flex items-start overflow-x-auto pb-1">
        {at(-1).length > 0 && (
          <div className="flex w-10 flex-col items-center">
            <div className="h-9 w-9 rounded border border-dashed border-red-500/40" />
            <Badges ptrs={at(-1)} out />
          </div>
        )}
        {value.v.map((v, i) => {
          const changed = before[i] !== undefined && JSON.stringify(before[i]) !== JSON.stringify(v);
          const pointed = at(i).length > 0;
          return (
            <div key={i} className="flex w-10 flex-col items-center">
              <motion.div
                key={cell(v)}
                initial={changed ? { scale: 1.25, backgroundColor: "rgba(250,204,21,0.35)" } : false}
                animate={{ scale: 1, backgroundColor: pointed ? "rgba(96,165,250,0.12)" : "rgba(255,255,255,0.03)" }}
                transition={{ duration: 0.45 }}
                className={`grid h-9 w-9 place-items-center rounded border font-mono text-[13px] ${
                  pointed ? "border-accent/60" : "border-line"
                }`}
                title={formatValue(v)}
              >
                <span className="truncate px-0.5">{cell(v)}</span>
              </motion.div>
              <span className="text-[10px] text-muted">{i}</span>
              <Badges ptrs={at(i)} />
            </div>
          );
        })}
        {value.truncated && <div className="px-2 pt-2 text-muted">…</div>}
        {at(value.len).length > 0 && (
          <div className="flex w-10 flex-col items-center">
            <div className="h-9 w-9 rounded border border-dashed border-red-500/40" />
            <span className="text-[10px] text-red-400">{value.len}</span>
            <Badges ptrs={at(value.len)} out />
          </div>
        )}
      </div>
    </div>
  );
}

function Grid({ name, value, frame }: { name: string; value: Seq; frame: Frame }) {
  let hit: [number, number] | null = null;
  for (const [a, b] of PAIRS) {
    const r = intLocal(frame, a);
    const c = intLocal(frame, b);
    if (r != null && c != null && r >= 0 && r < value.len) {
      hit = [r, c];
      break;
    }
  }
  return (
    <div data-testid={`array-${name}`} className="mb-4">
      <div className="mb-1 font-mono text-xs text-sky-300">
        {name} <span className="text-muted">{value.len} rows</span>
      </div>
      <div className="inline-grid gap-0.5">
        {value.v.map((row, r) => (
          <div key={r} className="flex gap-0.5">
            {isSeq(row) &&
              row.v.map((v, c) => (
                <div
                  key={c}
                  className={`grid h-7 w-7 place-items-center rounded border font-mono text-xs ${
                    hit && hit[0] === r && hit[1] === c ? "border-accent bg-accent/25" : "border-line bg-white/[0.03]"
                  }`}
                >
                  {cell(v)}
                </div>
              ))}
          </div>
        ))}
      </div>
    </div>
  );
}

export function ArrayViz() {
  const trace = useDebugStore((s) => s.trace);
  const idx = useDebugStore((s) => s.idx);
  const selectedFid = useDebugStore((s) => s.selectedFid);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const step = currentStep(trace, idx);
  const frame = selectedFrame(step, selectedFid);
  const seqs = frame ? frame.locals.filter((l) => isSeq(l.value)) : [];
  const ints = frame ? frame.locals.filter((l) => l.value.t === "int").map((l) => l.name) : [];

  return (
    <div className="flex h-full flex-col">
      <div className="panel-title flex items-center gap-2">
        Arrays
        <div className="ml-auto flex flex-wrap gap-1 normal-case">
          {ints.map((n) => (
            <button
              key={n}
              onClick={() => {
                const next = new Set(hidden);
                if (next.has(n)) next.delete(n);
                else next.add(n);
                setHidden(next);
              }}
              className={`rounded px-1.5 font-mono text-[10px] ${hidden.has(n) ? "bg-white/5 text-muted line-through" : "bg-accent/15 text-accent"}`}
              title="Toggle as pointer"
            >
              {n}
            </button>
          ))}
        </div>
      </div>
      <div className="flex-1 overflow-auto p-3">
        {seqs.length === 0 && <div className="text-sm text-muted">Arrays and lists in the selected frame show here.</div>}
        {frame &&
          trace &&
          seqs.map(({ name, value }) => {
            const v = value as Seq;
            const twoD = v.v.length > 0 && v.v.every((x) => isSeq(x) && x.v.every(isLeaf));
            return twoD ? (
              <Grid key={name} name={name} value={v} frame={frame} />
            ) : (
              <Row key={name} name={name} value={v} prev={previous(trace, idx, frame.fid, name)} frame={frame} hidden={hidden} />
            );
          })}
      </div>
    </div>
  );
}
