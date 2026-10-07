import { handleTrace } from "@/lib/handleTrace";

export const runtime = "nodejs";

export function POST(req: Request) {
  return handleTrace(req, true);
}
