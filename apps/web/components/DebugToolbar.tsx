"use client";

import { ArrowDownToLine, ArrowRightToLine, ArrowUpFromLine, FastForward, Rewind, StepBack, SkipBack, Undo2 } from "lucide-react";
import { useEffect } from "react";
import {
  continueRun,
  reverseContinue,
  stepBack,
  stepInto,
  stepOut,
  stepOutBack,
  stepOver,
  stepOverBack,
  type Nav,
} from "@/lib/replay";
import { useDebugStore } from "@/store/debugStore";

/** Eclipse keys: F5 into, F6 over, F7 out, F8 resume. Shift reverses. */
const KEYS: Record<string, [Nav, Nav]> = {
  F5: [stepInto, stepBack],
  F6: [stepOver, stepOverBack],
  F7: [stepOut, stepOutBack],
  F8: [continueRun, reverseContinue],
};

export function DebugToolbar() {
  const trace = useDebugStore((s) => s.trace);
  const nav = useDebugStore((s) => s.nav);
  const enabled = !!trace && trace.steps.length > 0;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
        e.preventDefault();
        useDebugStore.getState().debug();
        return;
      }
      const pair = KEYS[e.key];
      if (!pair) return;
      e.preventDefault(); // F5 would reload the page
      useDebugStore.getState().nav(e.shiftKey ? pair[1] : pair[0]);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const buttons: { id: string; label: string; key: string; fn: Nav; icon: React.ReactNode }[] = [
    { id: "reverse-continue", label: "Reverse to breakpoint", key: "⇧F8", fn: reverseContinue, icon: <Rewind size={16} /> },
    { id: "step-back", label: "Step back", key: "⇧F5", fn: stepBack, icon: <StepBack size={16} /> },
    { id: "step-over-back", label: "Step over back", key: "⇧F6", fn: stepOverBack, icon: <Undo2 size={16} /> },
    { id: "step-into", label: "Step into", key: "F5", fn: stepInto, icon: <ArrowDownToLine size={16} /> },
    { id: "step-over", label: "Step over", key: "F6", fn: stepOver, icon: <ArrowRightToLine size={16} /> },
    { id: "step-out", label: "Step out", key: "F7", fn: stepOut, icon: <ArrowUpFromLine size={16} /> },
    { id: "continue", label: "Resume to breakpoint", key: "F8", fn: continueRun, icon: <FastForward size={16} /> },
  ];

  return (
    <div className="flex items-center gap-1">
      <button className="icon-btn" disabled={!enabled} onClick={() => useDebugStore.getState().setIdx(0)} title="Restart (step 1)">
        <SkipBack size={16} />
      </button>
      {buttons.map((b) => (
        <button
          key={b.id}
          data-testid={b.id}
          className="icon-btn"
          disabled={!enabled}
          onClick={() => nav(b.fn)}
          title={`${b.label} (${b.key})`}
        >
          {b.icon}
        </button>
      ))}
    </div>
  );
}
