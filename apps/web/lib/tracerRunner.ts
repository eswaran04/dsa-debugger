import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import type { Trace } from "@dsa/shared";

export class JavaNotFoundError extends Error {
  constructor() {
    super("Java not found. Install JDK 17+ or set JAVA_HOME.");
  }
}

export class TracerMissingError extends Error {
  constructor() {
    super("Tracer not built. Run: pnpm -F tracer build");
  }
}

export type TracerOpts = { javaBin: string; tracerJar: string; wallMs: number };

export function resolveJava(env: NodeJS.ProcessEnv): { javaBin: string; tracerJar: string } {
  return {
    javaBin: env.JAVA_HOME ? path.join(env.JAVA_HOME, "bin", "java") : "java",
    tracerJar: env.TRACER_JAR ?? path.resolve(process.cwd(), "../../packages/tracer/build/tracer.jar"),
  };
}

/** Runs `java -jar tracer.jar -` with the job on stdin; SIGKILLs it at wallMs + 3 s. */
export function runTracer(jobJson: string, opts: TracerOpts): Promise<Trace> {
  if (!existsSync(opts.tracerJar)) return Promise.reject(new TracerMissingError());
  return new Promise((resolve, reject) => {
    const child = spawn(opts.javaBin, ["-jar", opts.tracerJar, "-"], { stdio: ["pipe", "pipe", "pipe"] });
    const out: Buffer[] = [];
    const err: Buffer[] = [];
    let killed = false;
    const timer = setTimeout(() => {
      killed = true;
      child.kill("SIGKILL");
    }, opts.wallMs + 3000);
    child.stdout.on("data", (b: Buffer) => out.push(b));
    child.stderr.on("data", (b: Buffer) => err.push(b));
    child.on("error", (e: NodeJS.ErrnoException) => {
      clearTimeout(timer);
      reject(e.code === "ENOENT" ? new JavaNotFoundError() : e);
    });
    child.on("close", () => {
      clearTimeout(timer);
      const stdout = Buffer.concat(out).toString("utf8").trim();
      if (killed && !stdout) {
        resolve({ status: "timeout", steps: [], stdout: "", stats: { steps: 0, ms: opts.wallMs, truncated: false } });
        return;
      }
      try {
        resolve(JSON.parse(stdout) as Trace);
      } catch {
        reject(new Error("Tracer failed: " + Buffer.concat(err).toString("utf8").slice(0, 2000)));
      }
    });
    child.stdin.on("error", () => {}); // the process may exit before reading stdin
    child.stdin.end(jobJson);
  });
}
