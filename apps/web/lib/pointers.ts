import type { Frame } from "@dsa/shared";

export type Pointer = { name: string; index: number; outOfRange: boolean };

/** int locals that look like indexes into an array of arrayLen (one past either end included). */
export function pointerVars(frame: Frame, arrayLen: number): Pointer[] {
  const out: Pointer[] = [];
  for (const { name, value } of frame.locals) {
    if (value.t !== "int" || typeof value.v !== "number") continue;
    const v = value.v;
    if (v < -1 || v > arrayLen) continue;
    out.push({ name, index: v, outOfRange: v < 0 || v >= arrayLen });
  }
  return out;
}
