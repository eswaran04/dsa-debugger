"use client";

import MonacoEditor, { type OnMount } from "@monaco-editor/react";
import { useEffect, useRef } from "react";
import { currentStep, selectedFrame } from "@/lib/selectors";
import { useDebugStore } from "@/store/debugStore";

type Monaco = Parameters<OnMount>[1];
type CodeEditor = Parameters<OnMount>[0];

export function Editor() {
  const code = useDebugStore((s) => s.code);
  const setCode = useDebugStore((s) => s.setCode);
  const trace = useDebugStore((s) => s.trace);
  const idx = useDebugStore((s) => s.idx);
  const selectedFid = useDebugStore((s) => s.selectedFid);
  const breakpoints = useDebugStore((s) => s.breakpoints);
  const toggleBreakpoint = useDebugStore((s) => s.toggleBreakpoint);

  const editorRef = useRef<CodeEditor | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const decorations = useRef<ReturnType<CodeEditor["createDecorationsCollection"]> | null>(null);

  const step = currentStep(trace, idx);
  const frame = selectedFrame(step, selectedFid);
  const line = frame?.line ?? null;

  const onMount: OnMount = (editor, monaco) => {
    editorRef.current = editor;
    monacoRef.current = monaco;
    decorations.current = editor.createDecorationsCollection();
    monaco.editor.defineTheme("dsa-dark", {
      base: "vs-dark",
      inherit: true,
      rules: [],
      colors: { "editor.background": "#0b0f17", "editorGutter.background": "#0b0f17" },
    });
    monaco.editor.setTheme("dsa-dark");
    editor.onMouseDown((e) => {
      const t = e.target.type;
      const isGutter =
        t === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN || t === monaco.editor.MouseTargetType.GUTTER_LINE_NUMBERS;
      if (isGutter && e.target.position) toggleBreakpoint(e.target.position.lineNumber);
    });
  };

  // Line decorations: breakpoints, current line, other frames, exception line.
  useEffect(() => {
    const monaco = monacoRef.current;
    const coll = decorations.current;
    if (!monaco || !coll) return;
    const range = (l: number) => new monaco.Range(l, 1, l, 1);
    type Deco = Parameters<typeof coll.set>[0][number];
    const decos: Deco[] = breakpoints.map((l) => ({
      range: range(l),
      options: { glyphMarginClassName: "dbg-breakpoint", glyphMarginHoverMessage: { value: "Breakpoint" } },
    }));
    if (step) {
      for (const f of step.stack) {
        if (f.fid === frame?.fid) continue;
        decos.push({ range: range(f.line), options: { isWholeLine: true, className: "dbg-frame-line" } });
      }
      if (line != null) {
        const exc = step.event === "exception";
        decos.push({
          range: range(line),
          options: { isWholeLine: true, className: exc ? "dbg-exception-line" : "dbg-current-line" },
        });
        editorRef.current?.revealLineInCenterIfOutsideViewport(line);
      }
    }
    coll.set(decos);
  }, [breakpoints, step, frame?.fid, line]);

  // Compile errors as red squiggles.
  useEffect(() => {
    const monaco = monacoRef.current;
    const model = editorRef.current?.getModel();
    if (!monaco || !model) return;
    const errs = trace?.status === "compile_error" ? trace.compileErrors ?? [] : [];
    monaco.editor.setModelMarkers(
      model,
      "javac",
      errs.map((e) => ({
        startLineNumber: e.line,
        endLineNumber: e.line,
        startColumn: Math.max(1, e.col),
        endColumn: Math.max(2, e.col + 1),
        message: e.message,
        severity: monaco.MarkerSeverity.Error,
      })),
    );
  }, [trace]);

  return (
    <div className="relative h-full">
      <span data-testid="current-line" data-line={line ?? ""} hidden />
      <MonacoEditor
        language="java"
        value={code}
        onChange={(v) => setCode(v ?? "")}
        onMount={onMount}
        theme="vs-dark"
        options={{
          glyphMargin: true,
          fontFamily: "var(--font-mono), monospace",
          fontSize: 14,
          lineHeight: 22,
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
          renderLineHighlight: "none",
          automaticLayout: true,
          tabSize: 4,
        }}
      />
    </div>
  );
}
