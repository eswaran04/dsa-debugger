import { DebugRequestSchema } from "./validate";
import { resolveJava, runTracer } from "./tracerRunner";

const WALL_MS = 10_000;

/** Shared body of /api/debug and /api/run: validate, run the tracer, return the Trace. */
export async function handleTrace(req: Request, record: boolean): Promise<Response> {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return Response.json({ error: "Invalid JSON body" }, { status: 400 });
  }
  const parsed = DebugRequestSchema.safeParse(body);
  if (!parsed.success) return Response.json({ error: parsed.error.issues[0].message }, { status: 400 });
  const { code, method, args } = parsed.data;
  try {
    const trace = await runTracer(JSON.stringify({ code, method: method ?? null, args, record }), {
      ...resolveJava(process.env),
      wallMs: WALL_MS,
    });
    return Response.json(trace);
  } catch (e) {
    return Response.json({ error: e instanceof Error ? e.message : String(e) }, { status: 500 });
  }
}
