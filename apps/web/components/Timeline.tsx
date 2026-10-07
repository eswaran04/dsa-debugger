"use client";

import { useDebugStore } from "@/store/debugStore";

export function Timeline() {
  const trace = useDebugStore((s) => s.trace);
  const idx = useDebugStore((s) => s.idx);
  const breakpoints = useDebugStore((s) => s.breakpoints);
  const setIdx = useDebugStore((s) => s.setIdx);
  const n = trace?.steps.length ?? 0;
  const bps = new Set(breakpoints);

  return (
    <div className="flex flex-1 items-center gap-3">
      <div className="relative flex-1">
        <input
          data-testid="timeline"
          type="range"
          min={0}
          max={Math.max(0, n - 1)}
          value={Math.min(idx, Math.max(0, n - 1))}
          disabled={n === 0}
          onChange={(e) => setIdx(Number(e.target.value))}
          className="timeline w-full"
        />
        {n > 1 && (
          <div className="pointer-events-none absolute inset-x-[7px] -bottom-1 h-1.5">
            {trace!.steps.map((s, k) => {
              const color =
                s.event === "exception"
                  ? "bg-orange-400"
                  : s.event === "line" && bps.has(s.line)
                    ? "bg-red-500"
                    : s.event === "return"
                      ? "bg-violet-400/60"
                      : null;
              if (!color) return null;
              return <span key={k} className={`absolute h-1.5 w-[2px] ${color}`} style={{ left: `${(k / (n - 1)) * 100}%` }} />;
            })}
          </div>
        )}
      </div>
      <span className="w-28 text-right font-mono text-xs text-muted tabular-nums">
        {n === 0 ? "No trace" : `Step ${idx + 1} / ${n}`}
      </span>
    </div>
  );
}
