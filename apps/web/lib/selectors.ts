import type { Frame, Step, Trace } from "@dsa/shared";

export function currentStep(trace: Trace | null, idx: number): Step | null {
  return trace && trace.steps.length > 0 ? trace.steps[Math.min(idx, trace.steps.length - 1)] : null;
}

/** The frame the user is inspecting: the clicked call-stack frame, else the top frame. */
export function selectedFrame(step: Step | null, fid: number | null): Frame | null {
  if (!step || step.stack.length === 0) return null;
  return step.stack.find((f) => f.fid === fid) ?? step.stack[0];
}
