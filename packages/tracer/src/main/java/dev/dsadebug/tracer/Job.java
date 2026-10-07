package dev.dsadebug.tracer;

import dev.dsadebug.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A tracing request: user code, optional entry method, named raw-JSON args, and limits. */
public record Job(String code, String method, List<Arg> args, boolean record, Limits limits) {
    public record Arg(String name, String raw) {}

    public record Limits(int maxSteps, int maxTraceBytes, int wallMs, int maxStdoutBytes) {
        public static final Limits DEFAULTS = new Limits(5000, 5242880, 10000, 65536);
    }

    /** Parses a job. "args" must be a list of {"name":..., "raw":...}. */
    public static Job fromJson(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("job must be a JSON object");
        Map<?, ?> m = (Map<?, ?>) root;
        Object code = m.get("code");
        if (!(code instanceof String)) throw new IllegalArgumentException("job.code must be a string");
        Object method = m.get("method");
        List<Arg> args = new ArrayList<>();
        Object a = m.get("args");
        if (a != null) {
            if (!(a instanceof List)) throw new IllegalArgumentException("job.args must be a list of {name, raw}");
            for (Object o : (List<?>) a) {
                if (!(o instanceof Map)) throw new IllegalArgumentException("each arg must be an object {name, raw}");
                Map<?, ?> am = (Map<?, ?>) o;
                if (!(am.get("name") instanceof String) || !(am.get("raw") instanceof String)) {
                    throw new IllegalArgumentException("each arg needs string fields name and raw");
                }
                args.add(new Arg((String) am.get("name"), (String) am.get("raw")));
            }
        }
        Object rec = m.get("record");
        boolean record = !(rec instanceof Boolean) || (Boolean) rec;
        Limits d = Limits.DEFAULTS;
        Limits limits = d;
        if (m.get("limits") instanceof Map) {
            Map<?, ?> l = (Map<?, ?>) m.get("limits");
            limits = new Limits(
                    num(l, "maxSteps", d.maxSteps()),
                    num(l, "maxTraceBytes", d.maxTraceBytes()),
                    num(l, "wallMs", d.wallMs()),
                    num(l, "maxStdoutBytes", d.maxStdoutBytes()));
        }
        return new Job((String) code, method instanceof String ? (String) method : null, args, record, limits);
    }

    private static int num(Map<?, ?> m, String k, int def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }
}
