import { z } from "zod";

const bytes = (s: string) => Buffer.byteLength(s, "utf8");

export const DebugRequestSchema = z.object({
  code: z
    .string()
    .refine((c) => bytes(c) <= 51_200, "Code is larger than 50 KB")
    .refine((c) => /class\s+Solution\b/.test(c), "Code must contain class Solution"),
  method: z.string().nullable().optional(),
  args: z
    .array(z.object({ name: z.string().regex(/^[A-Za-z_$][\w$]*$/, "Invalid argument name"), raw: z.string() }))
    .refine((a) => bytes(JSON.stringify(a)) <= 10_240, "Arguments are larger than 10 KB"),
});
