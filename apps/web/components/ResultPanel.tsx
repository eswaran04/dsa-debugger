"use client";

import { Check, X } from "lucide-react";
import { useState } from "react";
import { resultLines } from "@/lib/format";
import { useDebugStore } from "@/store/debugStore";

const norm = (s: string) => s.replace(/\s+/g, "");

export function ResultPanel() {
  const trace = useDebugStore((s) => s.trace);
  const phase = useDebugStore((s) => s.phase);
  const [expected, setExpected] = useState("");
  const lines = trace && phase === "ready" ? resultLines(trace) : [];
  const isVoid = trace?.result?.t === "void";
  const shown = lines.join("\n");

  return (
    <div className="flex h-full flex-col">
      <div className="panel-title">
        Result {trace && <span className="normal-case text-muted">· {trace.stats.ms} ms</span>}
      </div>
      <div className="flex-1 space-y-2 overflow-auto p-3">
        {lines.length === 0 ? (
          <div className="text-sm text-muted">{phase === "running" ? "Running…" : "Run or debug to see the output."}</div>
        ) : (
          <>
            {isVoid && <div className="text-xs text-muted">Method returned void — final argument values:</div>}
            <pre data-testid="result-value" className="whitespace-pre-wrap font-mono text-[13px] text-emerald-300">
              {shown}
            </pre>
          </>
        )}
        <div className="flex items-center gap-2 pt-1">
          <input
            value={expected}
            onChange={(e) => setExpected(e.target.value)}
            placeholder="Expected output (optional)"
            className="flex-1 rounded border border-line bg-black/20 px-2 py-1 font-mono text-xs outline-none focus:border-accent/60"
          />
          {expected && lines.length > 0 &&
            (norm(expected) === norm(shown) ? (
              <span className="flex items-center gap-1 text-xs text-emerald-400"><Check size={14} /> match</span>
            ) : (
              <span className="flex items-center gap-1 text-xs text-red-400"><X size={14} /> differs</span>
            ))}
        </div>
      </div>
    </div>
  );
}
