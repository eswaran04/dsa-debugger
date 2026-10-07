package dev.dsadebug.tracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dsadebug.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TraceLimitsTest {
    private static final Job.Limits D = Job.Limits.DEFAULTS;
    private static final List<Job.Arg> N0 = List.of(new Job.Arg("n", "0"));

    private record Run(String json, Map<String, Object> t, long ms) {}

    private static Run run(String fixture, List<Job.Arg> args, boolean record, Job.Limits limits) {
        long start = System.nanoTime();
        String json = new TraceRecorder().run(new Job(Fixtures.read(fixture), null, args, record, limits));
        return new Run(json, map(Json.parse(json)), (System.nanoTime() - start) / 1_000_000);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static List<Map<String, Object>> steps(Map<String, Object> t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object s : list(t.get("steps"))) out.add(map(s));
        return out;
    }

    private static Map<String, Object> exception(Map<String, Object> t) {
        return map(t.get("exception"));
    }

    private static long lineOf(String fixture, String text) {
        String[] lines = Fixtures.read(fixture).split("\n");
        for (int k = 0; k < lines.length; k++) if (lines[k].contains(text)) return k + 1;
        throw new IllegalArgumentException("no line containing " + text);
    }

    @Test
    void infiniteLoopHitsStepLimit() {
        Run r = run("InfiniteLoop.java", N0, true, new Job.Limits(500, D.maxTraceBytes(), D.wallMs(), D.maxStdoutBytes()));
        assertEquals("step_limit", r.t().get("status"), String.valueOf(r.t().get("error")));
        assertEquals(500, steps(r.t()).size());
        assertTrue(r.ms() < D.wallMs(), "took " + r.ms() + " ms");
    }

    @Test
    void indexOutOfBounds() {
        Run r = run("OutOfBounds.java", List.of(new Job.Arg("nums", "[1,2,3,4,5]")), true, D);
        assertEquals("runtime_error", r.t().get("status"));
        assertEquals("java.lang.ArrayIndexOutOfBoundsException", exception(r.t()).get("type"));
        assertEquals(lineOf("OutOfBounds.java", "nums[10]"), exception(r.t()).get("line"));
        List<Map<String, Object>> steps = steps(r.t());
        assertEquals("exception", steps.get(steps.size() - 1).get("event"));
    }

    @Test
    void stackOverflowReported() {
        Run quiet = run("NoBaseCase.java", N0, false, D);
        assertEquals("runtime_error", quiet.t().get("status"), String.valueOf(quiet.t().get("error")));
        assertEquals("java.lang.StackOverflowError", exception(quiet.t()).get("type"));

        Run rec = run("NoBaseCase.java", N0, true, D);
        String status = (String) rec.t().get("status");
        assertTrue(status.equals("step_limit") || status.equals("runtime_error"), status);
        assertTrue(rec.ms() < D.wallMs(), "took " + rec.ms() + " ms");
        for (Map<String, Object> s : steps(rec.t())) assertTrue(list(s.get("stack")).size() <= 64);
        assertTrue(rec.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= D.maxTraceBytes());
    }

    @Test
    void exceptionInsideJdkCall() {
        Run r = run("OutOfBounds.java", N0, true, D);
        assertEquals("runtime_error", r.t().get("status"));
        assertEquals("java.lang.IndexOutOfBoundsException", exception(r.t()).get("type"));
        assertEquals(lineOf("OutOfBounds.java", "list.get(0)"), exception(r.t()).get("line"));
    }

    @Test
    void printForeverCapped() {
        Run r = run("PrintForever.java", N0, true, D);
        assertEquals("step_limit", r.t().get("status"));
        assertTrue(((String) r.t().get("stdout")).length() <= 65536 + 32);
    }

    @Test
    void caughtExceptionIgnored() {
        Run r = run("CaughtInside.java", N0, true, D);
        assertEquals("ok", r.t().get("status"), String.valueOf(r.t().get("error")));
        assertEquals(-1L, map(r.t().get("result")).get("v"));
    }

    @Test
    void wallTimeout() {
        Run r = run("SleepForever.java", N0, true, new Job.Limits(D.maxSteps(), D.maxTraceBytes(), 2000, D.maxStdoutBytes()));
        assertEquals("timeout", r.t().get("status"));
        assertTrue(r.ms() < 4000, "took " + r.ms() + " ms");
        assertTrue(!steps(r.t()).isEmpty(), "partial steps kept");
    }

    @Test
    void traceByteCap() {
        Random rnd = new Random(42);
        StringBuilder nums = new StringBuilder("[");
        for (int k = 0; k < 200; k++) nums.append(k == 0 ? "" : ",").append(rnd.nextInt(1000));
        nums.append("]");
        Run r = run("QuickSort.java", List.of(new Job.Arg("nums", nums.toString())), true,
                new Job.Limits(D.maxSteps(), 200_000, D.wallMs(), D.maxStdoutBytes()));
        assertTrue(r.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 200_000,
                "size " + r.json().length());
        assertEquals(true, map(r.t().get("stats")).get("truncated"));
    }
}
