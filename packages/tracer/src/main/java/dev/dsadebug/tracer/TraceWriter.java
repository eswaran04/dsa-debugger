package dev.dsadebug.tracer;

import dev.dsadebug.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Renders a TraceModel as the shared Trace JSON. Steps are written one at a time. */
final class TraceWriter {
    private TraceWriter() {}

    /** Slack for fields whose length depends on what is kept (stats.steps digits, truncated flag). */
    private static final int SLACK = 32;

    /**
     * Writes the trace within limits.maxTraceBytes(): trailing steps are dropped until it fits, and
     * stats.truncated is set. The status is never changed.
     */
    static String write(TraceModel m, Job.Limits limits) {
        List<String> steps = new ArrayList<>(m.steps.size());
        for (TraceModel.Step s : m.steps) {
            Json.Writer w = new Json.Writer();
            writeStep(w, s);
            steps.add(w.toString());
        }
        boolean wasTruncated = m.truncated;
        m.truncated = true; // measure the header with its longest flag value
        long budget = (long) limits.maxTraceBytes() - render(m, List.of()).size() - SLACK;
        m.truncated = wasTruncated;
        int keep = 0;
        long used = 0;
        for (String s : steps) {
            long n = s.getBytes(StandardCharsets.UTF_8).length + 1L; // + comma
            if (used + n > budget) break;
            used += n;
            keep++;
        }
        if (keep < steps.size()) m.truncated = true;
        return render(m, steps.subList(0, keep)).toString();
    }

    private static Json.Writer render(TraceModel m, List<String> steps) {
        Json.Writer w = new Json.Writer();
        w.beginObj().key("status").str(m.status);
        if (m.compileErrors != null) {
            w.key("compileErrors").beginArr();
            for (SourceCompiler.Diag d : m.compileErrors) {
                w.beginObj().key("line").num(d.line()).key("col").num(d.col()).key("message").str(d.message()).endObj();
            }
            w.endArr();
        }
        if (m.entry != null) {
            w.key("entry").beginObj().key("method").str(m.entry.name()).key("params").beginArr();
            for (int i = 0; i < m.entry.paramNames().size(); i++) {
                w.beginObj()
                        .key("name").str(m.entry.paramNames().get(i))
                        .key("type").str(m.entry.paramTypes().get(i))
                        .endObj();
            }
            w.endArr().endObj();
        }
        w.key("steps").beginArr();
        for (String step : steps) w.raw(step);
        w.endArr();
        if (m.resultJson != null) w.key("result").raw(m.resultJson);
        if (m.finalArgs != null) {
            w.key("finalArgs").beginArr();
            for (TraceModel.Var v : m.finalArgs) w.beginObj().key("name").str(v.name()).key("value").raw(v.valueJson()).endObj();
            w.endArr();
        }
        if (m.exception != null) {
            w.key("exception").beginObj()
                    .key("type").str(m.exception.type())
                    .key("message").str(m.exception.message())
                    .key("line").num(m.exception.line())
                    .endObj();
        }
        if (m.error != null) w.key("error").str(m.error);
        w.key("stdout").str(m.stdout);
        w.key("stats").beginObj()
                .key("steps").num(steps.size())
                .key("ms").num(m.ms)
                .key("truncated").bool(m.truncated)
                .endObj();
        return w.endObj();
    }

    static void writeStep(Json.Writer w, TraceModel.Step s) {
        w.beginObj()
                .key("line").num(s.line)
                .key("depth").num(s.depth)
                .key("event").str(s.event)
                .key("stack").raw(s.stackJson);
        if (!s.stdout.isEmpty()) w.key("stdout").str(s.stdout);
        if (s.returnValueJson != null) w.key("returnValue").raw(s.returnValueJson);
        if (s.method != null) w.key("method").str(s.method);
        w.endObj();
    }
}
