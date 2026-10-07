"use client";

import { currentStep, selectedFrame } from "@/lib/selectors";
import { useDebugStore } from "@/store/debugStore";

export function CallStackPanel() {
  const trace = useDebugStore((s) => s.trace);
  const idx = useDebugStore((s) => s.idx);
  const selectedFid = useDebugStore((s) => s.selectedFid);
  const selectFrame = useDebugStore((s) => s.selectFrame);
  const step = currentStep(trace, idx);
  const active = selectedFrame(step, selectedFid);

  return (
    <div className="flex h-full flex-col">
      <div className="panel-title">
        Call stack {step && <span className="normal-case text-muted">· depth {step.depth}</span>}
      </div>
      <div className="flex-1 overflow-auto py-1">
        {step?.stack.map((f, i) => (
          <button
            key={f.fid}
            data-testid={`frame-${f.fid}`}
            onClick={() => selectFrame(i === 0 ? null : f.fid)}
            className={`flex w-full items-center gap-2 px-3 py-1 text-left font-mono text-[13px] hover:bg-white/5 ${
              active?.fid === f.fid ? "bg-accent/10 text-accent" : "text-fg/90"
            }`}
          >
            <span className={`h-1.5 w-1.5 rounded-full ${i === 0 ? "bg-yellow-300" : "bg-emerald-400/60"}`} />
            {f.method}()
            <span className="ml-auto text-muted">:{f.line}</span>
          </button>
        ))}
        {step && step.depth > step.stack.length && (
          <div className="px-3 py-1 text-xs text-muted">… {step.depth - step.stack.length} deeper frames hidden</div>
        )}
      </div>
    </div>
  );
}
