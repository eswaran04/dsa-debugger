# DSA Debugger — Phase 1 Design (Java, local-only)

## Context

Goal: a polished web app where a user pastes a Java `class Solution { ... }` (e.g. quickSort), enters named inputs (`nums = [7, 4, 1, 5, 3]`), and debugs it line by line like Eclipse. They see the current line, local variables, the call stack, and arrays with index pointers (i, j, low, high), so they can find where a loop or condition goes wrong. Then they edit the code, re-run, and see the returned output.

**Revision (2026-10-07):** the user changed hosting to **local-only**. The app runs on the user's machine and uses the system JDK directly. The following are removed: the Fastify runner, nsjail, Docker, Fly.io, Vercel, rate limiting, and the shared secret. Next.js API routes spawn the tracer JVM themselves.

Decisions made with the user:
- **Debug model:** record + replay. The tracer runs the code once under a real JDI debugger and records every line step. The UI then steps forward and backward instantly.
- **Hosting:** local only, single user, system JDK (JDK 17 installed at `/Library/Java/JavaVirtualMachines/jdk-17.jdk`).
- **Inputs:** named args, e.g. `nums = [2,7,11,15], target = 9`.
- **Types:** primitives, String, arrays (1D/2D), `List<Integer>`, `List<List<Integer>>`, `List<String>`. No ListNode/TreeNode in phase 1.
- **Accounts:** none. Code, inputs, and breakpoints are kept in browser localStorage.

**Security note:** the app executes arbitrary pasted Java with the user's own permissions. The server must bind to `127.0.0.1` only (`next dev -H 127.0.0.1` / `next start -H 127.0.0.1`), so no one on the LAN can reach it. Wall-time and step limits stay in place to stop runaway code.

## Architecture

```
Browser (Next.js UI, http://127.0.0.1:3000)
   │  POST /api/debug | /api/run  {code, method?, args}
   ▼
Next.js route handler (runtime "nodejs"): validate (zod) → runTracer()
   │  child_process.spawn(<JAVA_HOME>/bin/java -jar tracer.jar -), job JSON on stdin, SIGKILL at wallMs+3s
   ▼
tracer.jar (system JDK)
   1. compile Solution.java (javax.tools, -g -parameters) → compile errors w/ line/col
   2. launch debuggee JVM via JDI CommandLineLaunch (Harness main → Solution.method)
   3. record steps → Trace JSON on stdout
```

Why there is no separate Node service: in local mode the Next.js server already is a Node process, so it can spawn Java directly. JDI exists only in Java, so the tracer stays a Java CLI.

## Repo layout (pnpm workspace)

```
dsa_debugger/
  apps/web/            Next.js 15 App Router, TS, Tailwind v4, shadcn/ui, Monaco, zustand, framer-motion
    lib/tracerRunner.ts   spawn java + parse Trace
    lib/validate.ts       zod request schema
    app/api/{debug,run}/route.ts
  packages/tracer/     Java 17, plain javac/jar scripts + JUnit console jar (no Gradle/Maven installed)
  packages/shared/     TS types: Trace, Step, Frame, Value, DebugRequest
```

## Component 1 — Tracer (`packages/tracer`): unchanged from the approved design

- **Compile:** `javax.tools` with `-g -parameters`. It also collects `Solution` method signatures in source order (javac Trees API) and lists all user classes (helpers, nested).
- **Entry method:** an explicit `method` wins. Otherwise pick the first non-private method (public preferred) whose param-name set equals the arg names. Errors list the signatures.
- **Harness:** a debuggee main that converts JSON args by generic param type and invokes the method. Exit 3 means an arg error; exit 1 means a user exception.
- **Recording:**
  - A breakpoint on the entry method starts a `StepRequest(STEP_LINE, STEP_INTO)` with exclusions `java.* javax.* jdk.* sun.* com.sun.* dev.dsadebug.*`.
  - Method entry/exit events maintain frame ids (`fid`) and record return values.
  - The entry exit gives `result`, plus `finalArgs` for void methods.
  - Exceptions are tracked as the last thrown exception while a user frame is on the stack. They are reported only if the debuggee exits with code 1.
  - Stdout is attached per step.
  - The stack is capped at the top 64 user frames.
- **ValueSerializer:** reads JDI fields directly and never calls `invokeMethod`. It covers arrays, ArrayList, LinkedList, ArrayDeque, HashMap, HashSet, boxed types, and objects. Caps: 1000 elements, depth 3. The `id` field is used to show aliasing.
- **Limits** (still needed locally for infinite loops and huge output): `maxSteps` 5000 → `step_limit`, `wallMs` 10000 → `timeout`, `maxStdoutBytes` 65536, `maxTraceBytes` 5 MB. The partial trace is always returned.
- **Debuggee flags:** `-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=1 -Xss16m`.
- **CLI:** `java -jar tracer.jar <job.json | ->` prints exactly one Trace JSON and exits 0 for every status.

