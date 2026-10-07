"use client";

import { CornerDownLeft } from "lucide-react";
import { changedVars } from "@/lib/diff";
import { formatValue } from "@/lib/format";
import { currentStep, selectedFrame } from "@/lib/selectors";
import { useDebugStore } from "@/store/debugStore";
import { ValueView } from "./ValueView";

export function VariablesPanel() {
  const trace = useDebugStore((s) => s.trace);
  const idx = useDebugStore((s) => s.idx);
  const selectedFid = useDebugStore((s) => s.selectedFid);
  const step = currentStep(trace, idx);
  const frame = selectedFrame(step, selectedFid);
  const changed = trace && frame ? changedVars(trace, idx, frame.fid) : new Set<string>();

  return (
    <div className="flex h-full flex-col">
      <div className="panel-title">
        Variables {frame && <span className="normal-case text-muted">· {frame.method}()</span>}
      </div>
      <div className="flex-1 overflow-auto py-1">
        {step?.event === "return" && step.returnValue && (
          <div className="mx-2 mb-1 flex items-center gap-2 rounded bg-violet-500/10 px-2 py-1 font-mono text-[13px] text-violet-200">
            <CornerDownLeft size={13} /> {step.method}() returned {formatValue(step.returnValue)}
          </div>
        )}
        {!frame && <div className="p-3 text-sm text-muted">Press Debug to record a trace.</div>}
        {frame?.locals.length === 0 && <div className="p-3 text-sm text-muted">No locals yet.</div>}
        {frame?.locals.map((v) => (
          <ValueView key={`${frame.fid}-${v.name}-${idx}`} name={v.name} value={v.value} changed={changed.has(v.name)} />
        ))}
      </div>
    </div>
  );
}
