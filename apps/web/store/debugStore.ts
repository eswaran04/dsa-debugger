"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { Trace } from "@dsa/shared";
import { parseNamedArgs } from "@/lib/parseArgs";
import type { Nav } from "@/lib/replay";
import { samples } from "@/lib/samples";

export type Phase = "idle" | "running" | "ready" | "error";

type State = {
  code: string;
  argsText: string;
  method: string | null;
  breakpoints: number[];
  sampleId: string;
  trace: Trace | null;
  /** Whether the current trace came from Run (no steps) rather than Debug. */
  runOnly: boolean;
  idx: number;
  selectedFid: number | null;
  phase: Phase;
  error: string | null;
  debug: () => Promise<void>;
  run: () => Promise<void>;
  nav: (fn: Nav) => void;
  setIdx: (n: number) => void;
  toggleBreakpoint: (line: number) => void;
  loadSample: (id: string) => void;
  setCode: (code: string) => void;
  setArgsText: (text: string) => void;
  setMethod: (m: string | null) => void;
  selectFrame: (fid: number | null) => void;
};

const first = samples[0];

export const useDebugStore = create<State>()(
  persist(
    (set, get) => {
      async function execute(path: "/api/debug" | "/api/run") {
        if (get().phase === "running") return;
        const parsed = parseNamedArgs(get().argsText);
        if (!parsed.ok) {
          set({ phase: "error", error: parsed.error });
          return;
        }
        set({ phase: "running", error: null });
        try {
          const res = await fetch(path, {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ code: get().code, method: get().method, args: parsed.args }),
          });
          const body = await res.json();
          if (!res.ok) {
            set({ phase: "error", error: body.error ?? `Request failed (${res.status})`, trace: null });
            return;
          }
          set({ phase: "ready", trace: body as Trace, runOnly: path === "/api/run", idx: 0, selectedFid: null });
        } catch (e) {
          set({ phase: "error", error: e instanceof Error ? e.message : String(e), trace: null });
        }
      }

      return {
        code: first.code,
        argsText: first.args,
        method: null,
        breakpoints: [],
        sampleId: first.id,
        trace: null,
        runOnly: false,
        idx: 0,
        selectedFid: null,
        phase: "idle",
        error: null,
        debug: () => execute("/api/debug"),
        run: () => execute("/api/run"),
        nav: (fn) => {
          const { trace, idx, breakpoints } = get();
          if (!trace || trace.steps.length === 0) return;
          set({ idx: fn(trace, idx, new Set(breakpoints)), selectedFid: null });
        },
        setIdx: (n) => {
          const t = get().trace;
          if (!t || t.steps.length === 0) return;
          set({ idx: Math.min(Math.max(n, 0), t.steps.length - 1), selectedFid: null });
        },
        toggleBreakpoint: (line) =>
          set((s) => ({
            breakpoints: s.breakpoints.includes(line) ? s.breakpoints.filter((l) => l !== line) : [...s.breakpoints, line],
          })),
        loadSample: (id) => {
          const s = samples.find((x) => x.id === id);
          if (!s) return;
          set({ sampleId: id, code: s.code, argsText: s.args, method: null, breakpoints: [], trace: null, phase: "idle", error: null });
        },
        setCode: (code) => set({ code, trace: null, phase: get().phase === "running" ? "running" : "idle" }),
        setArgsText: (argsText) => set({ argsText }),
        setMethod: (method) => set({ method }),
        selectFrame: (selectedFid) => set({ selectedFid }),
      };
    },
    {
      name: "dsa-debugger:v1",
      partialize: (s) => ({ code: s.code, argsText: s.argsText, method: s.method, breakpoints: s.breakpoints, sampleId: s.sampleId }),
    },
  ),
);