Output schema: unchanged (`Trace`, `Step`, `Frame`, `Var`, `Value` in `packages/shared`).

## Component 2 — Local bridge (`apps/web`, replaces runner + proxy)

- `runTracer(jobJson: string, opts: { javaBin: string; tracerJar: string; wallMs: number }) -> Promise<Trace>`:
  - Spawns `javaBin -jar tracerJar -`, writes the job to stdin, collects stdout and stderr, and SIGKILLs at `wallMs + 3000`.
  - Killed with empty stdout → `{status:"timeout", steps:[], stdout:"", stats:{...}}`.
  - Spawn `ENOENT` → throws `JavaNotFoundError("Java not found. Install JDK 17+ or set JAVA_HOME.")`.
  - Unparseable stdout → throws with stderr attached.
- Resolving the java binary: `JAVA_HOME ? $JAVA_HOME/bin/java : "java"`. The tracer jar is `TRACER_JAR` or `path.resolve(process.cwd(), "../../packages/tracer/build/tracer.jar")`. A missing jar → 500 with `Run pnpm -F tracer build`.
- The tracer itself emits `{status:"runtime_error", error:"A JDK is required (javac not available) — a JRE is not enough."}` when `ToolProvider.getSystemJavaCompiler()` is null.
- `DebugRequestSchema` (zod): code ≤ 50 KB, must match `class\s+Solution\b`, args JSON ≤ 10 KB, arg names must be Java identifiers.
- Routes: `POST /api/debug` (`record:true`) and `POST /api/run` (`record:false`). Both use `export const runtime = "nodejs"`. Validation failure → 400 `{error}`. Java missing → 500 `{error}`.
- The store disables Debug/Run while a request is in flight, so a double-click never starts two JVMs. No queue is needed.

## Component 3 — Web UI: unchanged from the approved design

- **Layout:** IDE layout with resizable panels. Monaco editor with gutter breakpoints, current-line highlight, and red squiggles for compile errors.
- **Panels:** Variables (changed values flash yellow, `(id=…)` suffix), Call stack (click a frame to jump to it), Array visualizer (pointer badges, out-of-range in red, 2D grid), Console (stdout up to the current step), Result (`finalArgs` for void methods, optional expected-output ✓/✗).
- **Controls:** Timeline scrubber with markers for breakpoints, returns, and exceptions.
- **Eclipse keys:** F5 into, F6 over, F7 out, F8 continue. Shift + the same keys runs reverse. Ctrl/Cmd+Enter starts Debug. `preventDefault` stops F5 from reloading the page.
- **Replay engine** (`lib/replay.ts`, pure): `stepInto stepOver stepOut continueRun stepBack stepOverBack stepOutBack reverseContinue`.
- **Status banners:** `step_limit`, `timeout`, `runtime_error`, `compile_error`.
- **Samples:** quick-sort, two-sum, sort-colors (void), group-anagrams.

## Dev workflow

- Root `pnpm dev` = `pnpm -F tracer build && pnpm -F web dev`, where web `dev` = `next dev -H 127.0.0.1 -p 3000`.
- `pnpm start` = build all + `next start -H 127.0.0.1 -p 3000`.
- Prereqs: Node 22+, JDK 17+, and pnpm via `corepack enable`.

Implementation plan: `docs/superpowers/plans/2026-10-07-dsa-debugger.md`.

## Milestones

0. **Spike:** a minimal JDI tracer on quickSort locally. It must produce a correct trace in under 5 s (Task 5 gate).
1. **Tracer complete** (Tasks 1–6).
2. **Web libs + local bridge** (Tasks 7–8).
3. **UI + e2e** (Tasks 9–11).
4. **README / start scripts** (Task 12).

## Verification

- **Tracer JUnit:**
  - quickSort `[7,4,1,5,3]` → ok, `[1,3,4,5,7]`, `placeInPosition` steps with `i`/`j`
  - `while(true)` → `step_limit`
  - syntax error → `compile_error` with its line
  - `nums[10]` → `runtime_error` with its line
  - `ArrayList.get(0)` on empty → `IndexOutOfBoundsException` at the user line
  - void method → `finalArgs`
  - helper class stepped
  - stdout per step
  - `Thread.sleep` → `timeout`
  - byte cap honored
- **Web vitest:** replay engine, args parser (commas inside strings, negatives), method scan, diff, pointers, format, `runTracer` (fake java, ENOENT, timeout, real jar), schema, store.
- **Playwright e2e:** load quickSort, Debug, F5 into `placeInPosition`, F6 until `i` changes, breakpoint + F8, Shift+F6 back. The end shows `[1, 3, 4, 5, 7]`. Change `<=` to `<` and confirm the different result or banner.
- **Manual:** run `pnpm dev` and open `http://127.0.0.1:3000`. Confirm `curl http://<LAN-IP>:3000` is refused, which proves the localhost binding.
