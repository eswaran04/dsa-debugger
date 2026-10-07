export type ArgInput = { name: string; raw: string };
export type DebugRequest = { code: string; method?: string | null; args: ArgInput[] };
export type ApiError = { error: string };
