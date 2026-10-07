"use client";

import { useDebugStore } from "@/store/debugStore";

export function InputPanel() {
  const argsText = useDebugStore((s) => s.argsText);
  const setArgsText = useDebugStore((s) => s.setArgsText);
  return (
    <div className="flex h-full flex-col">
      <div className="panel-title">Input</div>
      <textarea
        data-testid="args-input"
        value={argsText}
        onChange={(e) => setArgsText(e.target.value)}
        spellCheck={false}
        placeholder="nums = [7, 4, 1, 5, 3], target = 9"
        className="flex-1 resize-none bg-transparent p-3 font-mono text-sm outline-none placeholder:text-muted/50"
      />
    </div>
  );
}
