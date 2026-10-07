"use client";

import { Bug, Play, Loader2 } from "lucide-react";
import { scanMethods } from "@/lib/scanMethods";
import { samples } from "@/lib/samples";
import { useDebugStore } from "@/store/debugStore";

export function Header() {
  const code = useDebugStore((s) => s.code);
  const sampleId = useDebugStore((s) => s.sampleId);
  const method = useDebugStore((s) => s.method);
  const phase = useDebugStore((s) => s.phase);
  const { loadSample, setMethod, debug, run } = useDebugStore.getState();
  const running = phase === "running";
  const methods = scanMethods(code);

  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line bg-panel px-4">
      <div className="flex items-center gap-2 pr-3">
        <div className="grid h-8 w-8 place-items-center rounded-lg bg-accent/15 text-accent">
          <Bug size={18} />
        </div>
        <div className="leading-tight">
          <div className="text-sm font-semibold tracking-tight">DSA Debugger</div>
          <div className="text-[11px] text-muted">Java · step through your solution</div>
        </div>
      </div>
      <label className="field">
        <span>Sample</span>
        <select data-testid="sample-select" value={sampleId} onChange={(e) => loadSample(e.target.value)}>
          {samples.map((s) => (
            <option key={s.id} value={s.id}>
              {s.title}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Method</span>
        <select data-testid="method-select" value={method ?? ""} onChange={(e) => setMethod(e.target.value || null)}>
          <option value="">Auto (match args)</option>
          {methods.map((m) => (
            <option key={m.name + m.params.length} value={m.name}>
              {m.name}({m.params.map((p) => `${p.type} ${p.name}`).join(", ")})
            </option>
          ))}
        </select>
      </label>
      <div className="ml-auto flex items-center gap-2">
        <button data-testid="run-btn" className="btn" disabled={running} onClick={run} title="Run without recording">
          <Play size={15} /> Run
        </button>
        <button data-testid="debug-btn" className="btn btn-primary" disabled={running} onClick={debug} title="Debug (Ctrl/⌘+Enter)">
          {running ? <Loader2 size={15} className="animate-spin" /> : <Bug size={15} />} Debug
        </button>
      </div>
    </header>
  );
}
