"use client";

import { useDebugStore } from "@/store/debugStore";

export function ConsolePanel() {
  const trace = useDebugStore((s) => s.trace);
  const idx = useDebugStore((s) => s.idx);
  const runOnly = useDebugStore((s) => s.runOnly);
  let text = "";
  if (trace) {
    if (runOnly || trace.steps.length === 0) text = trace.stdout;
    else for (let k = 0; k <= Math.min(idx, trace.steps.length - 1); k++) text += trace.steps[k].stdout ?? "";
  }
  return (
    <div className="flex h-full flex-col">
      <div className="panel-title">Console</div>
      <pre className="flex-1 overflow-auto whitespace-pre-wrap p-3 font-mono text-[13px] text-fg/90">
        {text || <span className="text-muted">System.out output appears here.</span>}
      </pre>
    </div>
  );
}
