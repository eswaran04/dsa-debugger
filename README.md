# DSA Debugger

Paste a Java `class Solution { ... }`, give named inputs, and step through it line by line like Eclipse. You can see the current line, the variables (changes are highlighted), the call stack, and arrays with `i`/`j`/`low`/`high` pointers. You can also step **backwards**.

It runs only on your machine and uses your own JDK.

## Prerequisites

- Node 22+
- **JDK 17+**. You need a full JDK, not a JRE, because the debugger uses `javac` and JDI.
- pnpm 9. Run `corepack enable`, or use `npx -y pnpm@9.15.9` wherever these docs say `pnpm`.

## Run

```bash
pnpm install
pnpm dev          # builds the Java tracer, then starts http://127.0.0.1:3000
```

Production mode: `pnpm start`.

## Use

1. Pick a sample or paste your own `Solution` class.
2. Enter inputs as named args, e.g. `nums = [2, 7, 11, 15], target = 9`. Values are JSON.
3. Press **Debug** (Ctrl/⌘+Enter). Then use the keys below:

| Key | Action | With Shift |
|-----|--------|------------|
| F5  | Step into | Step back |
| F6  | Step over | Step over backwards |
| F7  | Step out | Step out backwards |
| F8  | Resume to next breakpoint | Reverse to previous breakpoint |

To set a breakpoint, click the gutter. Drag the timeline to jump to any step. **Run** executes your code without recording, so you can check the output quickly.

The entry method is picked by matching your argument names to its parameter names. You can also choose it from the **Method** dropdown.

**Supported input types:** `int long double boolean char String int[] int[][] char[][] String[] List<Integer> List<List<Integer>> List<String>`

**Limits:** a run stops after 5000 steps, which usually means an infinite loop, or after 10 s. You can still replay what ran.

## Configuration

| Env var | Default |
|---------|---------|
| `JAVA_HOME` | `java` on `PATH` |
| `TRACER_JAR` | `packages/tracer/build/tracer.jar` |

## Security

**This app executes any code pasted into it, with your user's permissions.** The server listens on `127.0.0.1` only. Do not change that, and do not expose this app to a network.

## Layout

- `packages/tracer`: Java CLI. It compiles the code, runs it under JDI, and prints a Trace JSON. Build with `./build.sh`; tests run with `./test.sh`.
- `packages/shared`: TypeScript types for the Trace JSON.
- `apps/web`: the Next.js UI. Its API routes spawn the tracer.
