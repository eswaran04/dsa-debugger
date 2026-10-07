"use client";

import { AlertTriangle } from "lucide-react";
import { useDebugStore } from "@/store/debugStore";

export function StatusBanner() {
  const trace = useDebugStore((s) => s.trace);
  const phase = useDebugStore((s) => s.phase);
  const error = useDebugStore((s) => s.error);

  let text: string | null = null;
  let tone = "border-amber-500/40 bg-amber-500/10 text-amber-200";
  if (phase === "error" && error) {
    text = error;
    tone = "border-red-500/40 bg-red-500/10 text-red-200";
  } else if (trace) {
    switch (trace.status) {
      case "step_limit":
        text = `Stopped after ${trace.stats.steps} steps — likely an infinite loop. You can still replay what ran.`;
        break;
      case "timeout":
        text = "Timed out — your code ran longer than 10 s.";
        break;
      case "runtime_error":
        tone = "border-red-500/40 bg-red-500/10 text-red-200";
        text = trace.exception
          ? `${trace.exception.type}: ${trace.exception.message} (line ${trace.exception.line})`
          : trace.error ?? "Runtime error";
        break;
      case "compile_error":
        tone = "border-red-500/40 bg-red-500/10 text-red-200";
        text = `Compilation failed — see red markers.${trace.compileErrors?.[0] ? ` Line ${trace.compileErrors[0].line}: ${trace.compileErrors[0].message}` : ""}`;
        break;
    }
    if (trace.stats.truncated && trace.status === "ok") text = "Trace was truncated to stay within size limits.";
  }
  if (!text) return null;
  return (
    <div data-testid="status-banner" className={`mx-3 mt-2 flex items-start gap-2 rounded-md border px-3 py-2 text-sm ${tone}`}>
      <AlertTriangle size={16} className="mt-0.5 shrink-0" />
      <span className="whitespace-pre-wrap">{text}</span>
    </div>
  );
}
