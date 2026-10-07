"use client";

import { ChevronRight } from "lucide-react";
import { useState } from "react";
import type { Value } from "@dsa/shared";
import { formatValue, isRef, shortName } from "@/lib/format";

type Child = { label: string; value: Value };

function children(v: Value): Child[] {
  switch (v.t) {
    case "array":
    case "list":
    case "set":
      return v.v.map((value, i) => ({ label: `[${i}]`, value }));
    case "map":
      return v.entries.map(([k, value]) => ({ label: formatValue(k), value }));
    case "obj":
      return v.fields.map((f) => ({ label: f.name, value: f.value }));
    default:
      return [];
  }
}

function typeLabel(v: Value): string {
  if (v.t === "array") return `${v.elem}[${v.len}]`;
  if (v.t === "list" || v.t === "set" || v.t === "map") return `${shortName(v.cls)} (size ${v.len})`;
  if (v.t === "obj" || v.t === "ref") return shortName(v.cls);
  if (v.t === "str") return "String";
  return v.t;
}

/** One variable row, expandable for containers and objects (Eclipse-style tree). */
export function ValueView({ name, value, changed, depth = 0 }: { name: string; value: Value; changed?: boolean; depth?: number }) {
  const [open, setOpen] = useState(depth === 0 && value.t === "obj");
  const kids = children(value);
  const expandable = kids.length > 0;
  return (
    <div>
      <div
        data-testid={depth === 0 ? `var-${name}` : undefined}
        data-value={formatValue(value)}
        className={`group flex items-baseline gap-2 rounded px-2 py-0.5 font-mono text-[13px] ${changed ? "var-changed" : ""}`}
        style={{ paddingLeft: 8 + depth * 14 }}
      >
        <button
          className={`w-3 shrink-0 text-muted transition-transform ${expandable ? "" : "invisible"} ${open ? "rotate-90" : ""}`}
          onClick={() => setOpen(!open)}
          aria-label="Expand"
        >
          <ChevronRight size={12} />
        </button>
        <span className="text-sky-300">{name}</span>
        <span className="text-muted">=</span>
        <span className="truncate text-amber-100" title={formatValue(value)}>
          {formatValue(value)}
        </span>
        <span className="ml-auto shrink-0 text-[11px] text-muted/70">
          {typeLabel(value)}
          {isRef(value) && <span className="ml-1 opacity-70">(id={value.id})</span>}
        </span>
      </div>
      {open && kids.map((c) => <ValueView key={c.label} name={c.label} value={c.value} depth={depth + 1} />)}
    </div>
  );
}
