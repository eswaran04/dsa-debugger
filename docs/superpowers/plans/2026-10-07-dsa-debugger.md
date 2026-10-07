# DSA Debugger Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A local-only web app that records a line-by-line JDI trace of a user's Java `Solution` class run on named inputs and replays it Eclipse-style (step over/into/out/back, breakpoints, variables, call stack, array pointers).

**Architecture:** A Java tracer (`tracer.jar`) compiles user code, launches it under JDI, and emits a `Trace` JSON. Next.js API routes (bound to 127.0.0.1) spawn the tracer with the system JDK per request; the browser replays the trace client-side. No hosting, no sandbox.

**Tech Stack:** Java 17 (JDI, javax.tools, JUnit 5 console launcher; no Gradle/Maven), Node 22 + TypeScript, pnpm workspaces (via corepack), zod, vitest, Next.js 15 App Router, Tailwind v4, shadcn/ui, @monaco-editor/react, zustand, framer-motion, react-resizable-panels, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-07-dsa-debugger-design.md`

## Global Constraints

- Tracer source level: `--release 17`. Runtime requirement: JDK 17+ (a JRE is not enough; javac + JDI are needed). Java binary = `$JAVA_HOME/bin/java` if `JAVA_HOME` is set, else `java` on PATH.
- Tracer packages: `dev.dsadebug.json`, `dev.dsadebug.harness`, `dev.dsadebug.tracer`. User code lives in the default package. Step exclusion filters: `java.*`, `javax.*`, `jdk.*`, `sun.*`, `com.sun.*`, `dev.dsadebug.*`.
- Compile flags for user code: `-g -parameters`.
- Default limits: `maxSteps` 5000, `maxTraceBytes` 5 MB (5_242_880), `wallMs` 10000, `maxStdoutBytes` 65536. Arrays/lists are capped at 1000 elements. Nested serialization depth is 3.
- Debuggee JVM flags: `-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=1 -Xss16m`.
- Request limits (API routes): code ≤ 50 KB, serialized args ≤ 10 KB, code must contain `class Solution`.
- **Security:** the app runs arbitrary pasted Java with the user's permissions. The Next.js server binds to `127.0.0.1` only (`next dev -H 127.0.0.1 -p 3000`, `next start -H 127.0.0.1 -p 3000`). Never bind to `0.0.0.0`.
- API routes: `export const runtime = "nodejs"`. The tracer process is SIGKILLed at `wallMs + 3000` ms.
- Supported arg types: `int long double boolean char String int[] int[][] char[][] String[] List<Integer> List<List<Integer>> List<String>`.
- No accounts and no DB. Browser persistence uses localStorage only.

## Review Focus

1. **Void entry method that mutates its input** (e.g. `void sortColors(int[] nums)`): the user expects to see the mutated `nums` as the output. Tracer emits `finalArgs`, and the Result panel shows them when `result.t === "void"`. Tested in Task 5 and Task 10.
2. **Helper classes beside `Solution`** (a top-level `class Pair`, or a nested `static class Node`): lines inside them must be stepped, and they count as user frames. User classes come from the compile output, not from a `Solution*` filter. Tested in Task 5.
3. **Inputs containing commas, negatives, or spaces inside values** (`words = ["a,b", "c"], k = -1`): the parser splits only on top-level commas. Tested in Task 7.
4. **Runaway output or deep recursion** (`while(true) System.out.println(i)`, or recursion without a base case): stdout is capped at `maxStdoutBytes`, and `StackOverflowError` is reported as `runtime_error`. The response stays under `maxTraceBytes`, and nothing hangs. Tested in Task 6.
5. **User code with `import java.util.*;`, non-public methods, or a missing `public` on the class**: it compiles and resolves. Method resolution prefers public methods but also accepts package-private ones. Tested in Task 3.

---

## File Structure

```
dsa_debugger/
  package.json  pnpm-workspace.yaml  .gitignore  .nvmrc
  packages/shared/
    package.json  tsconfig.json  src/index.ts  src/trace.ts  src/api.ts
  packages/tracer/
    package.json            # scripts: build -> ./build.sh, test -> ./test.sh
    build.sh  test.sh       # javac/jar; test.sh downloads JUnit console jar into lib/ once
    src/main/java/dev/dsadebug/json/Json.java            # parse + write (no deps)
    src/main/java/dev/dsadebug/harness/ArgConverter.java # raw JSON -> Java value by generic Type
    src/main/java/dev/dsadebug/harness/Harness.java      # debuggee main
    src/main/java/dev/dsadebug/tracer/Job.java           # job model + parse
    src/main/java/dev/dsadebug/tracer/SourceCompiler.java
    src/main/java/dev/dsadebug/tracer/MethodResolver.java
    src/main/java/dev/dsadebug/tracer/ValueSerializer.java
    src/main/java/dev/dsadebug/tracer/TraceRecorder.java # JDI event loop
    src/main/java/dev/dsadebug/tracer/TraceWriter.java   # Trace -> JSON with byte cap
    src/main/java/dev/dsadebug/tracer/Main.java          # CLI: job path or "-" (stdin)
    src/test/java/dev/dsadebug/...                       # mirrors main
    src/test/resources/fixtures/*.java                   # QuickSort.java, InfiniteLoop.java, ...
  apps/web/
    app/layout.tsx  app/page.tsx  app/globals.css
    app/api/debug/route.ts  app/api/run/route.ts
    lib/replay.ts  lib/parseArgs.ts  lib/scanMethods.ts  lib/diff.ts  lib/pointers.ts
    lib/tracerRunner.ts  lib/validate.ts  lib/samples.ts  lib/format.ts
    store/debugStore.ts
    components/{Header,Editor,DebugToolbar,Timeline,VariablesPanel,ValueView,
                CallStackPanel,ArrayViz,InputPanel,ConsolePanel,ResultPanel,StatusBanner}.tsx
    lib/*.test.ts  e2e/debug.spec.ts  playwright.config.ts
  README.md
```

Deviation from spec layout: samples live in `apps/web/lib/samples.ts`, and tracer fixtures live in `packages/tracer/src/test/resources/fixtures`. There is no root `samples/` dir, because Next cannot import cleanly from outside the app.

---

### Task 1: Workspace scaffold + shared trace types

**Files:**
- Create: `package.json`, `pnpm-workspace.yaml`, `.gitignore`, `.nvmrc` (`22`), `packages/shared/{package.json,tsconfig.json,src/index.ts,src/trace.ts,src/api.ts}`

**Interfaces:**
- Produces (`@dsa/shared`, types only, consumed with `import type`):

```ts
// trace.ts
export type PrimName = "int"|"long"|"double"|"float"|"boolean"|"char"|"byte"|"short";
export type Value =
  | { t: PrimName; v: number | boolean | string }        // char -> 1-char string; long -> number (string if > 2^53)
  | { t: "null" } | { t: "void" }
  | { t: "str"; id: number; v: string }
  | { t: "array"; id: number; elem: string; len: number; v: Value[]; truncated?: true }
  | { t: "list" | "set"; id: number; cls: string; len: number; v: Value[]; truncated?: true }
  | { t: "map"; id: number; cls: string; len: number; entries: [Value, Value][]; truncated?: true }
  | { t: "obj"; id: number; cls: string; fields: Var[] }
  | { t: "ref"; id: number; cls: string };                  // depth cut-off
export type Var = { name: string; value: Value };
export type Frame = { fid: number; cls: string; method: string; line: number; locals: Var[] };
export type Step = { line: number; depth: number; event: "line"|"return"|"exception";
  stack: Frame[]; stdout?: string; returnValue?: Value; method?: string };   // stack[0] = top
export type TraceStatus = "ok"|"compile_error"|"runtime_error"|"timeout"|"step_limit";
export type Trace = { status: TraceStatus;
  compileErrors?: { line: number; col: number; message: string }[];
  entry?: { method: string; params: { name: string; type: string }[] };
  steps: Step[]; result?: Value; finalArgs?: Var[];
  exception?: { type: string; message: string; line: number };
  error?: string;                                           // resolution/arg errors, human readable
  stdout: string;                                           // full stdout (capped)
  stats: { steps: number; ms: number; truncated: boolean } };
// api.ts
export type ArgInput = { name: string; raw: string };
export type DebugRequest = { code: string; method?: string | null; args: ArgInput[] };
export type ApiError = { error: string };
```

- [ ] **Step 1:** `git init`. Run `corepack enable`, then `pnpm init`. Root `package.json` scripts: `build`, `test`, and `dev` (`pnpm -r --parallel dev`). `pnpm-workspace.yaml` lists `apps/*` and `packages/*`. `.gitignore` covers `node_modules`, `.next`, `build`, `lib/*.jar`, `.env*`, `test-results`, and `playwright-report`.
- [ ] **Step 2:** Write the shared types exactly as above. `packages/shared/package.json` has name `@dsa/shared`, `"exports": {".": "./src/index.ts"}`, and `"typecheck": "tsc --noEmit"`.
- [ ] **Step 3:** Run `pnpm -F @dsa/shared typecheck`. Expected: exit 0.
- [ ] **Step 4:** Commit: `chore: scaffold workspace and shared trace types`. Include the spec and plan docs in this first commit.

---

### Task 2: Tracer build scripts + Json + ArgConverter

**Files:**
- Create: `packages/tracer/{package.json,build.sh,test.sh}`, `src/main/java/dev/dsadebug/json/Json.java`, `src/main/java/dev/dsadebug/harness/ArgConverter.java`
- Test: `src/test/java/dev/dsadebug/json/JsonTest.java`, `src/test/java/dev/dsadebug/harness/ArgConverterTest.java`

**Interfaces:**
- Produces:
  - `Json.parse(String) -> Object`: returns `Map<String,Object>` (LinkedHashMap), `List<Object>`, `String`, `Long`, `Double`, `Boolean`, or `null`. Throws `Json.ParseException(String msg, int pos)`.
  - `Json.Writer`: `beginObj() endObj() beginArr() endArr() key(String) str(String) num(long) num(double) bool(boolean) nul() raw(String)`, plus `int size()` (bytes written so far) and `String toString()`. It manages commas internally and escapes per RFC 8259.
  - `ArgConverter.convert(String raw, java.lang.reflect.Type type) -> Object`. Throws `IllegalArgumentException("expected int[] but got ...")`. `char` accepts a 1-char JSON string. `List<...>` returns an `ArrayList`.
- `build.sh`: runs `javac --release 17 -d build/classes $(find src/main/java -name '*.java')`, then `jar --create --file build/tracer.jar --main-class dev.dsadebug.tracer.Main -C build/classes .`
- `test.sh`: if `lib/junit-platform-console-standalone-1.11.3.jar` is missing, curl it from `https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.3/`. It then compiles main+test into `build/test-classes` with that jar on the classpath and runs `java -jar lib/junit...jar execute -cp build/test-classes:src/test/resources --scan-class-path "$@"`. It passes the env var `TRACER_JAR=build/tracer.jar` and calls `build.sh` first.

- [ ] **Step 1: Write failing tests**
  - `JsonTest.parsesNested`: `parse("{\"a\":[1,-2.5,\"x\\\"y\",true,null]}")` gives a map where `a` = `[1L, -2.5, "x\"y", true, null]`.
  - `JsonTest.rejectsTrailingGarbage`: `parse("[1] x")` throws `ParseException`.
  - `JsonTest.writerEscapes`: a writer emitting `{"s":"a\nb\u0001"}` yields exactly `{"s":"a\\nb\\u0001"}`.
  - `ArgConverterTest`: `"[7, 4, 1, 5, 3]"` → `int[]{7,4,1,5,3}`. `"[[1,2],[3]]"` → `int[][]`. `"[[\"a\",\"b\"]]"` → `char[][]{{'a','b'}}`. `"[[1,2],[]]"` → `List<List<Integer>>` equal to `List.of(List.of(1,2), List.of())`; obtain the generic Type from a dummy method's `getGenericParameterTypes()`. `"9"` → `int` 9. `"\"hi\""` → `String`. `"true"` → `boolean`. `"[1]"` for `int` throws `IllegalArgumentException` whose message contains `int`. `"3000000000"` for `int` throws (overflow).
- [ ] **Step 2:** Run `pnpm -F tracer test`. Expected: compile failure (classes missing).
- [ ] **Step 3:** Implement `Json` (recursive descent) and `ArgConverter` (switch on raw `Class` / `ParameterizedType` raw type `List`).
- [ ] **Step 4:** Run `pnpm -F tracer test`. Expected: all pass.
- [ ] **Step 5:** Commit: `feat(tracer): json and arg conversion`.

---

### Task 3: SourceCompiler + MethodResolver + Harness

**Files:**
- Create: `tracer/Job.java`, `tracer/SourceCompiler.java`, `tracer/MethodResolver.java`, `harness/Harness.java`
- Test: `SourceCompilerTest.java`, `MethodResolverTest.java`, `HarnessTest.java`

**Interfaces:**
- Consumes: `Json`, `ArgConverter` (Task 2).
- Produces:
  - `record Job(String code, String method, List<Arg> args, boolean record, Limits limits)`, `record Arg(String name, String raw)`, and `record Limits(int maxSteps, int maxTraceBytes, int wallMs, int maxStdoutBytes)`. `Job.fromJson(String)` fills missing limits with the Global Constraints defaults and missing `record` with `true`.
  - `SourceCompiler.compile(String code, Path outDir) -> CompileResult`.
    - `record CompileResult(boolean ok, List<Diag> diags, List<String> userClasses, List<MethodSig> methods)`, `record Diag(long line, long col, String message)`.
    - `userClasses` = binary names of every `.class` written (e.g. `Solution`, `Solution$Node`, `Pair`).
    - `methods` = methods of top-level class `Solution` in **source order**, collected with a `com.sun.source.util.TreePathScanner` over `JavacTask.parse()`. `record MethodSig(String name, boolean isPublic, boolean isPrivate, List<String> paramNames, List<String> paramTypes)`; types are source text such as `int[]`.
    - Options: `-g -parameters -proc:none -d outDir`. Source file name: `Solution.java`.
  - `MethodResolver.resolve(List<MethodSig> methods, String requested, List<String> argNames) -> MethodSig`. Throws `ResolveException(String msg)`.
    - Rule: if `requested` is non-null, pick the first sig with that name, then verify its param names equal the arg names as a set.
    - Otherwise: among non-private sigs with param-name set == arg-name set, prefer `isPublic`, then source order.
    - With no match, the message lists `name(type name, ...)` for every non-private sig, plus the given arg names.
  - Harness CLI: `java -cp <tracer.jar>:<classesDir> dev.dsadebug.harness.Harness <invoke.json>`.
    - `invoke.json`: `{"method":"quickSort","paramNames":["nums"],"args":{"nums":"[7,4,1,5,3]"}}`.
    - Behavior: load class `Solution`, find the declared method with that name whose `Parameter.getName()` list equals `paramNames`, then `setAccessible(true)`.
    - Convert args in parameter order, instantiate via the no-arg constructor, and invoke.
    - Arg conversion errors go to stderr as `HARNESS_ARG_ERROR <message>` with exit 3. User exceptions propagate (via `InvocationTargetException.getCause()`), exit 1.
    - Harness prints nothing on success. The tracer captures the result via JDI.

- [ ] **Step 1: Write failing tests**
  - `SourceCompilerTest.compilesQuickSortFixture`: ok. `userClasses` = `[Solution]`. Method names in order = `[quickSort, quick, placeInPosition]`. The `quick` param names = `[nums, low, high]`.
  - `SourceCompilerTest.reportsSyntaxErrorLine`: code with a missing `;` on line 4 gives `ok=false` and `diags[0].line()==4`.
  - `SourceCompilerTest.helperAndNestedClasses`: code containing `class Solution { static class Node{} }` plus `class Pair{}` gives `userClasses` containing `Solution`, `Solution$Node`, and `Pair`.
  - `SourceCompilerTest.importsAndNonPublicClass`: `import java.util.*; class Solution { List<Integer> f(int n){...} }` compiles. Method `f` has `isPublic=false`.
  - `MethodResolverTest`: QuickSort sigs + `["nums"]` → `quickSort`. Args `["nums","low","high"]` → `quick`. Args `["x"]` throws, and the message contains `quickSort(int[] nums)`. Requested `placeInPosition` with args `["nums"]` throws. A package-private `f(int n)` resolves with `["n"]`.
  - `HarnessTest.runsAndExitsZero`: compile the QuickSort fixture to a temp dir, then run Harness via `ProcessBuilder`. Expected exit 0.
  - `HarnessTest.badArgExits3`: same, but with `"nums":"5"`. Expected exit 3, and stderr starts with `HARNESS_ARG_ERROR`.
- [ ] **Step 2:** Run `pnpm -F tracer test`. Expected: FAIL (missing classes).
- [ ] **Step 3:** Implement the four classes per Interfaces. Add fixture `src/test/resources/fixtures/QuickSort.java` = the user's quickSort code verbatim (spec Context).
- [ ] **Step 4:** Run `pnpm -F tracer test`. Expected: PASS.
- [ ] **Step 5:** Commit: `feat(tracer): compile, resolve entry method, harness`.

---

### Task 4: ValueSerializer

**Files:**
- Create: `tracer/ValueSerializer.java`
- Test: `ValueSerializerTest.java`, fixture `fixtures/ValuesProbe.java`, test helper `src/test/java/dev/dsadebug/tracer/JdiFixture.java`

**Interfaces:**
- Produces: `ValueSerializer(int maxElems, int maxDepth)` and `void write(Json.Writer w, com.sun.jdi.Value v)`, which emits the shared `Value` JSON.
  - `ArrayList`: reads fields `elementData` + `size`.
  - `LinkedList`: walks `first`/`next`/`item`.
  - `ArrayDeque`: reads `elements`, `head`, `tail` (circular).
  - `HashMap`/`LinkedHashMap`: walks `table[]` buckets via `next`, reading `key`/`value`.
  - `HashSet`: its `map` field, keys only.
  - Boxed `Integer`/`Long`/`Double`/`Character`/`Boolean`: written as the primitive `Value`.
  - Other objects: `obj` with non-static fields.
  - Past `maxDepth` → `ref`.
  - `long` beyond ±2^53 is written as a string.
  - Never calls `invokeMethod`.
- `JdiFixture.launchAndStopAt(Path classesDir, String mainClass, int line) -> StackFrame` (test-only): launches via `Bootstrap.virtualMachineManager().defaultConnector()` and breaks at the given line.

- [ ] **Step 1: Write failing test** `ValueSerializerTest.serializesAllKinds`. `ValuesProbe.main` declares locals: `int i=3; long big=9007199254740993L; char c='x'; String s="hi"; int[] a={1,2}; int[][] g={{1},{2,3}}; List<Integer> l=new ArrayList<>(List.of(4,5)); List<List<Integer>> ll; Map<String,Integer> m=new HashMap<>(Map.of("k",1)); Set<Integer> st; Deque<Integer> dq; int[] huge=new int[1500];`, followed by a no-op line where the fixture stops. Serialize each local and parse it with `Json.parse`. Assert:
  - `i` → `{t:int,v:3}`; `big` → `v` is the string `"9007199254740993"`; `c` → `v:"x"`; `s` → `t:str`.
  - `a` → `len 2, v[1].v==2`; `g` → `v[1].len==2`.
  - `l` → `t:list, cls:java.util.ArrayList, v:[4,5]`; `ll` nested lists.
  - `m` → `entries[0]` = `["k",1]`; `st` → `t:set`; `dq` → elements in head-to-tail order.
  - `huge` → `v.size()==1000` and `truncated==true`.
  - `a` serialized twice yields the same `id`.
- [ ] **Step 2:** Run `pnpm -F tracer test`. Expected: FAIL.
- [ ] **Step 3:** Implement `ValueSerializer`.
- [ ] **Step 4:** Run `pnpm -F tracer test`. Expected: PASS.
- [ ] **Step 5:** Commit: `feat(tracer): JDI value serializer`.

---

### Task 5: TraceRecorder core + Main CLI (milestone M0a spike)

**Files:**
- Create: `tracer/TraceRecorder.java`, `tracer/TraceWriter.java`, `tracer/Main.java`
- Test: `TraceRecorderTest.java`, fixtures `VoidSort.java`, `WithHelper.java`, `Printer.java`

**Interfaces:**
- Consumes: `Job`, `SourceCompiler`, `MethodResolver`, `ValueSerializer`, `Json.Writer`.
- Produces:
  - `Main`: `java -jar tracer.jar <job.json | ->` writes exactly one `Trace` JSON object to stdout and exits 0 for every trace status. A non-zero exit means a tracer bug.
  - `TraceRecorder.run(Job job) -> String`: the trace JSON. Tests call this directly.

**Algorithm (decided here; implementer follows):**
1. Create a temp work dir and compile.
   - Compile failure → `{status:"compile_error", compileErrors, steps:[], stdout:"", stats}`.
   - Resolve the entry method. `ResolveException` → `{status:"runtime_error", error:msg, steps:[]}`.
   - Write `invoke.json`. Launch with `CommandLineLaunch`: `main` = `dev.dsadebug.harness.Harness <invoke.json>`, `options` = debuggee JVM flags + `-cp <tracer.jar>:<classes>`. The tracer jar path comes from `TraceRecorder.class.getProtectionDomain().getCodeSource()`. Under tests, it comes from env `TRACER_JAR` or the classes dir.
2. Requests:
   - `ClassPrepareRequest` for `Solution`. On prepare, add a `BreakpointRequest` at the entry method's `location()`.
   - `MethodEntryRequest` and `MethodExitRequest`: one class filter per `userClasses` entry, `SUSPEND_EVENT_THREAD`.
   - `ExceptionRequest(null, caught=true, uncaught=true)` with **no** class filter, because exceptions are often thrown inside JDK code such as `ArrayList.get`. Events before the entry breakpoint are ignored.
3. On the entry breakpoint (when `job.record`), create `StepRequest(thread, STEP_LINE, STEP_INTO)` with the exclusion filters from Global Constraints.
4. **User frames** are frames whose declaring type name is in `userClasses`. `depth` = their count.
   - Keep `Deque<Integer> fidStack`. On `MethodEntryEvent`, push `++fidCounter`. On `MethodExitEvent`, pop.
   - Frame `fid` = `fidStack` aligned with the user frames, top first.
   - Ignore entry/exit events that fire before the entry breakpoint.
5. On each `StepEvent` and the entry breakpoint, append `Step{event:"line"}` with full user-frame stack locals (`visibleVariables()`; `AbsentInformationException` → empty).
   - Attach the new stdout bytes drained since the last step.
6. On `MethodExitEvent` (after entry), append `Step{event:"return", method, returnValue, line: location.lineNumber()}`.
   - If it is the entry method at entry depth: set `result` (void → `{t:"void"}`) and set `finalArgs` = entry frame args (`frame.getArgumentValues()` zipped with param names). Then delete the step request and resume to VM death.
7. On `ExceptionEvent` after entry, when the thread has ≥1 user frame, overwrite `pendingException`. It holds `{type, message from the detailMessage field, line of the top user frame, a stack snapshot}`. Catch locations are not used.
   - When the debuggee exits with code 1 (Harness exits 1 on `InvocationTargetException`), append `Step{event:"exception"}` from `pendingException` and set `exception` and status `runtime_error`.
   - Exit code 0 means any exception was caught, so it is ignored.
   - Read the exit code with `vm.process().waitFor()` after `VMDeathEvent`/`VMDisconnectEvent`.
   - **Stack cap:** a recorded `stack` holds at most the top 64 user frames. `depth` stays the true count. This keeps deep recursion O(64) per step.
8. `record:false`: skip steps 3 and 5. Still record the result, `finalArgs`, and the exception.
9. Stdout comes from a daemon thread reading `vm.process().getInputStream()` into a buffer capped at `maxStdoutBytes`. Overflow appends `\n[output truncated]` and sets `stats.truncated`. Stderr is drained and discarded, except for the `HARNESS_ARG_ERROR` line → `status runtime_error`, `error`.

- [ ] **Step 1: Write failing tests (`TraceRecorderTest`, parsing output with `Json.parse`)**
  - `quickSortHappyPath`: QuickSort fixture with `nums=[7,4,1,5,3]` gives:
    - `status=="ok"` and `result.v` values `[1,3,4,5,7]`
    - `entry.method=="quickSort"`
    - first step line = the line of `int low = 0;`
    - some step whose top frame method is `placeInPosition` has locals named `i` and `j`
    - max `depth` ≥ 3
    - some `return` step with `method=="placeInPosition"`
    - every step's `stack[0].line == line`
  - `voidEntryEmitsFinalArgs`: `VoidSort` (`public void sortColors(int[] nums)`) with `nums=[2,0,1]` gives `result.t=="void"` and `finalArgs[0].value.v` values `[0,1,2]`.
  - `helperClassIsStepped`: `WithHelper` (Solution calls `new Pair(1,2).sum()`, where `Pair` is a top-level class) has some step with `stack[0].cls=="Pair"`.
  - `stdoutAttachedToStep`: `Printer` prints `"a"` then `"b"` on separate lines. Concatenating the steps' `stdout` in order equals `"a\nb\n"`, and `trace.stdout` is the same.
  - `runOnlyHasNoSteps`: QuickSort with `record=false` gives `steps.size()==0` and the correct result.
  - `jreOnlyReportsError`: `new TraceRecorder(() -> null).run(job)` (constructor takes a `Supplier<JavaCompiler>`; the default uses `ToolProvider::getSystemJavaCompiler`) gives `status=="runtime_error"` and `error=="A JDK is required (javac not available) — a JRE is not enough."`.
  - `mainCliReadsStdin`: run `java -jar build/tracer.jar -` with the job on stdin. Expected exit 0 and parseable JSON with `status ok`.
- [ ] **Step 2:** Run `pnpm -F tracer test`. Expected: FAIL.
- [ ] **Step 3:** Implement per the algorithm.
- [ ] **Step 4:** Run `pnpm -F tracer test`. Expected: PASS. Also record the QuickSort trace's `stats.ms`; it should be < 5000 ms on the dev Mac. If it is slower, stop and report. This is the M0a spike gate.
- [ ] **Step 5:** Commit: `feat(tracer): JDI step recorder and CLI`.

---

### Task 6: Tracer limits and failure modes

**Files:**
- Modify: `tracer/TraceRecorder.java`, `tracer/TraceWriter.java`
- Test: `TraceLimitsTest.java`, fixtures `InfiniteLoop.java`, `OutOfBounds.java`, `NoBaseCase.java`, `PrintForever.java`, `CaughtInside.java`, `SleepForever.java`

**Interfaces:**
- Produces: `TraceWriter.write(TraceModel m, Limits l) -> String`. If the JSON would exceed `maxTraceBytes`, it drops trailing steps until it fits, then sets `stats.truncated=true`. Status `ok` stays `ok`; `step_limit` and others are preserved. The step budget reserves 64 KB for non-step fields.

- [ ] **Step 1: Write failing tests**
  - `infiniteLoopHitsStepLimit`: `while(true){i++;}` with `maxSteps=500` gives `status=="step_limit"`, `steps.size()==500`, and exit within `wallMs`.
  - `indexOutOfBounds`: `nums[10]` on a 5-element array gives `status=="runtime_error"`, `exception.type=="java.lang.ArrayIndexOutOfBoundsException"`, `exception.line` = that line, and a last step with `event=="exception"`.
  - `stackOverflowReported`: recursion with no base case and `record=false` gives `runtime_error` with type `java.lang.StackOverflowError`. With `record=true`, the status is `step_limit` or `runtime_error`, it finishes before `wallMs`, every `stack.size() ≤ 64`, and the serialized size is ≤ `maxTraceBytes`.
  - `exceptionInsideJdkCall`: `new ArrayList<Integer>().get(0)` in user code gives `runtime_error`, type `java.lang.IndexOutOfBoundsException`, and `exception.line` = the user line.
  - `printForeverCapped`: `while(true) System.out.println(i++);` gives `trace.stdout.length() ≤ 65536 + 32` and status `step_limit`.
  - `caughtExceptionIgnored`: `try { int x = 1/0; } catch (ArithmeticException e) { return -1; }` gives `status ok`, `result.v == -1`.
  - `wallTimeout`: `Thread.sleep(60000)` with `wallMs=2000` gives `status=="timeout"` within 4 s. The partial steps are kept.
  - `traceByteCap`: QuickSort on 200 random ints with `maxTraceBytes=200_000` gives output length ≤ 200_000 and `stats.truncated==true`.
- [ ] **Step 2:** Run `pnpm -F tracer test`. Expected: the new tests FAIL.
- [ ] **Step 3:** Implement:
  - step counter → `step_limit` + `vm.exit(0)`
  - a watchdog `ScheduledExecutorService` at `wallMs` → `timeout` + `vm.exit(0)`
  - byte-capped `TraceWriter`
- [ ] **Step 4:** Run `pnpm -F tracer test`. Expected: all PASS.
- [ ] **Step 5:** Commit: `feat(tracer): step, time, output and size limits`.

---

### Task 7: Web pure libs (replay, args parser, method scan, diff, pointers)

**Files:**
- Create: `apps/web` via `pnpm create next-app apps/web --ts --tailwind --app --eslint --no-src-dir --import-alias "@/*"`. Add `transpilePackages: ["@dsa/shared"]` to `next.config.ts`. Then create `lib/{replay,parseArgs,scanMethods,diff,pointers}.ts`.
- Test: `lib/{replay,parseArgs,scanMethods,diff,pointers}.test.ts` (vitest)

**Interfaces:**
- Consumes: `Trace`, `Step`, `Frame`, `Value`, `ArgInput` from `@dsa/shared`.
- Produces:
  - `replay.ts`: each function has signature `(trace: Trace, idx: number, bps: ReadonlySet<number>) => number` and clamps to `[0, steps.length-1]`.
    - `stepInto` = idx+1.
    - `stepOver` = first k>idx with `depth ≤ steps[idx].depth`, else last.
    - `stepOut` = first k>idx with `depth < steps[idx].depth`, else last.
    - `continueRun` = first k>idx with `event==="line" && bps.has(line)`, else last.
    - `stepBack` = idx-1.
    - `stepOverBack` = last k<idx with `depth ≤ current`, else 0.
    - `stepOutBack` = last k<idx with `depth < current`, else 0.
    - `reverseContinue` = last k<idx breakpoint line, else 0.
  - `parseArgs.ts`: `parseNamedArgs(text: string) => { ok: true; args: ArgInput[] } | { ok: false; error: string }`.
    - Splits on commas or newlines at bracket depth 0 and outside double quotes.
    - Each part is `name = value`. `value` must `JSON.parse`; on failure the error is `` `Invalid value for ${name}` ``.
    - Duplicate names give an error. Empty text → `{ok:true,args:[]}`.
  - `scanMethods.ts`: `scanMethods(code: string) => { name: string; params: { type: string; name: string }[] }[]`.
    - Regex-scans method declarations inside `class Solution`, excluding `private`, in source order.
  - `diff.ts`: `changedVars(trace: Trace, idx: number, fid: number) => Set<string>`.
    - Compares the frame `fid`'s locals at `idx` with the same `fid` at the nearest earlier step.
    - Uses deep-equal on `Value`, ignoring nothing.
    - Returns an empty set if the frame first appears at `idx`. A newly declared local counts as changed.
  - `pointers.ts`: `pointerVars(frame: Frame, arrayLen: number) => { name: string; index: number; outOfRange: boolean }[]`.
    - Includes `int` locals with `-1 ≤ v ≤ arrayLen`. `outOfRange` = `v < 0 || v >= arrayLen`. Shown red so off-by-one bugs are visible.

- [ ] **Step 1: Write failing tests**
  - Use a synthetic trace builder `mk(depths:number[], lines:number[])`.
  - `replay`:
    - depths `[1,1,2,2,1]`: `stepOver(0..)` from idx 1 → 4; `stepOut` from 2 → 4; `stepInto` from 1 → 2.
    - `continueRun` with bps `{lines[3]}` from 0 → 3; with no bps → 4.
    - `stepOverBack` from 4 → 1; `stepOutBack` from 3 → 1; `reverseContinue` with bps from 4 → 3.
    - Clamping: `stepBack(0)` → 0, `stepInto(last)` → last.
  - `parseArgs`: `'nums = [7, 4, 1, 5, 3]'` → one arg, raw `"[7, 4, 1, 5, 3]"`. `'words = ["a,b", "c"], k = -1'` → two args, `words` raw `'["a,b", "c"]'`, `k` raw `"-1"`. Newline-separated works. `'x = [1,'` → `ok:false`. `'a=1, a=2'` → `ok:false`.
  - `scanMethods`: QuickSort code → names `[quickSort, quick, placeInPosition]`, with `quick` params `[{type:"int[]",name:"nums"},{type:"int",name:"low"},{type:"int",name:"high"}]`. A `private` helper is excluded. `List<List<Integer>> f(List<String> words, int k)` parses generics.
  - `diff`: `i` changes 0→1 between two steps of fid 7 → `{"i"}`. An unrelated frame does not count. First appearance → empty set.
  - `pointers`: locals `i=0, j=5, low=-1, x=99` with len 5 → `i` in range, `j` outOfRange, `low` outOfRange, `x` excluded.
- [ ] **Step 2:** Run `pnpm -F web test`. Expected: FAIL. Add vitest config + script.
- [ ] **Step 3:** Implement the five libs.
- [ ] **Step 4:** Run `pnpm -F web test`. Expected: PASS.
- [ ] **Step 5:** Commit: `feat(web): replay engine and input parsing`.

---

### Task 8: Local tracer bridge + API routes + debug store

**Files:**
- Create: `lib/tracerRunner.ts`, `lib/validate.ts`, `app/api/debug/route.ts`, `app/api/run/route.ts`, `store/debugStore.ts`, `lib/samples.ts`
- Test: `lib/tracerRunner.test.ts`, `lib/validate.test.ts`, `store/debugStore.test.ts`, fixtures `test/fixtures/fake-java-ok.sh` (prints a fixed Trace JSON, ignores args) and `test/fixtures/fake-java-sleep.sh` (`sleep 30`)

**Interfaces:**
- Consumes: Task 7 libs; tracer CLI (Task 5: job on stdin → one Trace JSON on stdout, exit 0).
- Produces:
  - `resolveJava(env: NodeJS.ProcessEnv) -> { javaBin: string; tracerJar: string }`. `javaBin` = `env.JAVA_HOME ? path.join(env.JAVA_HOME, "bin", "java") : "java"`. `tracerJar` = `env.TRACER_JAR ?? path.resolve(process.cwd(), "../../packages/tracer/build/tracer.jar")`.
  - `runTracer(jobJson: string, opts: { javaBin: string; tracerJar: string; wallMs: number }) -> Promise<Trace>`.
    - Spawns `[javaBin, "-jar", tracerJar, "-"]`, writes `jobJson` to stdin, and SIGKILLs at `wallMs + 3000`.
    - Killed with empty stdout → `{status:"timeout", steps:[], stdout:"", stats:{steps:0, ms:wallMs, truncated:false}}`.
    - Spawn `ENOENT` → rejects with `JavaNotFoundError` (message `Java not found. Install JDK 17+ or set JAVA_HOME.`).
    - Missing `tracerJar` (checked with `fs.existsSync` before spawning) → rejects with `TracerMissingError` (message `Tracer not built. Run: pnpm -F tracer build`).
    - Unparseable stdout → rejects with `Error("Tracer failed: " + stderr.slice(0, 2000))`.
  - `DebugRequestSchema` (zod) in `validate.ts` rejects: `code` > 51_200 bytes; `code` without `/class\s+Solution\b/`; `JSON.stringify(args)` > 10_240 bytes; arg names not `/^[A-Za-z_$][\w$]*$/`.
  - Routes `POST /api/debug` and `POST /api/run`, both with `export const runtime = "nodejs"`.
    - They parse the body with the schema; failure → 400 `{error}`.
    - They build the job `{code, method, args, record}`: `record` is `true` for debug and `false` for run, with no limits field so the tracer defaults apply. Then they call `runTracer` and return 200 `Trace`.
    - `JavaNotFoundError` / `TracerMissingError` / other errors → 500 `{error: message}`.
  - `samples.ts`: `samples: { id: string; title: string; code: string; args: string }[]`. Contents: `quick-sort` (user's code, args `nums = [7, 4, 1, 5, 3]`), `two-sum` (args `nums = [2,7,11,15], target = 9`), `sort-colors` (void, `nums = [2,0,2,1,1,0]`), `group-anagrams` (`List<List<String>>` return, `strs = ["eat","tea","tan","ate","nat","bat"]`).
  - `useDebugStore` (zustand + `persist`, key `dsa-debugger:v1`; persists only `code, argsText, method, breakpoints, sampleId`). State: `code, argsText, method: string|null, breakpoints: number[], sampleId, trace: Trace|null, idx: number, selectedFid: number|null, phase: "idle"|"running"|"ready"|"error", error: string|null`. Actions:
    - `debug()`: parse args; on failure set `phase:"error", error` and do not fetch. Otherwise POST `/api/debug` and set `trace`, `idx:0`, `phase:"ready"`. Non-200 → `error` from body.
    - `run()`: same, via `/api/run`.
    - `nav(fn: typeof stepOver)`, `setIdx(n)`, `toggleBreakpoint(line)`, `loadSample(id)`.
    - `setCode(c)` clears `trace` (stale trace lines would mislead).
    - Both `debug()` and `run()` return immediately if `phase === "running"`. The Debug and Run buttons read `phase` to disable themselves.

- [ ] **Step 1: Write failing tests**
  - `tracerRunner.test.ts`:
    - `fake-java-ok.sh` as `javaBin` → resolves to the fixed trace. Use any existing file as `tracerJar`.
    - `javaBin: "/nonexistent/java"` → rejects `JavaNotFoundError`.
    - A nonexistent `tracerJar` → rejects `TracerMissingError`.
    - `fake-java-sleep.sh` with `wallMs: 200` → resolves `status "timeout"` within 4 s.
    - Integration (`it.skipIf(!existsSync(realJar))`): real `java` + `packages/tracer/build/tracer.jar` + QuickSort job → `status "ok"`, result values `[1,3,4,5,7]`.
  - `validate.test.ts`: a valid quickSort request passes. 60 KB code fails. Code without Solution fails. Arg name `1x` fails.
  - `debugStore.test.ts` (mock `fetch`):
    - `debug()` with bad args sets `phase "error"` and never calls fetch.
    - A success sets `trace` and `idx 0`.
    - `setCode` clears `trace`. `toggleBreakpoint(5)` twice → `[]`. `nav(stepInto)` advances `idx`.
    - Calling `debug()` twice without awaiting → fetch called once.
- [ ] **Step 2:** Run `pnpm -F web test`. Expected: FAIL.
- [ ] **Step 3:** Implement per Interfaces.
- [ ] **Step 4:** Run `pnpm -F tracer build && pnpm -F web test`. Expected: PASS, including the integration test.
- [ ] **Step 5:** Commit: `feat(web): local tracer bridge, API routes, samples and debug store`.

---

### Task 9: Web IDE shell (editor, toolbar, timeline, keyboard)

**Files:**
- Create: `app/layout.tsx`, `app/page.tsx`, `app/globals.css`, `components/{Header,Editor,DebugToolbar,Timeline,InputPanel,StatusBanner}.tsx`
- Install: `@monaco-editor/react zustand framer-motion react-resizable-panels lucide-react`. Initialize shadcn (`button select tooltip slider badge`).

**Interfaces:**
- Consumes: `useDebugStore` (Task 8), `replay.ts` (Task 7), `scanMethods` (Task 7).
- Produces: `data-testid` hooks used by Task 11 e2e:
  - `debug-btn`, `run-btn`
  - `step-over`, `step-into`, `step-out`, `continue`, `step-back`
  - `timeline`, `current-line` (attribute `data-line` on a hidden span), `status-banner`, `args-input`, `method-select`

**Decisions:**
- Dark theme. Inter for UI, JetBrains Mono for code. Monaco theme `vs-dark` with custom decoration colors.
- Layout per the spec diagram, using `PanelGroup`.
- Editor:
  - Gutter click toggles a breakpoint (red dot glyph, `glyphMargin: true`).
  - Current line: yellow background via `deltaDecorations`.
  - Lines of other stack frames get a dimmed green.
  - Reveal the current line in center.
  - The editor stays editable at all times. Editing calls `setCode`, which clears the trace.
- Compile errors → `monaco.editor.setModelMarkers`.
- Exception line → red line decoration.
- Keyboard (window `keydown`, `preventDefault` so F5 does not reload): F5 stepInto, F6 stepOver, F7 stepOut, F8 continueRun. Shift+F5/F6/F7/F8 map to stepBack/stepOverBack/stepOutBack/reverseContinue. Ctrl/Cmd+Enter = debug.
- Timeline:
  - Slider over the step index.
  - Tick markers: red at breakpoint steps, purple at `return`, orange at `exception`.
  - Label `Step {idx+1} / {n}`.
- StatusBanner copy:
  - `step_limit`: `Stopped after {n} steps — likely an infinite loop. You can still replay what ran.`
  - `timeout`: `Timed out — your code ran longer than 10 s.`
  - `runtime_error`: `{exception.type}: {exception.message} (line {line})`, or `trace.error`.
  - `compile_error`: `Compilation failed — see red markers.`
- Method select: options from `scanMethods(code)` plus `Auto`.

- [ ] **Step 1:** Implement components and page.
- [ ] **Step 2:** Run `pnpm -F web build`. Expected: build succeeds, no type errors.
- [ ] **Step 3:** Manual check with `pnpm dev` (builds tracer, starts Next on http://127.0.0.1:3000).
  - Load the quick-sort sample and click Debug.
  - F6 moves the yellow line. Shift+F6 goes back.
  - A breakpoint on `int temp = nums[i];` + F8 stops there.
  - A syntax error shows a red squiggle.
- [ ] **Step 4:** Commit: `feat(web): IDE shell with editor, toolbar, timeline`.

---

### Task 10: Web inspection panels (variables, call stack, arrays, console, result)

**Files:**
- Create: `components/{VariablesPanel,ValueView,CallStackPanel,ArrayViz,ConsolePanel,ResultPanel}.tsx`, `lib/format.ts`
- Test: `lib/format.test.ts`

**Interfaces:**
- Consumes: `changedVars` and `pointerVars` (Task 7), store (Task 8).
- Produces:
  - `formatValue(v: Value) -> string`: compact one-line display. Examples: `[1, 3, 4, 5, 7]`, `"hi"`, `'x'`, `{k=1}`, `[[1, 2], []]`, `null`, `Pair{a=1, b=2}`, `…` at a truncation.
  - `data-testid`s: `var-<name>` (value text in `data-value`), `result-value`, `frame-<fid>`, `array-<name>`.

**Decisions:**
- VariablesPanel:
  - Shows the selected frame. This is `selectedFid`, else the top frame.
  - Each row is a name, plus an expandable `ValueView` for arrays/lists/maps/objects.
  - Rows in `changedVars` get a yellow background that fades over 600 ms.
  - On a `return` step, the panel shows a `↩ quick() returned 3` row on top.
  - Reference values (`array`, `list`, `map`, `set`, `obj`, `str`) show a muted `(id=<id>)` suffix, as Eclipse does. Equal ids across frames reveal aliasing.
- CallStackPanel:
  - Top frame first, as `method():line`.
  - Clicking sets `selectedFid`, and the editor highlights that frame's line.
  - It resets to the top frame on each `idx` change.
- ArrayViz:
  - For each `array`/`list` local of `int`/`char`/`String` elements in the selected frame, render cells with index labels.
  - Pointer badges come from `pointerVars`, and multiple pointers stack. Out-of-range badges are red and sit left of index 0 or right of the last index.
  - Cells are keyed by `index` so values change in place. A cell whose value changed since the previous step pulses.
  - `int[][]`/`char[][]` render as a grid. Pairs of int locals named like `r/c`, `row/col`, or `i/j` with valid ranges highlight the cell.
  - Default pointer set = all candidates. The user can uncheck one via a chip list, held in component state.
- ConsolePanel: concatenates `steps[0..idx].stdout`.
- ResultPanel:
  - Phase `ready` and status `ok` → `formatValue(result)`. If `result.t==="void"`, it shows `finalArgs` as `nums = [0, 1, 2]` with the label `Method returned void — final argument values:`.
  - For `run()` results it shows the same.
  - An optional "Expected" text box compares against `formatValue(result)` with whitespace normalized, and shows a green ✓ or red ✗ badge.

- [ ] **Step 1: Write failing test** `format.test.ts`:
  - int array → `[1, 3, 4, 5, 7]`
  - nested list → `[[1, 2], []]`
  - char → `'x'`, str → `"hi"`, map → `{k=1}`, null → `null`
  - truncated array of 1000 → ends with `, …]`
  - obj → `Pair{a=1, b=2}`
- [ ] **Step 2:** Run `pnpm -F web test`. Expected: FAIL.
- [ ] **Step 3:** Implement `format.ts` and the panels.
- [ ] **Step 4:** Run `pnpm -F web test && pnpm -F web build`. Expected: PASS + build OK.
- [ ] **Step 5:** Commit: `feat(web): variables, call stack, array visualizer, console, result`.

---

### Task 11: End-to-end test (Playwright)

**Files:**
- Create: `apps/web/playwright.config.ts`, `apps/web/e2e/debug.spec.ts`

**Interfaces:**
- Consumes: the test ids from Tasks 9–10.
- `webServer`: a single entry, `command: "pnpm -F tracer build && pnpm -F web dev"`, `url: "http://127.0.0.1:3000"`, `reuseExistingServer: true`, `timeout: 120_000`. `use.baseURL` = `http://127.0.0.1:3000`.

- [ ] **Step 1: Write the test** `quickSortDebugFlow`:
  1. Go to `/`, select sample `quick-sort`, and click `debug-btn`.
  2. Wait for `current-line[data-line]` to equal the line of `int low = 0;`.
  3. Press F5 until `current-line` is inside `placeInPosition`, at most 30 presses. Record `var-i` `data-value`. Press F6 until `var-i` changes, at most 20 presses. Assert it changed.
  4. Click the gutter on the `nums[i] = nums[j];` line, then press F8. Assert `current-line` = that line.
  5. Press Shift+F6. Assert `current-line` decreased in step order: the timeline value is lower.
  6. Click `timeline` at its end. Assert `result-value` text = `[1, 3, 4, 5, 7]`.
  7. Edit `nums[i] <= pivot` to `nums[i] < pivot`, then Debug again. Assert `result-value` text ≠ `[1, 3, 4, 5, 7]`, or that `status-banner` is visible.
- [ ] **Step 2:** Run `pnpm -F web exec playwright install chromium && pnpm -F web exec playwright test`. Expected: PASS.
- [ ] **Step 3:** Commit: `test(web): e2e quickSort debug flow`.

---

### Task 12: README + local start scripts

**Files:**
- Create: `README.md`
- Modify: root `package.json` scripts, `apps/web/package.json` scripts

**Decisions:**
- Root scripts:
  - `"dev": "pnpm -F tracer build && pnpm -F web dev"`
  - `"start": "pnpm -F tracer build && pnpm -F web build && pnpm -F web start"`
  - `"test": "pnpm -F tracer test && pnpm -F web test"`
- Web scripts: `"dev": "next dev -H 127.0.0.1 -p 3000"`, `"start": "next start -H 127.0.0.1 -p 3000"`.
- README sections:
  - **Prerequisites:** Node 22+, JDK 17+ (a JDK, not a JRE), and pnpm via `corepack enable`.
  - **Run:** `pnpm install`, then `pnpm dev`, then open http://127.0.0.1:3000.
  - **Keyboard shortcuts:** F5/F6/F7/F8, Shift+ variants, Ctrl/Cmd+Enter.
  - **Supported input types.**
  - **Limits:** 5000 steps, 10 s.
  - **Env overrides:** `JAVA_HOME`, `TRACER_JAR`.
  - **Security warning:** the app executes any pasted code with your user permissions, so keep it bound to 127.0.0.1 and never expose it.

- [ ] **Step 1:** Write the README and scripts.
- [ ] **Step 2:** From a clean clone path: run `pnpm install && pnpm dev`. Expected: tracer builds and Next logs `http://127.0.0.1:3000`.
- [ ] **Step 3:** Run `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3000`. Expected: `200`. Run `curl --max-time 3 http://$(ipconfig getifaddr en0):3000`. Expected: connection refused.
- [ ] **Step 4:** Run `pnpm test`. Expected: all tracer and web tests pass.
- [ ] **Step 5:** Commit: `docs: README and local start scripts`.
