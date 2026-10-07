import type { Trace } from "@dsa/shared";

/** Replay navigation: each function maps the current step index to the next one to show. */
export type Nav = (trace: Trace, idx: number, bps: ReadonlySet<number>) => number;

const last = (t: Trace) => Math.max(0, t.steps.length - 1);
const clamp = (t: Trace, i: number) => Math.min(Math.max(i, 0), last(t));

function findNext(t: Trace, idx: number, pred: (k: number) => boolean, fallback: number): number {
  for (let k = idx + 1; k < t.steps.length; k++) if (pred(k)) return k;
  return fallback;
}

function findPrev(t: Trace, idx: number, pred: (k: number) => boolean, fallback: number): number {
  for (let k = idx - 1; k >= 0; k--) if (pred(k)) return k;
  return fallback;
}

const depthAt = (t: Trace, i: number) => t.steps[i]?.depth ?? 0;
const isBreak = (t: Trace, k: number, bps: ReadonlySet<number>) =>
  t.steps[k].event === "line" && bps.has(t.steps[k].line);

export const stepInto: Nav = (t, idx) => clamp(t, idx + 1);
export const stepOver: Nav = (t, idx) => findNext(t, idx, (k) => depthAt(t, k) <= depthAt(t, idx), last(t));
export const stepOut: Nav = (t, idx) => findNext(t, idx, (k) => depthAt(t, k) < depthAt(t, idx), last(t));
export const continueRun: Nav = (t, idx, bps) => findNext(t, idx, (k) => isBreak(t, k, bps), last(t));

export const stepBack: Nav = (t, idx) => clamp(t, idx - 1);
export const stepOverBack: Nav = (t, idx) => findPrev(t, idx, (k) => depthAt(t, k) <= depthAt(t, idx), 0);
export const stepOutBack: Nav = (t, idx) => findPrev(t, idx, (k) => depthAt(t, k) < depthAt(t, idx), 0);
export const reverseContinue: Nav = (t, idx, bps) => findPrev(t, idx, (k) => isBreak(t, k, bps), 0);
